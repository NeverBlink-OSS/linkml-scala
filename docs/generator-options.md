# Generator options

<!-- Generated from model/generator-options.yaml. Regenerate with ./mill --no-server optiongen.regenerate. -->

Each generator retains its nested Scala `Options` case class. Native JSON uses the same field
names and defaults. Omit a JSON field to use its core default. Python arguments are keyword-only.
JavaScript arguments are positional. CLI flags appear below without common input/output flags.

Each exposed parameter shows its interface default. **Bold defaults differ from the core
constructor.** `required` means the caller must supply the argument. `Unexposed` means that
interface uses the core default without exposing a parameter.

## Pruning and formats

 Schema mode | Native JSON value | Meaning |
 --- | --- | --- |
 ` tree_root ` | ` "treeRoot" ` | remove all elements unreachable from the tree_root class. |
 ` schema ` | ` "schema" ` | remove all elements unreachable from any of the classes defined in the root schema. |
 ` skip ` | ` "skip" ` | do not remove unused elements. |

The authoring schema represents pruning as a mode plus an optional root name, for example
`{mode: tree_root, treeRoot: Person}`. Native options use a string mode or a single-key object.
To override the root, send ` {"pruningMode": {"treeRoot": "Person"}} `. This JSON shape also applies to Python's native calls.

Pruning accepts `treeRoot`, `tree_root`, and `tree-root`. Python permits a `tree_root` override
only with those spellings and rejects an override for other modes. CLI and JavaScript ignore a
root override outside tree-root pruning. Optional root strings are passed without trimming.

JSON/YAML formats accept `yaml`, `yml`, and `json`, ignoring case. RDF accepts `ttl`, `turtle`,
`nt`, and `ntriples`. CLI and JavaScript ignore RDF case. Native JSON and Python require lowercase RDF names.

## JsonSchemaGenerator

Options for generating JSON Schema. Returns a string. Native entry point ` linkml_json_schema `.

JavaScript positional signature ` LinkML.jsonSchema(schema, open = false, treeRootOverride = undefined) `.

Python keyword signature ` schema.json_schema(*, open=False, tree_root=None, tree_root_inline_type=None, indentation_step=2, metadata_language="en", include_null=False) `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` open `<br>Whether the generated JSON Schema should allow `additionalProperties` for classes. | ` Boolean ` | ` false ` | ` --open ` = ` false ` | ` open ` = ` false ` | ` open ` = ` False ` |
 ` treeRoot `<br>If defined, override the schema `tree_root` class with the one provided. | ` Option[String] ` | ` None ` | ` --tree-root-override ` = ` not set ` | ` treeRootOverride ` = ` undefined ` | ` tree_root ` = ` None ` |
 ` treeRootInlineType `<br>If defined, override the `tree_root_as` extension of the tree root class with the one provided. One of `plain`, `optional`, `list`, `compact_dict`, `simple_dict`. | ` Option[String] ` | ` None ` | ` --tree-root-inline-type-override ` = ` not set ` | Unexposed | ` tree_root_inline_type ` = ` None ` |
 ` indentationStep `<br>Number of spaces in pretty print indentation of the serialized JSON Schema. | ` Int ` | ` 2 ` | Unexposed | Unexposed | ` indentation_step ` = ` 2 ` |
 ` metadataLanguage `<br>Which language to use for metadata fields (description, title) in the generated JSON Schema. | ` String ` | ` "en" ` | Unexposed | Unexposed | ` metadata_language ` = ` "en" ` |
 ` includeNull `<br>Allows null values for optional slots and compact dictionary entries without required content beyond the key. | ` Boolean ` | ` false ` | ` --include-null ` = ` false ` | Unexposed | ` include_null ` = ` False ` |

## ShaclGenerator

Options for generating SHACL shapes. Returns a string. Native entry point ` linkml_shacl `.

JavaScript positional signature ` LinkML.shacl(schema, open = false, onlyClassesFromRootSchema = false, format = "ttl") `.

Python keyword signature ` schema.shacl(*, open=False, only_classes_from_root_schema=False, format="ttl") `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` open `<br>Whether the generated shapes should be open, allowing properties the schema does not mention. | ` Boolean ` | ` false ` | ` --open ` = ` false ` | ` open ` = ` false ` | ` open ` = ` False ` |
 ` onlyClassesFromRootSchema `<br>Whether to include only classes from the root schema. This is useful if you intend to generate SHACL shapes for each schema file separately, and you don't need the imported classes to be included in the generated SHACL shapes. | ` Boolean ` | ` false ` | ` --only-classes-from-root-schema ` = ` false ` | ` onlyClassesFromRootSchema ` = ` false ` | ` only_classes_from_root_schema ` = ` False ` |
 ` format `<br>Which RDF serialization to write: `ttl` for Turtle, which is prefixed and pretty-printed, or `nt` for N-Triples. | ` RdfFormat ` | ` RdfFormat.ttl ` | ` --format ` = ` "ttl" ` | ` format ` = ` "ttl" ` | ` format ` = ` "ttl" ` |

