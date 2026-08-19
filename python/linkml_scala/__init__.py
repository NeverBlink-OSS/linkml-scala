"""Python bindings for LinkML-Scala.

LinkML-Scala compiled to a native shared library, called through ctypes. No JVM, no Python
implementation of LinkML, no subprocess per schema.

Load a schema once, then run as many generators over it as you like::

    import linkml_scala

    with linkml_scala.load_file("model.yaml") as schema:
        print(schema.json_schema())
        print(schema.shacl())

See docs/python_bindings.md in the LinkML-Scala repository for the whole story, including how to
build the library.
"""

from __future__ import annotations

from pathlib import Path
from typing import Any, Mapping

from ._runtime import (
    LinkMlError,
    NativeLibraryNotFound,
    Runtime,
    library_path,
    runtime,
)

__all__ = [
    "FATAL",
    "ERROR",
    "WARNING",
    "LinkMlError",
    "NativeLibraryNotFound",
    "Runtime",
    "Schema",
    "SchemaLoadError",
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

``report["issues"]`` is a list of issues, each with at least a ``type``, a ``severity`` (one of
:data:`FATAL`, :data:`ERROR`, :data:`WARNING`) and a ``location``.
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


class Schema:
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
        return self._call("lint", inferMessages=infer_messages)["report"]

    # Generators

    def json_schema(
        self,
        *,
        open: bool = False,
        tree_root: str | None = None,
        tree_root_inline_type: str | None = None,
    ) -> str:
        """Generate a JSON Schema.

        :param open: allow ``additionalProperties`` on generated classes.
        :param tree_root: use this class as the root instead of the schema's ``tree_root``.
        :param tree_root_inline_type: override the root class' ``tree_root_as`` extension. One of
            ``plain``, ``optional``, ``list``, ``compact_dict``, ``simple_dict``.
        """
        return self._generate(
            "json-schema",
            open=open,
            treeRoot=tree_root,
            treeRootInlineType=tree_root_inline_type,
        )

    def shacl(self, *, open: bool = False, only_classes_from_root_schema: bool = False) -> str:
        """Generate SHACL shapes, serialized as N-Triples.

        :param open: emit open shapes, which allow properties the schema does not mention.
        :param only_classes_from_root_schema: leave out classes that came in through ``imports``.
            Useful when generating shapes per schema file.
        """
        return self._generate(
            "shacl",
            open=open,
            onlyClassesFromRootSchema=only_classes_from_root_schema,
        )

    def rdfs(self, *, only_classes_from_root_schema: bool = False) -> str:
        """Generate RDFS, serialized as N-Triples.

        :param only_classes_from_root_schema: leave out classes that came in through ``imports``.
        """
        return self._generate("rdfs", onlyClassesFromRootSchema=only_classes_from_root_schema)

    def linkml(
        self,
        *,
        skip_derivation: bool = False,
        pruning_mode: str = "skip",
        tree_root: str | None = None,
        format: str = "yaml",
    ) -> str:
        """Materialize a derived LinkML schema: imports resolved, slots pushed into attributes.

        :param skip_derivation: copy classes as they are instead of deriving them.
        :param pruning_mode: which unused elements to drop. ``treeRoot`` removes everything
            unreachable from the tree root class, ``schema`` everything unreachable from any class in
            the root schema, ``skip`` nothing.
        :param tree_root: tree root class to prune from, instead of the schema's own.
        :param format: ``yaml`` or ``json``.
        """
        return self._generate(
            "linkml",
            skipDerivation=skip_derivation,
            pruningMode=pruning_mode,
            treeRoot=tree_root,
            format=format,
        )

    def table_schema(self, *, tree_root: str | None = None) -> str:
        """Generate a Frictionless Table Schema, serialized as JSON.

        :param tree_root: table root class, instead of the schema's ``tree_root``.
        """
        return self._generate("table-schema", treeRoot=tree_root)

    def graphql(self, *, pruning_mode: str = "skip", tree_root: str | None = None) -> str:
        """Generate a GraphQL schema: types, interfaces, scalars and enums, but no queries.

        :param pruning_mode: see :meth:`linkml`.
        :param tree_root: see :meth:`linkml`.
        """
        return self._generate("graphql", pruningMode=pruning_mode, treeRoot=tree_root)

    def er_diagram(
        self,
        *,
        pruning_mode: str = "skip",
        tree_root: str | None = None,
        optional_marker: bool = True,
    ) -> str:
        """Generate a Mermaid entity relationship diagram.

        :param pruning_mode: see :meth:`linkml`.
        :param tree_root: see :meth:`linkml`.
        :param optional_marker: mark optional attributes with a trailing ``?``. Mermaid understands
            this from 11.16 on; older renderers reject the whole diagram, so pass ``False`` if the
            diagram is headed somewhere that pins an older version.
        """
        return self._generate(
            "er-diagram",
            pruningMode=pruning_mode,
            treeRoot=tree_root,
            optionalMarker=optional_marker,
        )

    def scala(
        self,
        package: str = "eu.neverblink.linkml.metamodel",
        *,
        generate_emit_prefixes: bool = True,
    ) -> dict[str, str]:
        """Generate Scala classes, as a filename to source mapping.

        :param package: package to put the generated classes in.
        :param generate_emit_prefixes: also generate a ``Prefixes`` object holding the schema's
            ``emit_prefixes``.
        """
        return self._call(
            "generate",
            generator="scala",
            options={"package": package, "generateEmitPrefixes": generate_emit_prefixes},
        )["files"]

    # Lifecycle

    def close(self) -> None:
        """Release the schema. Calling it more than once is fine."""
        handle, self._handle = self._handle, None
        if handle is not None:
            self._runtime.call({"op": "close", "handle": handle})

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

    def _generate(self, generator: str, **options: Any) -> str:
        return self._call(
            "generate",
            generator=generator,
            # Leave unset options out entirely, so the library applies its own defaults.
            options={key: value for key, value in options.items() if value is not None},
        )["output"]

    def _call(self, op: str, **fields: Any) -> dict[str, Any]:
        if self._handle is None:
            raise LinkMlError("this schema is closed")
        return self._runtime.call({"op": op, "handle": self._handle, **fields})


def load_file(path: str | Path, *, infer_messages: bool = True) -> Schema:
    """Load a schema from the file system, reading its ``imports`` from disk too.

    Imports resolve exactly as they do for the ``linkml-scala`` CLI: relative ones against the
    importing schema's directory, and ``linkml:`` ones against the metamodel built into the library.

    :param path: path to the schema file.
    :param infer_messages: fill in each issue's human-readable ``message`` and ``details``.
    :raises SchemaLoadError: if fatal problems stopped the schema from loading.
    """
    return _load({"path": str(path)}, infer_messages)


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
    return _load({"schema": schema, "imports": dict(imports or {})}, infer_messages)


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
    return _load({"path": path, "imports": dict(imports)}, infer_messages)


def _load(request: dict[str, Any], infer_messages: bool) -> Schema:
    current = runtime()
    response = current.call({"op": "load", "inferMessages": infer_messages, **request})
    report = response["report"]
    if "handle" not in response:
        raise SchemaLoadError(report)
    return Schema(current, response["handle"], report)
