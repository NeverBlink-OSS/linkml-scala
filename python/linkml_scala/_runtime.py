"""Loading the shared library, and calling into it.

Everything ctypes-shaped lives here. The public API in ``__init__`` deals in strings and dicts.

The library exports one function per operation. Options travel as one JSON string, which may be NULL
for defaults, so the common case never builds any JSON at all. Documents come back as plain strings,
so a multi-megabyte SHACL graph is not escaped into JSON and parsed straight back out.
"""

from __future__ import annotations

import ctypes
import json
import os
import sys
import threading
from pathlib import Path
from typing import Any, Mapping

from ._generated import DOCUMENT_FUNCTIONS

__all__ = ["Runtime", "LinkMlError", "NativeLibraryNotFound", "library_path", "runtime"]

# Bumped in lockstep with LinkMlNativeApi.abiVersion on the Scala side.
_EXPECTED_ABI_VERSION = 1

_LIB_STEM = "liblinkml_scala"


# char*, kept as POINTER(c_char) rather than c_char_p: ctypes turns the latter into bytes and
# discards the pointer, leaving nothing to hand to linkml_free.
_Chars = ctypes.POINTER(ctypes.c_char)


class LinkMlError(Exception):
    """The library rejected a call, or failed while handling it."""


class NativeLibraryNotFound(LinkMlError):
    """The shared library could not be found on this machine."""


def _library_filename() -> str:
    if sys.platform == "darwin":
        return f"{_LIB_STEM}.dylib"
    if sys.platform == "win32":
        return f"{_LIB_STEM}.dll"
    return f"{_LIB_STEM}.so"


def _candidates() -> list[Path]:
    """Where to look for the library, best guess first."""
    filename = _library_filename()
    found = []

    explicit = os.environ.get("LINKML_SCALA_LIB")
    if explicit:
        # A directory or the library itself, so both spellings work.
        path = Path(explicit)
        found.append(path / filename if path.is_dir() else path)

    # Installed by `./mill nativelib.installPythonLib`.
    found.append(Path(__file__).parent / "_lib" / filename)

    # A source checkout that built the library but did not install it.
    repo_root = Path(__file__).resolve().parents[2]
    found.append(repo_root / "out" / "nativelib" / "sharedLibrary.dest" / filename)

    return found


def library_path() -> Path:
    """The library this process would load.

    :raises NativeLibraryNotFound: if none of the candidate locations has it.
    """
    candidates = _candidates()
    for candidate in candidates:
        if candidate.is_file():
            return candidate
    listed = "\n  ".join(str(candidate) for candidate in candidates)
    raise NativeLibraryNotFound(
        f"Could not find {_library_filename()}. Looked in:\n  {listed}\n"
        "Build it with `./mill nativelib.installPythonLib`, or point LINKML_SCALA_LIB at it."
    )


def _options(options: Mapping[str, Any] | None) -> bytes | None:
    """Encode an options mapping, dropping unset values.

    Returns None, which reaches C as NULL, when there is nothing to say. The library then applies
    its own defaults and no JSON is built or parsed on either side.
    """
    if not options:
        return None
    present = {key: value for key, value in options.items() if value is not None}
    if not present:
        return None
    return json.dumps(present).encode("utf-8")


