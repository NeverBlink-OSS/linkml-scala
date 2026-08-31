"""Python bindings for LinkML-Scala.

LinkML-Scala compiled to a native shared library, called through ctypes. No JVM, no Python
implementation of LinkML, no subprocess per schema.

Load a schema once, then run as many generators over it as you like::

    import linkml_scala

    with linkml_scala.load_file("model.yaml") as schema:
        print(schema.json_schema())
        print(schema.shacl())

Install it with ``pip install neverblink-linkml``. See docs/python_bindings.md in the LinkML-Scala
repository for more information, including how to build the library yourself.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping

from ._generated import Generators
from ._runtime import (
    LinkMlError,
    NativeLibraryNotFound,
    Runtime,
    library_path,
    runtime,
)
from ._version import __version__

__all__ = [
    "FATAL",
    "ERROR",
    "WARNING",
    "__version__",
    "BuildInfo",
    "LinkMlError",
    "NativeLibraryNotFound",
    "Runtime",
    "Schema",
    "SchemaLoadError",
    "build_info",
    "library_path",
    "load_file",
    "load_path",
    "load_string",
    "runtime",
]

FATAL = "FATAL"
ERROR = "ERROR"
WARNING = "WARNING"

Report = dict[str, Any]
"""A validation report, following the `validation-report.yaml` LinkML model.

``report["issues"]`` is a list of issues, each with at least an ``issue_type``, a ``severity``
(one of :data:`FATAL`, :data:`ERROR`, :data:`WARNING`) and a ``location``.
"""

BuildInfo = dict[str, Any]
"""Build metadata, following the `build-info.yaml` LinkML model.

