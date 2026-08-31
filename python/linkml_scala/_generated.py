# AUTO-GENERATED from mill-build/src/Entrypoints.scala and the generators' Options case
# classes. Do not edit by hand - regenerate with ./mill bindings.
"""The generator methods of :class:`linkml_scala.Schema`.

Each one mirrors an ``Options`` case class in the Scala sources, so the keyword arguments,
their defaults and their documentation are whatever the generator itself declares.
"""

from __future__ import annotations

from typing import Any


def _pruning(mode: str, tree_root: str | None) -> Any:
    """Encode the pruning mode the way the generators read it back.

    `PruningMode` carries the tree-root override inside its `treeRoot` case rather than
    beside it, so naming a root is an object instead of a second field.
    """
    if tree_root is None:
        return mode
    if mode not in ("treeRoot", "tree_root", "tree-root"):
        raise ValueError(f"tree_root only applies to pruning_mode='treeRoot', not {mode!r}")
    return {"treeRoot": tree_root}


# Every exported function taking (handle, options) and returning a document or NULL.
DOCUMENT_FUNCTIONS = (
    "linkml_json_schema",
    "linkml_shacl",
    "linkml_rdfs",
    "linkml_linkml",
    "linkml_frictionless",
    "linkml_graphql",
    "linkml_er_diagram",
    "linkml_scala",
)


