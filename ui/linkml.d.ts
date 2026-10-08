// AUTO-GENERATED from generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala.
// Do not edit by hand – regenerate with ./mill uiTypes (or generator.js.npmPackage).

// The validation report, generated from model/issue-types.yaml. Each issue's `issue_type`
// names its kind, so a `switch` on it narrows the issue to that kind.
import type { SchemaValidationReport } from "../generator/npm/validation-report.js";
export * from "../generator/npm/validation-report.js";

// The build info, generated from model/build-info.yaml.
import type { BuildInfo } from "../generator/npm/build-info.js";
export * from "../generator/npm/build-info.js";

/**
 * Opaque handle to a loaded, import-resolved LinkML schema. Create one with
 * {@link LinkMLApi.load} and pass it to the generator functions. Parse a schema
 * once and reuse the handle, instead of re-parsing the YAML on every call.
 */
export interface SchemaView {
  /** @internal Nominal brand – do not access. */
  readonly __linkmlSchemaView: unique symbol;
}

/**
 * What loading a schema produced. There is always a report - loading is validating - and a
 * `view` unless the schema had fatal problems.
 */
export interface LoadResult {
  readonly view?: SchemaView;
  readonly report: SchemaValidationReport;
}

export interface LinkMLApi {
  /**
   * Version and build metadata of this copy of LinkML-Scala: which version it is, which LinkML metamodel it was built against, and what it is running on.  Useful in bug reports, and for checking that the version you loaded is the one you meant to.
   * @returns A `BuildInfo` object, as described by https://linkml.neverblink.eu/model/build-info
   */
  buildInfo(): BuildInfo;

  /**
   * Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from the schema's YAML text.  The main schema is parsed directly from `mainSchema`, so it has no path of its own. If one of its imports (transitively) imports the main schema back by filename, that import cannot be matched against the root and the main schema will be loaded a second time. Use [[loadFromPath]] instead when the root schema takes part in an import cycle.  See [[loadFromPath]] for the correct key format.
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details`.
   * @returns The validation report, and a handle to pass to the generator functions unless the schema had fatal problems.
   */
  loadFromString(mainSchema: string, importMap: Record<string, string>, inferMessages?: boolean): LoadResult;

  /**
   * Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from a path into the [[importMap]].  Unlike [[loadFromString]], the main schema is read through the import map by its own path, so it is tracked from the start of import resolution. This makes it immune to cyclic imports involving the root schema: an import that (transitively) references the root back by path resolves to the already-loaded root instead of loading it again.  Keys in the [[importMap]] are import paths as seen from the root schema. The `.yaml` and `.yml` extensions are optional and interchangeable, and each imported schema is loaded only once, even when it is reached through different relative paths.
   * @param path Path of the main LinkML model within the [[importMap]] (e.g. `"model.yaml"`).
   * @param importMap JS dictionary (object) containing a mapping from path to LinkML models (in YAML format), including the main schema itself under [[path]].
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details`.
   * @returns The validation report, and a handle to pass to the generator functions unless the schema had fatal problems.
   */
  loadFromPath(path: string, importMap: Record<string, string>, inferMessages?: boolean): LoadResult;

  /**
   * Generate JSON Schema from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param open Whether the JSON Schema should allow `additionalProperties` for all classes, not only for those whose `extra_slots` allows them.
   * @param treeRootOverride Override for the LinkML `tree_root` class which will be at the root of the JSON Schema.
   * @returns Serialized JSON Schema
   */
  jsonSchema(schema: SchemaView, open?: boolean, treeRootOverride?: string): string;

  /**
   * Generate SHACL shapes from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param open Whether all SHACL shapes should be open (`_:b sh:closed false .`, allowing additional properties), not only those of classes whose `extra_slots` allows them.
   * @param onlyClassesFromRootSchema Whether to include only classes from the root schema (turned off by default). This is useful if you intend to generate SHACL shapes for each schema file separately, and you don't need the imported classes to be included in the generated SHACL shapes.
   * @param format RDF serialization format: `ttl` for Turtle (the default), which is prefixed and pretty-printed, or `nt` for N-Triples.
   * @returns SHACL shapes in the requested format
   */
  shacl(schema: SchemaView, open?: boolean, onlyClassesFromRootSchema?: boolean, format?: string): string;

  /**
   * Generate Scala code from a loaded LinkML schema. This is primarily used for the metamodel
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param packageName Package to generate the classes in
   * @returns JS dictionary (object) containing a mapping from filename to the generated Scala code.
   */
  scala(schema: SchemaView, packageName: string): Record<string, string>;

