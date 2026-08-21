"""Helpers for managing platform tags on the wheel built by hatchling.

The platform tag must name the oldest system the library still runs on, so that pip installs the
correct wheel. We read that out of the library itself:

  * Linux: the highest ``GLIBC_x.y`` the library asks for (manylinux).
  * macOS: the minimum OS version recorded in the Mach-O header.
  * Windows: no such thing exists, so the tag is only the architecture.

Set ``LINKML_SCALA_WHEEL_PLATFORM`` to override the whole platform tag. Run it like::

    python hatch_build.py linkml_scala/_lib/liblinkml_scala.so
"""

from __future__ import annotations

import os
import platform
import struct
import sys
from pathlib import Path

LIBRARY_EXTENSIONS = (".so", ".dylib", ".dll")

LINUX_ARCHITECTURES = {
    "x86_64": "x86_64",
    "amd64": "x86_64",
    "aarch64": "aarch64",
    "arm64": "aarch64",
}
MACOS_ARCHITECTURES = {"x86_64": "x86_64", "amd64": "x86_64", "arm64": "arm64", "aarch64": "arm64"}
WINDOWS_ARCHITECTURES = {"amd64": "amd64", "x86_64": "amd64", "arm64": "arm64"}

# musl does not record its version in the binary, so unlike glibc this cannot be derived. 1.2 is
# what every currently supported Alpine has.
MUSLLINUX_VERSION = (1, 2)


def find_library(root: Path) -> Path:
    """The one shared library staged inside the package."""
    lib_dir = root / "linkml_scala" / "_lib"
    found = sorted(p for p in lib_dir.glob("*") if p.suffix in LIBRARY_EXTENSIONS)
    if len(found) != 1:
        raise RuntimeError(
            f"expected exactly one shared library in {lib_dir}, found {len(found)}. "
            "Build it with `./mill nativelib.installPythonLib`."
        )
    return found[0]


def platform_tag(library: Path) -> str:
    """The platform part of the wheel tag for a library, e.g. ``manylinux_2_34_x86_64``."""
    override = os.environ.get("LINKML_SCALA_WHEEL_PLATFORM")
    if override:
        return override

    machine = platform.machine().lower()
    if sys.platform.startswith("linux"):
        return linux_tag(library, architecture(LINUX_ARCHITECTURES, machine))
    if sys.platform == "darwin":
        major, minor = macho_minimum_os(library)
        return f"macosx_{major}_{minor}_{architecture(MACOS_ARCHITECTURES, machine)}"
    if sys.platform == "win32":
        return f"win_{architecture(WINDOWS_ARCHITECTURES, machine)}"
    raise RuntimeError(f"no wheel platform tag known for {sys.platform}")


def architecture(known: dict[str, str], machine: str) -> str:
    if machine not in known:
        raise RuntimeError(f"unsupported architecture {machine!r} on {sys.platform}")
    return known[machine]


def linux_tag(library: Path, arch: str) -> str:
    """``manylinux_x_y_<arch>`` or ``musllinux_1_2_<arch>``, depending on which libc it links.

    glibc puts a version on every symbol it exports, so anything linked against it asks for some
    ``GLIBC_x.y``. musl does not version its symbols at all, so finding none means musl.
    """
    versions = elf_glibc_requirements(library)
    if not versions:
        return f"musllinux_{MUSLLINUX_VERSION[0]}_{MUSLLINUX_VERSION[1]}_{arch}"
    major, minor = max(versions)
    return f"manylinux_{major}_{minor}_{arch}"


def elf_glibc_requirements(library: Path) -> list[tuple[int, int]]:
    """Every ``GLIBC_x.y`` version the library asks for. Empty for a musl build.

    Read from the ELF version-requirement table.
    """
    data = library.read_bytes()
    if data[:4] != b"\x7fELF":
        raise RuntimeError(f"{library} is not an ELF file")
    if data[4] != 2:
        raise RuntimeError(f"{library} is not 64-bit, and we only package 64-bit builds")
    endian = "<" if data[5] == 1 else ">"

    # ELF64 file header: the section table's offset, entry size and entry count.
    (section_offset,) = struct.unpack_from(endian + "Q", data, 0x28)
    entry_size, entry_count = struct.unpack_from(endian + "HH", data, 0x3A)

    versions: list[tuple[int, int]] = []
    for index in range(entry_count):
        header = section_offset + index * entry_size
        # ELF64 section header: sh_type at +0x04, sh_offset +0x18, sh_link +0x28, sh_info +0x2C.
        (section_type,) = struct.unpack_from(endian + "I", data, header + 0x04)
        if section_type != 0x6FFFFFFE:  # SHT_GNU_verneed, "needs these symbol versions"
            continue
        (table,) = struct.unpack_from(endian + "Q", data, header + 0x18)
        strings_index, needed = struct.unpack_from(endian + "II", data, header + 0x28)
        strings = strings_offset(data, endian, section_offset, entry_size, strings_index)
        versions += verneed_versions(data, endian, table, needed, strings)

    return versions