class Runtime:
    """One GraalVM isolate, and the calls into it.

    An isolate is a self-contained heap. Schema handles only mean anything within the isolate that
    made them. Threads have to attach before calling in, which happens on demand and is remembered
    per thread.

    Thread-safe. Prefer the process-wide instance from :func:`runtime` over building your own.
    """

    def __init__(self, path: str | Path | None = None) -> None:
        self._path = Path(path) if path is not None else library_path()
        self._lib = ctypes.CDLL(str(self._path))
        self._declare_signatures()

        isolate = ctypes.c_void_p()
        main_thread = ctypes.c_void_p()
        code = self._lib.graal_create_isolate(None, ctypes.byref(isolate), ctypes.byref(main_thread))
        if code != 0:
            raise LinkMlError(f"graal_create_isolate failed with code {code}")
        self._isolate = isolate

        self._threads = threading.local()
        self._threads.handle = main_thread

        version = self._lib.linkml_abi_version(self._thread())
        if version != _EXPECTED_ABI_VERSION:
            raise LinkMlError(
                f"{self._path} speaks ABI version {version}, but this package expects "
                f"{_EXPECTED_ABI_VERSION}. Rebuild one of them."
            )

    @property
    def path(self) -> Path:
        """The library file backing this runtime."""
        return self._path

    # Loading

    def load_file(
        self, path: str, options: Mapping[str, Any] | None = None
    ) -> tuple[int, dict[str, Any]]:
        """Load a schema from the file system.

        :return: the schema handle, 0 if the schema had fatal problems, and the validation report.
        """
        report, error = _Chars(), _Chars()
        handle = self._lib.linkml_load_file(
            self._thread(),
            path.encode("utf-8"),
            _options(options),
            ctypes.byref(report),
            ctypes.byref(error),
        )
        return self._loaded(handle, report, error)

    def load_string(
        self,
        path: str | None,
        schema: str | None,
        imports: Mapping[str, str] | None = None,
        options: Mapping[str, Any] | None = None,
    ) -> tuple[int, dict[str, Any]]:
        """Load a schema from memory, resolving imports against ``imports``.

        :param path: the root schema's own key in ``imports``, or None to load ``schema`` directly.
        :param schema: the root schema as YAML. Ignored when ``path`` is given.
        :return: the schema handle, 0 if the schema had fatal problems, and the validation report.
        """
        # The map goes over as two parallel arrays rather than JSON: it holds whole schemas, and
        # escaping megabytes of YAML only to parse it back out would undo the point of the split.
        entries = dict(imports or {})
        count = len(entries)
        names = (ctypes.c_char_p * count)(*(key.encode("utf-8") for key in entries))
        bodies = (ctypes.c_char_p * count)(*(value.encode("utf-8") for value in entries.values()))

        report, error = _Chars(), _Chars()
        handle = self._lib.linkml_load_string(
            self._thread(),
            None if path is None else path.encode("utf-8"),
            None if schema is None else schema.encode("utf-8"),
            names if count else None,
            bodies if count else None,
            count,
            _options(options),
            ctypes.byref(report),
            ctypes.byref(error),
        )
        return self._loaded(handle, report, error)

    def close(self, handle: int) -> None:
        """Release a schema handle. Releasing one that is already gone does nothing."""
        self._lib.linkml_close(self._thread(), handle)

    # Generating

    def document(
        self, function: str, handle: int, options: Mapping[str, Any] | None = None
    ) -> str:
        """Call a generator and return its document.

        :param function: the exported name, e.g. ``linkml_shacl``.
        :raises LinkMlError: if the library reported a failure.
        """
        error = _Chars()
        result = getattr(self._lib, function)(
            self._thread(), handle, _options(options), ctypes.byref(error)
        )
        # Take both, so neither leaks whichever way the call went.
        message = self._take(error)
        text = self._take(result)
        if text is None:
            raise LinkMlError(message or f"{function} failed without saying why")
        return text

    def json_document(
        self, function: str, handle: int, options: Mapping[str, Any] | None = None
    ) -> Any:
        """Call a generator whose result is JSON, and return it parsed."""
        return json.loads(self.document(function, handle, options))

    # Internals

    def _loaded(self, handle: int, report: Any, error: Any) -> tuple[int, dict[str, Any]]:
        message = self._take(error)
        report_json = self._take(report)
        if message is not None:
            raise LinkMlError(message)
        return handle, json.loads(report_json) if report_json else {}

    def _take(self, pointer: Any) -> str | None:
        """Read a string the library allocated, and release it. None if the pointer was NULL."""
        if not pointer:
            return None
        try:
            raw = ctypes.cast(pointer, ctypes.c_char_p).value
        finally:
            self._lib.linkml_free(self._thread(), pointer)
        return None if raw is None else raw.decode("utf-8")

    def _thread(self) -> ctypes.c_void_p:
        """This thread's isolate thread, attaching it to the isolate on first use."""
        handle = getattr(self._threads, "handle", None)
        if handle is None:
            handle = ctypes.c_void_p()
            code = self._lib.graal_attach_thread(self._isolate, ctypes.byref(handle))
            if code != 0:
                raise LinkMlError(f"graal_attach_thread failed with code {code}")
            self._threads.handle = handle
        return handle

    def _declare_signatures(self) -> None:
        void_p = ctypes.c_void_p
        handle = ctypes.c_longlong
        chars_out = ctypes.POINTER(_Chars)
        strings = ctypes.POINTER(ctypes.c_char_p)

        self._lib.graal_create_isolate.argtypes = [
            void_p,
            ctypes.POINTER(void_p),
            ctypes.POINTER(void_p),
        ]
        self._lib.graal_create_isolate.restype = ctypes.c_int

        self._lib.graal_attach_thread.argtypes = [void_p, ctypes.POINTER(void_p)]
        self._lib.graal_attach_thread.restype = ctypes.c_int

        self._lib.linkml_abi_version.argtypes = [void_p]
        self._lib.linkml_abi_version.restype = ctypes.c_int

        self._lib.linkml_load_file.argtypes = [
            void_p,
            ctypes.c_char_p,
            ctypes.c_char_p,
            chars_out,
            chars_out,
        ]
        self._lib.linkml_load_file.restype = handle

        self._lib.linkml_load_string.argtypes = [
            void_p,
            ctypes.c_char_p,
            ctypes.c_char_p,
            strings,
            strings,
            ctypes.c_int,
            ctypes.c_char_p,
            chars_out,
            chars_out,
        ]
        self._lib.linkml_load_string.restype = handle

        self._lib.linkml_close.argtypes = [void_p, handle]
        self._lib.linkml_close.restype = None

        # linkml_lint has the same shape but is not a generator, so it is not in the generated
        # list and gets declared alongside it.
        for name in (*DOCUMENT_FUNCTIONS, "linkml_lint"):
            function = getattr(self._lib, name)
            function.argtypes = [void_p, handle, ctypes.c_char_p, chars_out]
            function.restype = _Chars

        self._lib.linkml_free.argtypes = [void_p, _Chars]
        self._lib.linkml_free.restype = None


_runtime: Runtime | None = None
_runtime_lock = threading.Lock()


def runtime() -> Runtime:
    """The process-wide runtime, created on first use.

    One isolate per process keeps memory use predictable and lets schema handles be passed around
    freely, since they only mean anything within the isolate that made them.
    """
    global _runtime
    with _runtime_lock:
        if _runtime is None:
            _runtime = Runtime()
        return _runtime