Always has ``linkml_scala_version``, ``metamodel_version``, ``scala_version`` and ``platform``.
Here ``platform`` is always ``"NATIVE"`` and ``abi_version`` is filled in as well. See
:func:`build_info`.
"""


class SchemaLoadError(LinkMlError):
    """Fatal problems stopped a schema from loading.

    The report says what they were, in the same shape as :attr:`Schema.report`.
    """

    def __init__(self, report: Report) -> None:
        issues = report.get("issues", [])
        first = issues[0].get("message") if issues else None
        super().__init__(
            f"Could not load the schema: {first}"
            if first
            else "Could not load the schema, and the report says nothing about why"
        )
        self.report = report


class Schema(Generators):
    """A loaded, import-resolved LinkML schema.

    Parsing a schema is the expensive part, so this holds on to the parsed form inside the library
    and every generator call reuses it. Create one with :func:`load_file`, :func:`load_string` or
    :func:`load_path`.

    Release it with :meth:`close`, or use it as a context manager. Dropping the last reference also
    releases it, but only whenever the garbage collector gets round to it.
    """

    def __init__(self, runtime_: Runtime, handle: int, report: Report) -> None:
        self._runtime = runtime_
        self._handle: int | None = handle
        self._report = report

    @property
    def report(self) -> Report:
        """What linting the schema found while loading it.

        Loading only fails on fatal problems, so a schema can load and still have errors and
        warnings against it. This is exactly what :meth:`lint` returns for the same schema.
        """
        return self._report

    def issues(self, severity: str | None = None) -> list[dict[str, Any]]:
        """The issues from :attr:`report`, optionally only those of one severity.

        :param severity: :data:`FATAL`, :data:`ERROR` or :data:`WARNING`; all of them if omitted.
        """
        issues = self._report.get("issues", [])
        if severity is None:
            return issues
        return [issue for issue in issues if issue.get("severity") == severity]

    def lint(self, *, infer_messages: bool = True) -> Report:
        """Lint the schema again, and return the report.

        The same thing :attr:`report` already holds, so this is only worth calling to get a report
        with different settings.

        :param infer_messages: fill in each issue's human-readable ``message`` and ``details``. Turn
            it off for only the structured fields, which is a little faster.
        """
        return self._json("linkml_lint", inferMessages=infer_messages)

    # Lifecycle

    def close(self) -> None:
        """Release the schema. Calling it more than once is fine."""
        handle, self._handle = self._handle, None
        if handle is not None:
            self._runtime.close(handle)

    def __enter__(self) -> Schema:
        return self

    def __exit__(self, *exception: object) -> None:
        self.close()

    def __del__(self) -> None:
        try:
            self.close()
        except Exception:
            # Interpreter shutdown can pull the library out from under us. Nothing useful to do.
            pass

    def __repr__(self) -> str:
        state = "closed" if self._handle is None else f"handle={self._handle}"
        return f"<linkml_scala.Schema {state}, {len(self._report.get('issues', []))} issue(s)>"

    def _document(self, function: str, **options: Any) -> str:
        """Call a generator that returns a document, by its exported name."""
        return self._runtime.document(function, self._live(), options)

    def _json(self, function: str, **options: Any) -> Any:
        """Call a generator whose result is JSON, and return it parsed."""
        return self._runtime.json_document(function, self._live(), options)

    def _live(self) -> int:
        if self._handle is None:
            raise LinkMlError("this schema is closed")
        return self._handle


def load_file(path: str | Path, *, infer_messages: bool = True) -> Schema:
    """Load a schema from the file system, reading its ``imports`` from disk too.

    Imports resolve exactly as they do for the ``linkml-scala`` CLI: relative ones against the
    importing schema's directory, and ``linkml:`` ones against the metamodel built into the library.

    :param path: path to the schema file.
    :param infer_messages: fill in each issue's human-readable ``message`` and ``details``.
    :raises SchemaLoadError: if fatal problems stopped the schema from loading.
    """
    current = runtime()
    return _schema(current, *current.load_file(str(path), {"inferMessages": infer_messages}))


def load_string(
    schema: str,
    imports: Mapping[str, str] | None = None,
    *,
    infer_messages: bool = True,
) -> Schema:
    """Load a schema from YAML text, resolving ``imports`` against an in-memory map.

    The schema itself has no path, so an import that (transitively) imports the root back by
    filename cannot be matched to it and loads a second copy. Use :func:`load_path` when the root
    takes part in an import cycle.

    :param schema: the schema, as YAML.
    :param imports: filename to YAML text, covering everything the schema imports.
    :param infer_messages: fill in each issue's human-readable ``message`` and ``details``.
    :raises SchemaLoadError: if fatal problems stopped the schema from loading.
    """
    current = runtime()
    return _schema(
        current,
        *current.load_string(None, schema, imports, {"inferMessages": infer_messages}),
    )


def load_path(
    path: str,
    imports: Mapping[str, str],
    *,
    infer_messages: bool = True,
) -> Schema:
    """Load a schema from an in-memory map, by its path within that map.

    Unlike :func:`load_string`, the root schema is read through the map under its own path, so it is
    tracked from the start of import resolution and cyclic imports involving the root resolve back
    to it.

    Keys behave like file paths: a missing ``.yaml`` extension is added, and relative imports resolve
    against the directory of the schema that imported them. So keys are paths as seen from the root,
    such as ``"model.yaml"`` or ``"nested/person.yaml"``.

    :param path: the root schema's key in ``imports``.
    :param imports: path to YAML text, including the root schema itself.
    :param infer_messages: fill in each issue's human-readable ``message`` and ``details``.
    :raises SchemaLoadError: if fatal problems stopped the schema from loading.
    """
    current = runtime()
    return _schema(
        current,
        *current.load_string(path, None, imports, {"inferMessages": infer_messages}),
    )


def build_info() -> BuildInfo:
    """Version and build metadata for the native library these bindings loaded.

    Note that ``__version__`` is the version of this Python package, while
    ``build_info()["linkml_scala_version"]`` is the version of the library it is talking to. They
    match in a released wheel, but not when you point ``$LINKML_SCALA_LIB`` at a library you built
    yourself. Worth quoting both in a bug report.
    """
    return runtime().build_info()


def _schema(current: Runtime, handle: int, report: Report) -> Schema:
    """Wrap a load result, turning a refused load into an exception.

    The library returns handle 0 when fatal problems stopped the schema loading, and a report either
    way, so the report is what explains the failure.
    """
    if handle == 0:
        raise SchemaLoadError(report)
    return Schema(current, handle, report)