  /**
   * Generate RDFS from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param onlyClassesFromRootSchema Whether to include only classes from the root schema (turned off by default). This is useful if you intend to generate SHACL shapes for each schema file separately, and you don't need the imported classes to be included in the generated SHACL shapes.
   * @param format RDF serialization format: `ttl` for Turtle (the default), which is prefixed and pretty-printed, or `nt` for N-Triples.
   * @returns RDFS in the requested format
   */
  rdfs(schema: SchemaView, onlyClassesFromRootSchema?: boolean, format?: string): string;

  /**
   * Generate an OWL 2 ontology from a loaded LinkML schema. Classes, slots, enums and types become OWL classes, properties, classes of individuals and datatypes, with the same IRIs as [[shacl]] and [[rdfs]].
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param onlyRootSchema Whether to describe only the root schema, with an `owl:imports` for each schema it imports (turned off by default, which merges the imported schemas in).
   * @param metadataProfile Which annotation property holds descriptions: `rdfs` for `rdfs:comment` (the default) or `linkml` for `skos:definition`, as in the LinkML metamodel.
   * @param permissibleValues What permissible values become: `individual` (the default) or `class`.
   * @param format RDF serialization format: `ttl` for Turtle (the default), which is prefixed and pretty-printed, or `nt` for N-Triples.
   * @returns The ontology in the requested format
   */
  owl(schema: SchemaView, onlyRootSchema?: boolean, metadataProfile?: string, permissibleValues?: string, format?: string): string;

  /**
   * Read an OWL ontology and produce the LinkML schema it describes.  The opposite of [[owl]]. Feed the result to [[loadFromString]] if you want to run a generator over it.
   * @param ontology The ontology, as Turtle (or N-Triples, which is Turtle too).
   * @param config How to map the ontology: the text of the config itself, as YAML or JSON, not a path to a file (see `docs/owl.md`). By default, settings that suit most ontologies.
   * @param outFormat Output serialization format to use. One of yaml|json. Default: yaml
   * @param listNotImported Whether to list what could not be imported, in comments at the top of the YAML. Default: false
   * @param importMap The LinkML schemas that the config maps `owl:imports` to, keyed as in [[loadFromString]]. The schema then uses their terms by name rather than copying them in.
   * @param inputFormat RDF syntax of the ontology. One of ttl|nt. Default: ttl, which also reads N-Triples.
   * @returns The LinkML schema, serialized in the specified format.
   */
  fromOwl(ontology: string, config?: string, outFormat?: string, listNotImported?: boolean, importMap?: Record<string, string>, inputFormat?: string): string;

  /**
   * Materialize a derived LinkML schema from a loaded LinkML schema. Derives classes and prunes unreachable elements.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for removing unused elements (classes, types, enums). One of treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class. schema - remove all elements unreachable from any of the classes defined in the root schema. skip - do not remove unused elements. Default: treeRoot
   * @param skipDerivation If true, will not derive classes and instead copy them as-is.
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root. Does nothing if not in tree root pruning mode.
   * @param outFormat Output serialization format to use. One of yaml|json. Default: yaml
   * @returns The derived [[SchemaDefinition]] serialized in the specified format.
   */
  linkml(schema: SchemaView, pruningMode?: string, skipDerivation?: boolean, treeRoot?: string, outFormat?: string): string;

  /**
   * Generate a Frictionless Data Package from a loaded LinkML schema. Every class becomes a CSV table, described by its own Table Schema, and references between classes become foreign keys between the tables.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for choosing which classes become tables. One of treeRoot|schema|skip. treeRoot - only classes reachable from the tree_root class. schema - only classes reachable from any of the classes defined in the root schema. skip - every class. Default: skip
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root. Does nothing if not in tree root pruning mode.
   * @param skipClassesWithoutIdentifier Whether to skip classes that have no identifier slot. Such a table gets no primary key and nothing can reference it, so it is often not useful. Default: false
   * @returns JS dictionary (object) containing a mapping from filename to file content: a `datapackage.json` plus one `schemas/<table>.json` per table.
   */
  frictionless(schema: SchemaView, pruningMode?: string, treeRoot?: string, skipClassesWithoutIdentifier?: boolean): Record<string, string>;

  /**
   * Generate a GraphQL Schema from a loaded LinkML schema. Only types/interfaces/scalar/enums, queries must be provided for a specific implementation.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for removing unused elements (classes, types, enums). One of treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class. schema - remove all elements unreachable from any of the classes defined in the root schema. skip - do not remove unused elements. Default: treeRoot
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root.
   * @returns Table Schema, serialized as a JSON
   */
  graphQl(schema: SchemaView, pruningMode?: string, treeRoot?: string): string;

