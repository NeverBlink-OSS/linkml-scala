"""Loading the shared library, and talking to it.

Everything ctypes-shaped lives here. The public API in ``__init__`` only deals in dicts.
"""

from __future__ import annotations

import ctypes
import json
import os
import sys
import threading
from pathlib import Path
from typing import Any

__all__ = ["Runtime", "LinkMlError", "NativeLibraryNotFound", "library_path", "runtime"]

# Bumped in lockstep with LinkMlNativeApi.abiVersion on the Scala side.
_EXPECTED_ABI_VERSION = 1

_LIB_STEM = "liblinkml_scala"


class LinkMlError(Exception):
    """The library rejected a request, or failed while handling it."""


class NativeLibraryNotFound(LinkMlError):
    """The shared library could not be found on this machine."""


def _library_filename() -> str:
    if sys.platform == "darwin":
        return f"{_LIB_STEM}.dylib"
    if sys.platform == "win32":
        # native-image keeps the lib prefix on Windows too.
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

    # A source checkout that has run `./mill nativelib.sharedLibrary` but not installed it.
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


class Runtime:
    """One GraalVM isolate, and the calls into it.

    An isolate is a self-contained heap: the library can hold several, but one is all we need, and
    schema handles are scoped to it. Threads have to attach to an isolate before calling in, which
    happens on demand and is remembered per thread.

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

        version = self.call({"op": "version"})["abiVersion"]
        if version != _EXPECTED_ABI_VERSION:
            raise LinkMlError(
                f"{self._path} speaks ABI version {version}, but this package expects "
                f"{_EXPECTED_ABI_VERSION}. Rebuild one of them."
            )

    @property
    def path(self) -> Path:
        """The library file backing this runtime."""
        return self._path

    def call(self, request: dict[str, Any]) -> dict[str, Any]:
        """Send one request and return its response, minus the ``ok`` flag.

        :raises LinkMlError: if the library reports a failure.
        """
        payload = json.dumps(request).encode("utf-8")
        pointer = self._lib.linkml_call(self._thread(), payload)
        if not pointer:
            raise LinkMlError("linkml_call returned NULL")
        try:
            raw = ctypes.cast(pointer, ctypes.c_char_p).value
        finally:
            self._lib.linkml_free(self._thread(), pointer)

        response = json.loads(raw.decode("utf-8"))
        if not response.pop("ok", False):
            raise LinkMlError(response.get("error", "the native library reported no reason"))
        return response

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
        # Returned as POINTER(c_char) rather than c_char_p: ctypes would turn the latter into bytes
        # and throw the pointer away, leaving us nothing to hand to linkml_free.
        char_p = ctypes.POINTER(ctypes.c_char)

        self._lib.graal_create_isolate.argtypes = [void_p, ctypes.POINTER(void_p), ctypes.POINTER(void_p)]
        self._lib.graal_create_isolate.restype = ctypes.c_int

        self._lib.graal_attach_thread.argtypes = [void_p, ctypes.POINTER(void_p)]
        self._lib.graal_attach_thread.restype = ctypes.c_int

        self._lib.linkml_call.argtypes = [void_p, ctypes.c_char_p]
        self._lib.linkml_call.restype = char_p

        self._lib.linkml_free.argtypes = [void_p, char_p]
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