## RdfsGenerator

Options for generating RDF schema. Returns a string. Native entry point ` linkml_rdfs `.

JavaScript positional signature ` LinkML.rdfs(schema, onlyClassesFromRootSchema = false, format = "ttl") `.

Python keyword signature ` schema.rdfs(*, only_classes_from_root_schema=False, format="ttl") `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` onlyClassesFromRootSchema `<br>Whether to include only classes and enums from the root schema. This is useful if you intend to generate RDFS for each schema file separately, and you don't need the imported classes to be included. | ` Boolean ` | ` false ` | ` --only-classes-from-root-schema ` = ` false ` | ` onlyClassesFromRootSchema ` = ` false ` | ` only_classes_from_root_schema ` = ` False ` |
 ` format `<br>Which RDF serialization to write: `ttl` for Turtle, which is prefixed and pretty-printed, or `nt` for N-Triples. | ` RdfFormat ` | ` RdfFormat.ttl ` | ` --format ` = ` "ttl" ` | ` format ` = ` "ttl" ` | ` format ` = ` "ttl" ` |

## LinkMlGenerator

Options for materializing a derived LinkML schema. Derives classes and can prune unreachable elements. Returns a string. Native entry point ` linkml_linkml `.

JavaScript positional signature ` LinkML.linkml(schema, pruningMode = "treeRoot", skipDerivation = false, treeRoot = undefined, outFormat = "yaml") `.

Python keyword signature ` schema.linkml(*, pruning_mode="skip", tree_root=None, skip_class_derivation=False, output_format="yaml") `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` pruningMode `<br>Method to use for schema definition pruning. | ` PruningMode ` | ` PruningMode.skip ` | ` --pruning-mode ` = ` "skip" `<br>` --tree-root ` = ` not set ` | ` pruningMode ` = **` "treeRoot" `**<br>` treeRoot ` = ` undefined ` | ` pruning_mode ` = ` "skip" `<br>` tree_root ` = ` None ` |
 ` skipClassDerivation `<br>If true, will not derive classes and instead copy them as-is. | ` Boolean ` | ` false ` | ` --skip-derivation ` = ` false ` | ` skipDerivation ` = ` false ` | ` skip_class_derivation ` = ` False ` |
 ` outputFormat `<br>Output serialization format to use. | ` JsonOutputFormat ` | ` JsonOutputFormat.yaml ` | ` --format ` = ` "yaml" ` | ` outFormat ` = ` "yaml" ` | ` output_format ` = ` "yaml" ` |

## FrictionlessGenerator

Options for generating a Frictionless Data Package. Each selected class becomes a CSV table, described by its own Table Schema, and references between classes can become foreign keys between the tables. Returns a filename-to-content mapping. Native entry point ` linkml_frictionless `.

JavaScript positional signature ` LinkML.frictionless(schema, pruningMode = "skip", treeRoot = undefined, skipClassesWithoutIdentifier = false) `.

Python keyword signature ` schema.frictionless(*, pruning_mode="skip", tree_root=None, skip_classes_without_identifier=False, metadata_language="en") `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` pruningMode `<br>Which classes to turn into tables. | ` PruningMode ` | ` PruningMode.skip ` | ` --pruning-mode ` = ` "skip" `<br>` --tree-root ` = ` not set ` | ` pruningMode ` = ` "skip" `<br>` treeRoot ` = ` undefined ` | ` pruning_mode ` = ` "skip" `<br>` tree_root ` = ` None ` |
 ` skipClassesWithoutIdentifier `<br>Whether to skip classes that have no identifier slot. Such a table gets no primary key and nothing can reference it, so it is often not useful. | ` Boolean ` | ` false ` | ` --skip-classes-without-identifier ` = ` false ` | ` skipClassesWithoutIdentifier ` = ` false ` | ` skip_classes_without_identifier ` = ` False ` |
 ` metadataLanguage `<br>Which language to use for metadata fields (description) in the generated Table Schema. | ` String ` | ` "en" ` | Unexposed | Unexposed | ` metadata_language ` = ` "en" ` |

## GraphQlGenerator

Options for generating a GraphQL schema. Only types/interfaces/scalar/enums, queries must be provided for a specific implementation. Returns a string. Native entry point ` linkml_graphql `.

JavaScript positional signature ` LinkML.graphQl(schema, pruningMode = "treeRoot", treeRoot = undefined) `.

Python keyword signature ` schema.graphql(*, pruning_mode="schema", tree_root=None, metadata_language="en") `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` pruningMode `<br>How to prune the generated definitions. Schema mode retains elements reachable from classes defined in the root schema to omit unnecessary linkml:types scalar definitions. | ` PruningMode ` | ` PruningMode.schemaRoot ` | ` --pruning-mode ` = **` "skip" `**<br>` --tree-root ` = ` not set ` | ` pruningMode ` = **` "treeRoot" `**<br>` treeRoot ` = ` undefined ` | ` pruning_mode ` = ` "schema" `<br>` tree_root ` = ` None ` |
 ` metadataLanguage `<br>Which language to use for metadata fields (description etc.) in the output GraphQL. | ` String ` | ` "en" ` | Unexposed | Unexposed | ` metadata_language ` = ` "en" ` |