  /**
   * Generate TypeScript types from a loaded LinkML schema. The types describe the same JSON as [[jsonSchema]], with no runtime code: load data with `JSON.parse(text) as X` and dump it with `JSON.stringify(x)`.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for removing unused classes and enums. One of treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class. schema - remove all elements unreachable from any of the classes defined in the root schema. skip - do not remove unused elements. Default: skip
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root.
   * @param includeNull Whether optional slots may also be `null`.
   * @param open Whether all interfaces should allow additional properties, not only those of classes whose `extra_slots` allows them.
   * @returns TypeScript source code
   */
  typeScript(schema: SchemaView, pruningMode?: string, treeRoot?: string, includeNull?: boolean, open?: boolean): string;

  /**
   * Generate Python classes based on pydantic from a loaded LinkML schema. The classes load and dump the same JSON as [[jsonSchema]] describes: load data with `X.model_validate_json(text)` and dump it with `x.model_dump_json()`.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for removing unused classes and enums. One of treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class. schema - remove all elements unreachable from any of the classes defined in the root schema. skip - do not remove unused elements. Default: skip
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root.
   * @param includeNull Whether dumps keep the nulls that were loaded or set. Otherwise they leave out every field without a value.
   * @param open Whether the classes should accept and keep additional properties.
   * @returns Python source code
   */
  pydantic(schema: SchemaView, pruningMode?: string, treeRoot?: string, includeNull?: boolean, open?: boolean): string;

  /**
   * Generate a Mermaid entity relationship diagram from a loaded LinkML schema. Classes become entities, type- and enum-ranged slots become their attributes, and class-ranged slots become relationship lines.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for removing unused elements (classes, types, enums). One of treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class. schema - remove all elements unreachable from any of the classes defined in the root schema. skip - do not remove unused elements. Default: treeRoot
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root.
   * @param optionalMarker Whether to mark optional attributes with a trailing '?' on their type. Mermaid understands this from version 11.16 onwards, older renderers throw an error instead. Default: true
   * @returns The ER diagram, serialized as Mermaid
   */
  erDiagram(schema: SchemaView, pruningMode?: string, treeRoot?: string, optionalMarker?: boolean): string;

  /**
   * Generate JSON dictionaries that translate the LinkML name to specific frameworks. This is useful when the framework symbols are significant and must be known, like when constructing a query that is meant to be executed against a database conformant to a LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param target Target framework to generate translations for. One of "base", "uri", "scala", "graphql", "frictionless", "ossie", "erdiagram", "json", "typescript", or "pydantic".
   * @param derivedAttributes Also list inherited slots, mixin slots and slots from the class's `slots` list in classAttributes. Without it, classAttributes lists only the class's own attributes. Default: false
   * @returns Translation dictionary for translating the linkml names to framework names.
   */
  translation(schema: SchemaView, target: string, derivedAttributes?: boolean): string;

  /**
   * Generate an Apache Ossie ontology from a loaded LinkML schema. Classes become entity types, enums and named types become value types, and slots become the relationships grouped under the concept that plays their first role.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for choosing which elements become concepts. One of treeRoot|schema|skip. treeRoot - only elements reachable from the tree_root class. schema - only elements reachable from any of the classes defined in the root schema. skip - every element. Default: skip
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root. Does nothing if not in tree root pruning mode.
   * @param outFormat Output serialization format to use. One of yaml|json. Default: yaml
   * @returns The ontology, serialized in the specified format.
   */
  ossie(schema: SchemaView, pruningMode?: string, treeRoot?: string, outFormat?: string): string;

  /**
   * Read an Apache Ossie ontology and produce the LinkML schema it describes.  The opposite of [[ossie]]. Feed the result to [[loadFromString]] if you want to run a generator over it.
   * @param ontology The ontology document, as YAML or JSON.
   * @param schemaId The `id` of the schema to produce. An Ossie ontology has none of its own, so by default it is a placeholder built from the ontology's name.
   * @param outFormat Output serialization format to use. One of yaml|json. Default: yaml
   * @returns The LinkML schema, serialized in the specified format.
   */
  fromOssie(ontology: string, schemaId?: string, outFormat?: string): string;

  /**
   * Lint a loaded LinkML schema, finding problems that may cause issues when using the model. This method returns a structured JSON that follows the validation-report.yaml model.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details` from the model's `equals_expression`s. Turn it off to get only the structured fields.
   * @returns A `SchemaValidationReport` as a plain JS object. `issues` is empty if the schema is clean.
   */
  lint(schema: SchemaView, inferMessages?: boolean): SchemaValidationReport;
}

export declare const LinkML: LinkMLApi;