class Generators:
    """Mixin holding one method per generator. Mixed into :class:`linkml_scala.Schema`."""

    def json_schema(
        self,
        *,
        open: bool = False,
        tree_root: str | None = None,
        tree_root_inline_type: str | None = None,
        indentation_step: int = 2,
        metadata_language: str = "en",
    ) -> str:
        """Generate a JSON Schema.

        :param open: Whether the generated JSON Schema should allow `additionalProperties` for
            classes.
        :param tree_root: If defined, override the schema `tree_root` class with the one
            provided.
        :param tree_root_inline_type: If defined, override the `tree_root_as` extension of the
            tree root class with the one provided. One of `plain`, `optional`, `list`,
            `compact_dict`, `simple_dict`.
        :param indentation_step: Number of spaces in pretty print indentation of the serialized
            JSON Schema.
        :param metadata_language: Which language to use for metadata fields (description, title)
            in the generated JSON Schema.
        """
        return self._document(
            "linkml_json_schema",
            open=open,
            treeRoot=tree_root,
            treeRootInlineType=tree_root_inline_type,
            indentationStep=indentation_step,
            metadataLanguage=metadata_language,
        )

    def shacl(
        self,
        *,
        open: bool = False,
        only_classes_from_root_schema: bool = False,
        format: str = "ttl",
    ) -> str:
        """Generate SHACL shapes, serialized as N-Triples or Turtle.

        :param open: Whether the generated shapes should be open, allowing properties the schema
            does not mention (turned off by default).
        :param only_classes_from_root_schema: Whether to include only classes from the root
            schema (turned off by default). This is useful if you intend to generate SHACL
            shapes for each schema file separately, and you don't need the imported classes to
            be included in the generated SHACL shapes.
        :param format: Which RDF serialization to write: `ttl` for Turtle (the default), which
            is prefixed and pretty-printed, or `nt` for N-Triples.
        """
        return self._document(
            "linkml_shacl",
            open=open,
            onlyClassesFromRootSchema=only_classes_from_root_schema,
            format=format,
        )

    def rdfs(
        self,
        *,
        only_classes_from_root_schema: bool = False,
        format: str = "ttl",
    ) -> str:
        """Generate RDFS, serialized as N-Triples or Turtle.

        :param only_classes_from_root_schema: Whether to include only classes and enums from the
            root schema (turned off by default). This is useful if you intend to generate RDFS
            for each schema file separately, and you don't need the imported classes to be
            included.
        :param format: Which RDF serialization to write: `ttl` for Turtle (the default), which
            is prefixed and pretty-printed, or `nt` for N-Triples.
        """
        return self._document(
            "linkml_rdfs",
            onlyClassesFromRootSchema=only_classes_from_root_schema,
            format=format,
        )

    def linkml(
        self,
        *,
        pruning_mode: str = "skip",
        tree_root: str | None = None,
        skip_class_derivation: bool = False,
        output_format: str = "yaml",
    ) -> str:
        """Materialize a derived LinkML schema: imports resolved, slots pushed into attributes.

        :param pruning_mode: Method to use for schema definition pruning.
        :param tree_root: prune from this class instead of the schema's own `tree_root`. Only
            valid with `pruning_mode="treeRoot"`.
        :param skip_class_derivation: If true, will not derive classes and instead copy them
            as-is.
        :param output_format: Output serialization format to use.
        """
        return self._document(
            "linkml_linkml",
            pruningMode=_pruning(pruning_mode, tree_root),
            skipClassDerivation=skip_class_derivation,
            outputFormat=output_format,
        )

    def frictionless(
        self,
        *,
        pruning_mode: str = "skip",
        tree_root: str | None = None,
        skip_classes_without_identifier: bool = False,
        metadata_language: str = ""en"",
    ) -> dict[str, str]:
        """Generate a Frictionless Data Package, as a filename to content mapping.

        :param pruning_mode: Which classes to turn into tables.
        :param tree_root: prune from this class instead of the schema's own `tree_root`. Only
            valid with `pruning_mode="treeRoot"`.
        :param skip_classes_without_identifier: Whether to skip classes that have no identifier
            slot. Such a table gets no primary key and nothing can reference it, so it is often
            not useful.
        :param metadata_language: Which language to use for metadata fields (description) in the
            generated Table Schema.
        """
        return self._json(
            "linkml_frictionless",
            pruningMode=_pruning(pruning_mode, tree_root),
            skipClassesWithoutIdentifier=skip_classes_without_identifier,
            metadataLanguage=metadata_language,
        )

    def graphql(
        self,
        *,
        pruning_mode: str = "schema",
        tree_root: str | None = None,
        metadata_language: str = "en",
    ) -> str:
        """Generate a GraphQL schema: types, interfaces, scalars and enums, but no queries.

        :param pruning_mode: How to prune the generated definitions, schemaRoot by default
            (elements reachable from any root schema defined elements) to omit unnecessary
            linkml:types scalar definitions.
        :param tree_root: prune from this class instead of the schema's own `tree_root`. Only
            valid with `pruning_mode="treeRoot"`.
        :param metadata_language: Which language to use for metadata fields (description etc.)
            in the output GraphQL.
        """
        return self._document(
            "linkml_graphql",
            pruningMode=_pruning(pruning_mode, tree_root),
            metadataLanguage=metadata_language,
        )

    def er_diagram(
        self,
        *,
        pruning_mode: str = "schema",
        tree_root: str | None = None,
        optional_marker: bool = True,
    ) -> str:
        """Generate a Mermaid entity relationship diagram.

        :param pruning_mode: How to prune the generated entities, schemaRoot by default (classes
            reachable from any element defined in the root schema).
        :param tree_root: prune from this class instead of the schema's own `tree_root`. Only
            valid with `pruning_mode="treeRoot"`.
        :param optional_marker: Whether to mark optional attributes with a trailing `?` on their
            type, which requires Mermaid 11.16 or newer.
        """
        return self._document(
            "linkml_er_diagram",
            pruningMode=_pruning(pruning_mode, tree_root),
            optionalMarker=optional_marker,
        )

    def scala(
        self,
        *,
        package: str = "eu.neverblink.linkml.metamodel",
        generate_emit_prefixes: bool = True,
        metadata_language: str = "en",
    ) -> dict[str, str]:
        """Generate Scala classes, as a filename to source mapping.

        :param package: Scala package to generate the classes in.
        :param generate_emit_prefixes: Whether to generate a `Prefixes` object holding the
            model's `emit_prefixes`.
        :param metadata_language: Which language to use for metadata fields (description etc.)
            in ScalaDocs.
        """
        return self._json(
            "linkml_scala",
            package=package,
            generateEmitPrefixes=generate_emit_prefixes,
            metadataLanguage=metadata_language,
        )