## ErDiagramGenerator

Options for generating Mermaid entity relationship diagrams. Classes become entities, type- and enum-ranged slots become their attributes, and class-ranged slots become relationship lines. Returns a string. Native entry point ` linkml_er_diagram `.

JavaScript positional signature ` LinkML.erDiagram(schema, pruningMode = "treeRoot", treeRoot = undefined, optionalMarker = true) `.

Python keyword signature ` schema.er_diagram(*, pruning_mode="schema", tree_root=None, optional_marker=True) `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` pruningMode `<br>How to prune the generated entities. Schema mode retains classes reachable from classes defined in the root schema. | ` PruningMode ` | ` PruningMode.schemaRoot ` | ` --pruning-mode ` = **` "skip" `**<br>` --tree-root ` = ` not set ` | ` pruningMode ` = **` "treeRoot" `**<br>` treeRoot ` = ` undefined ` | ` pruning_mode ` = ` "schema" `<br>` tree_root ` = ` None ` |
 ` optionalMarker `<br>Whether to mark optional attributes with a trailing `?` on their type, which requires Mermaid 11.16 or newer. | ` Boolean ` | ` true ` | ` --optional-marker ` = ` true ` | ` optionalMarker ` = ` true ` | ` optional_marker ` = ` True ` |

## OssieGenerator

Options for generating an Apache Ossie ontology. Classes become entity types, enums and named types become value types, and slots become the relationships grouped under the concept that plays their first role. Returns a string. Native entry point ` linkml_ossie `.

JavaScript positional signature ` LinkML.ossie(schema, pruningMode = "skip", treeRoot = undefined, outFormat = "yaml") `.

Python keyword signature ` schema.ossie(*, pruning_mode="skip", tree_root=None, output_format="yaml", metadata_language="en") `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` pruningMode `<br>Which elements become concepts. | ` PruningMode ` | ` PruningMode.skip ` | ` --pruning-mode ` = ` "skip" `<br>` --tree-root ` = ` not set ` | ` pruningMode ` = ` "skip" `<br>` treeRoot ` = ` undefined ` | ` pruning_mode ` = ` "skip" `<br>` tree_root ` = ` None ` |
 ` outputFormat `<br>Output serialization format to use. | ` JsonOutputFormat ` | ` JsonOutputFormat.yaml ` | ` --format ` = ` "yaml" ` | ` outFormat ` = ` "yaml" ` | ` output_format ` = ` "yaml" ` |
 ` metadataLanguage `<br>Which language to use for metadata fields (description) in the generated ontology. | ` String ` | ` "en" ` | Unexposed | Unexposed | ` metadata_language ` = ` "en" ` |

## ScalaGenerator

Options for generating Scala classes. This is primarily used for the metamodel. Returns a filename-to-content mapping. Native entry point ` linkml_scala `.

JavaScript positional signature ` LinkML.scala(schema, packageName) `.

Python keyword signature ` schema.scala(*, package="eu.neverblink.linkml.metamodel", generate_emit_prefixes=True, metadata_language="en") `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` package `<br>Scala package to generate the classes in. | ` String ` | ` "eu.neverblink.linkml.metamodel" ` | ` --package ` = ` "eu.neverblink.linkml.metamodel" ` | ` packageName ` = **` required `** | ` package ` = ` "eu.neverblink.linkml.metamodel" ` |
 ` generateEmitPrefixes `<br>Whether to generate a `Prefixes` object holding the model's `emit_prefixes`. | ` Boolean ` | ` true ` | ` --generate-emit-prefixes ` = ` true ` | Unexposed | ` generate_emit_prefixes ` = ` True ` |
 ` metadataLanguage `<br>Which language to use for metadata fields (description etc.) in ScalaDocs. | ` String ` | ` "en" ` | Unexposed | Unexposed | ` metadata_language ` = ` "en" ` |

## TranslationGenerator

Options for generating JSON dictionaries that translate the LinkML name to specific frameworks. This is useful when the framework symbols are significant and must be known, like when constructing a query that is meant to be executed against a database conformant to a LinkML schema. Returns a string. Native entry point ` linkml_translation `.

JavaScript positional signature ` LinkML.translation(schema, target) `.

Python keyword signature ` schema.translation(*, to="base", indentation_step=2) `.

 Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
 --- | --- | --- | --- | --- | --- |
 ` to `<br>Framework whose names appear in the translation dictionary. | ` String ` | ` "base" ` | ` --target ` = ` "base" ` | ` target ` = **` required `** | ` to ` = ` "base" ` |
 ` indentationStep `<br>Number of spaces per JSON indentation level. | ` Int ` | ` 2 ` | Unexposed | Unexposed | ` indentation_step ` = ` 2 ` |
