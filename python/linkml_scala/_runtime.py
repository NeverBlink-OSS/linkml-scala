"""Loading the Scala Native shared library, and calling into it.

Everything ctypes-shaped lives here. The public API in ``__init__`` deals in strings and dicts.

The library exports one function per operation. Options travel as one JSON string, which may be NULL
for defaults, so the common case never builds any JSON at all. Documents come back as plain strings,
so a multi-megabyte SHACL graph is not escaped into JSON and parsed straight back out.
"""

from __future__ import annotations

import ctypes
import json
import os
import queue
import sys
import threading
from pathlib import Path
from typing import Any, Callable, Mapping

from ._generated import DOCUMENT_FUNCTIONS

__all__ = ["Runtime", "LinkMlError", "NativeLibraryNotFound", "library_path", "runtime"]

# Bumped in lockstep with LinkMlNativeApi.abiVersion on the Scala side.
_EXPECTED_ABI_VERSION = 3

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

    # Shipped inside the wheel, or put there by `./mill nativelib.native.installPythonLib`.
    found.append(Path(__file__).parent / "_lib" / filename)

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
        "Build it with `./mill nativelib.native.installPythonLib`, or point LINKML_SCALA_LIB at one you "
        "already have."
    )


def _options(options: Mapping[str, Any] | None) -> bytes | None:
    """Encode an options mapping, dropping unset values.

    Returns None, which reaches C as NULL, when there is nothing to say.
    """
    if not options:
        return None
    present = {key: value for key, value in options.items() if value is not None}
    if not present:
        return None
    return json.dumps(present).encode("utf-8")


class _Worker:
    """LinkML-Scala native worker thread."""

    def __init__(self, path: Path) -> None:
        self._work: queue.SimpleQueue = queue.SimpleQueue()
        ready: queue.SimpleQueue = queue.SimpleQueue()
        self._thread = threading.Thread(
            target=self._loop, args=(path, ready), name="linkml-scala", daemon=True
        )
        self._thread.start()
        ok, value = ready.get()
        if not ok:
            raise value
        self.lib: ctypes.CDLL = value

    def _loop(self, path: Path, ready: queue.SimpleQueue) -> None:
        # Load the library from this thread and keep all calls to this thread.
        try:
            lib = ctypes.CDLL(str(path))
            # dlopen and the library constructor run several frames below this one, so the stack
            # bottom Scala Native recorded is *lower* than where calls will arrive from -- the same
            # inversion as loading from anywhere else, just smaller. Re-record it from here.
            lib.linkml_init_threads.argtypes = []
            lib.linkml_init_threads.restype = ctypes.c_int
            lib.linkml_init_threads()
        except BaseException as error:  # noqa: BLE001 - returned to the caller as-is
            ready.put((False, error))
            return
        ready.put((True, lib))

        while True:
            call, box = self._work.get()
            try:
                box.put((True, call()))
            except BaseException as error:  # noqa: BLE001 - returned to the caller as-is
                box.put((False, error))

    def run(self, call: Callable[[], Any]) -> Any:
        """Run ``call`` on the worker and return its result here."""
        if threading.get_ident() == self._thread.ident:
            # Re-entrant: a finaliser the worker itself triggered. Running it inline is both
            # correct and the only option, since the worker cannot wait for itself.
            return call()
        box: queue.SimpleQueue = queue.SimpleQueue()
        self._work.put((call, box))
        ok, value = box.get()
        if ok:
            return value
        raise value