def strings_offset(
    data: bytes, endian: str, section_offset: int, entry_size: int, index: int
) -> int:
    """Where the string table a section points at starts in the file."""
    (offset,) = struct.unpack_from(endian + "Q", data, section_offset + index * entry_size + 0x18)
    return offset


def verneed_versions(
    data: bytes, endian: str, table: int, needed: int, strings: int
) -> list[tuple[int, int]]:
    """Walk the Verneed/Vernaux lists, collecting the GLIBC versions they point to."""
    versions: list[tuple[int, int]] = []
    entry = table
    for _ in range(needed):
        # Elf64_Verneed: vn_cnt at +0x02, vn_aux +0x08, vn_next +0x0C. Both offsets are relative
        # to the entry they sit in.
        (count,) = struct.unpack_from(endian + "H", data, entry + 0x02)
        aux_offset, next_offset = struct.unpack_from(endian + "II", data, entry + 0x08)
        aux = entry + aux_offset
        for _ in range(count):
            # Elf64_Vernaux: vna_name at +0x08, vna_next at +0x0C.
            name_offset, aux_next = struct.unpack_from(endian + "II", data, aux + 0x08)
            version = glibc_version(read_string(data, strings + name_offset))
            if version is not None:
                versions.append(version)
            if aux_next == 0:
                break
            aux += aux_next
        if next_offset == 0:
            break
        entry += next_offset
    return versions


def read_string(data: bytes, offset: int) -> str:
    end = data.index(b"\0", offset)
    return data[offset:end].decode("utf-8", "replace")


def glibc_version(name: str) -> tuple[int, int] | None:
    """``GLIBC_2.34`` to ``(2, 34)``. Anything else to None."""
    if not name.startswith("GLIBC_"):
        return None
    parts = name[len("GLIBC_") :].split(".")
    if len(parts) < 2 or not all(part.isdigit() for part in parts[:2]):
        return None
    return int(parts[0]), int(parts[1])


def macho_minimum_os(library: Path) -> tuple[int, int]:
    """The minimum macOS version the library needs, as ``(major, minor)``.

    Falls back to the version of the machine doing the build, if the header cannot be read.
    """
    try:
        return macho_load_commands(library.read_bytes())
    except (RuntimeError, struct.error, IndexError) as problem:
        running = platform.mac_ver()[0].split(".")
        major = int(running[0])
        minor = int(running[1]) if len(running) > 1 else 0
        print(
            f"warning: could not read the minimum OS version from {library.name} ({problem}); "
            f"falling back to this machine's macOS {major}.{minor}",
            file=sys.stderr,
        )
        return major, minor


def macho_load_commands(data: bytes) -> tuple[int, int]:
    (magic,) = struct.unpack_from("<I", data, 0)
    if magic != 0xFEEDFACF:  # MH_MAGIC_64, little-endian; both arm64 and x86_64 are little-endian
        raise RuntimeError(f"unexpected Mach-O magic {magic:#x}, expected a thin 64-bit library")
    (command_count,) = struct.unpack_from("<I", data, 0x10)

    offset = 0x20  # straight after the mach_header_64
    for _ in range(command_count):
        command, size = struct.unpack_from("<II", data, offset)
        if command == 0x32:  # LC_BUILD_VERSION: platform, minos, sdk, ntools
            (encoded,) = struct.unpack_from("<I", data, offset + 0x0C)
            return decode_macho_version(encoded)
        if command == 0x24:  # LC_VERSION_MIN_MACOSX: version, sdk
            (encoded,) = struct.unpack_from("<I", data, offset + 0x08)
            return decode_macho_version(encoded)
        offset += size
    raise RuntimeError("no LC_BUILD_VERSION or LC_VERSION_MIN_MACOSX load command")


def decode_macho_version(encoded: int) -> tuple[int, int]:
    """Mach-O packs X.Y.Z as ``xxxx.yy.zz`` in one 32-bit word."""
    return (encoded >> 16) & 0xFFFF, (encoded >> 8) & 0xFF


try:
    from hatchling.builders.hooks.plugin.interface import BuildHookInterface
except ImportError:  # Running this file directly, to see what tag a library would get.
    BuildHookInterface = object  # type: ignore[assignment,misc]


class CustomBuildHook(BuildHookInterface):  # type: ignore[misc]
    """Tags the wheel for one platform, and marks it as containing a binary."""

    def initialize(self, version: str, build_data: dict) -> None:
        library = find_library(Path(self.root))
        tag = f"py3-none-{platform_tag(library)}"
        # Not pure Python, so the files belong in platlib rather than purelib.
        build_data["pure_python"] = False
        build_data["tag"] = tag
        print(f"tagging the wheel {tag}, for {library.name}")


if __name__ == "__main__":
    target = Path(sys.argv[1]) if len(sys.argv) > 1 else find_library(Path(__file__).parent)
    print(f"py3-none-{platform_tag(target)}")