class Runtime:
    """The loaded library, and the calls into it.

    Thread-safe: calls from any thread are passed to the one worker thread that owns the library.
    They are therefore serialised.

    Prefer the process-wide instance from :func:`runtime` over building your own.
    """

    def __init__(self, path: str | Path | None = None) -> None:
        self._path = Path(path) if path is not None else library_path()
        self._worker = _Worker(self._path)
        self._lib = self._worker.lib
        self._declare_signatures()

        version = self._call(self._lib.linkml_abi_version)
        if version != _EXPECTED_ABI_VERSION:
            raise LinkMlError(
                f"{self._path} speaks ABI version {version}, but this package expects "
                f"{_EXPECTED_ABI_VERSION}. Rebuild one of them."
            )

    @property
    def path(self) -> Path:
        """The library file backing this runtime."""
        return self._path

    def _call(self, function: Callable[..., Any], *args: Any) -> Any:
        return self._worker.run(lambda: function(*args))

    # Loading

    def load_file(
        self, path: str, options: Mapping[str, Any] | None = None
    ) -> tuple[int, dict[str, Any]]:
        """Load a schema from the file system.

        :return: the schema handle, 0 if the schema had fatal problems, and the validation report.
        """
        report, error = _Chars(), _Chars()
        handle = self._call(
            self._lib.linkml_load_file,
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
        entries = dict(imports or {})
        count = len(entries)
        names = (ctypes.c_char_p * count)(*(key.encode("utf-8") for key in entries))
        bodies = (ctypes.c_char_p * count)(*(value.encode("utf-8") for value in entries.values()))

        report, error = _Chars(), _Chars()
        handle = self._call(
            self._lib.linkml_load_string,
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
        self._call(self._lib.linkml_close, handle)

    # Generating

    def document(
        self, function: str, handle: int, options: Mapping[str, Any] | None = None
    ) -> str:
        """Call a generator and return its document.

        :param function: the exported name, e.g. ``linkml_shacl``.
        :raises LinkMlError: if the library reported a failure.
        """
        error = _Chars()
        result = self._call(
            getattr(self._lib, function), handle, _options(options), ctypes.byref(error)
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

    def build_info(self) -> dict[str, Any]:
        """Version and build metadata for the loaded library, parsed from its JSON.

        :raises LinkMlError: if the library reported a failure.
        """
        error = _Chars()
        result = self._call(self._lib.linkml_build_info, ctypes.byref(error))
        # Take both, so neither leaks whichever way the call went.
        message = self._take(error)
        text = self._take(result)
        if text is None:
            raise LinkMlError(message or "linkml_build_info failed without saying why")
        return json.loads(text)

    def import_document(
        self, function: str, document: str, options: Mapping[str, Any] | None = None
    ) -> str:
        """Call an importer, by its exported name, and return the schema it produced.

        Unlike :meth:`document` there is no handle: an importer is what makes schemas.

        :raises LinkMlError: if the library reported a failure.
        """
        error = _Chars()
        result = self._call(
            getattr(self._lib, function),
            document.encode("utf-8"),
            _options(options),
            ctypes.byref(error),
        )
        # Take both, so neither leaks whichever way the call went.
        message = self._take(error)
        text = self._take(result)
        if text is None:
            raise LinkMlError(message or f"{function} failed without saying why")
        return text

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
            self._call(self._lib.linkml_free, pointer)
        return None if raw is None else raw.decode("utf-8")

    def _declare_signatures(self) -> None:
        handle = ctypes.c_longlong
        chars_out = ctypes.POINTER(_Chars)
        strings = ctypes.POINTER(ctypes.c_char_p)

        self._lib.linkml_abi_version.argtypes = []
        self._lib.linkml_abi_version.restype = ctypes.c_int

        self._lib.linkml_load_file.argtypes = [
            ctypes.c_char_p,
            ctypes.c_char_p,
            chars_out,
            chars_out,
        ]
        self._lib.linkml_load_file.restype = handle

        self._lib.linkml_load_string.argtypes = [
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

        self._lib.linkml_close.argtypes = [handle]
        self._lib.linkml_close.restype = None

        # Takes no schema, so it does not fit the generator shape below.
        self._lib.linkml_build_info.argtypes = [chars_out]
        self._lib.linkml_build_info.restype = _Chars

        # Importers take a document instead of a handle, so they do not fit either.
        self._lib.linkml_from_ossie.argtypes = [ctypes.c_char_p, ctypes.c_char_p, chars_out]
        self._lib.linkml_from_ossie.restype = _Chars

        # linkml_lint has the same shape but is not a generator, so it is not in the generated
        # list and gets declared alongside it.
        for name in (*DOCUMENT_FUNCTIONS, "linkml_lint"):
            function = getattr(self._lib, name)
            function.argtypes = [handle, ctypes.c_char_p, chars_out]
            function.restype = _Chars

        self._lib.linkml_free.argtypes = [_Chars]
        self._lib.linkml_free.restype = None


_runtime: Runtime | None = None
_runtime_lock = threading.Lock()


def runtime() -> Runtime:
    """The process-wide runtime, created on first use."""
    global _runtime
    with _runtime_lock:
        if _runtime is None:
            _runtime = Runtime()
        return _runtime
