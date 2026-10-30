// AUTO-GENERATED from generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala.
// Do not edit by hand – regenerate with ./mill uiTypes (or generator.js.npmPackage).

// The validation report, generated from model/issue-types.yaml. Each issue's `issue_type`
// names its kind, so a `switch` on it narrows the issue to that kind.
import type { SchemaValidationReport } from "../generator/npm/validation-report.js";
export * from "../generator/npm/validation-report.js";

// The build info, generated from model/build-info.yaml.
import type { BuildInfo } from "../generator/npm/build-info.js";
export * from "../generator/npm/build-info.js";

// The schema statistics, generated from model/schema-stats.yaml.
import type { SchemaStats } from "../generator/npm/schema-stats.js";
export * from "../generator/npm/schema-stats.js";

// The options of the generators, generated from model/generator-options.yaml.
import type { DocsGeneratorOptions, ErDiagramGeneratorOptions, FrictionlessGeneratorOptions, GraphQlGeneratorOptions, JsonSchemaGeneratorOptions, LinkMlGeneratorOptions, OssieGeneratorOptions, OwlGeneratorOptions, PydanticGeneratorOptions, RdfsGeneratorOptions, ScalaGeneratorOptions, ShaclGeneratorOptions, StatsGeneratorOptions, TranslationGeneratorOptions, TypeScriptGeneratorOptions } from "../generator/npm/generator-options.js";
export type { DocsGeneratorOptions, ErDiagramGeneratorOptions, FrictionlessGeneratorOptions, GraphQlGeneratorOptions, JsonSchemaGeneratorOptions, LinkMlGeneratorOptions, MetadataLanguageOptions, OssieGeneratorOptions, OwlGeneratorOptions, PruningOptions, PydanticGeneratorOptions, RdfOptions, RdfsGeneratorOptions, ScalaGeneratorOptions, ShaclGeneratorOptions, StatsGeneratorOptions, TranslationGeneratorOptions, TypeScriptGeneratorOptions, JsonOutputFormat, OwlMetadataProfile, OwlPermissibleValueKind, RdfFormat, ScalaErrorHandling, TypeScriptErrorHandling } from "../generator/npm/generator-options.js";

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
   * Version and build metadata of this copy of ohi LinkML: which version it is, which LinkML metamodel it was built against, and what it is running on.  Useful in bug reports, and for checking that the version you loaded is the one you meant to.
   * @returns A `BuildInfo` object, as described by https://linkml.ohi.dev/model/build-info
   */
  buildInfo(): BuildInfo;

  /**
   * Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from the schema's YAML text.  The main schema is parsed directly from `mainSchema`, so it has no path of its own. If one of its imports (transitively) imports the main schema back by filename, that import cannot be matched against the root and the main schema will be loaded a second time. Use [[loadFromPath]] instead when the root schema takes part in an import cycle.  See [[loadFromPath]] for the correct key format.
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[imports]].
   * @param imports JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details`.
   * @param importMap Optional JS dictionary from an import to the location to load it from, which is a key of the [[imports]], such as `{"https://example.org/units/": "deps/units/"}`. Keys are full URIs or paths, not CURIEs. A key that ends with `/` or `#` matches every import that starts with it. CURIE imports are expanded with the prefixes of the schema that contains them before they are matched.
   * @returns The validation report, and a handle to pass to the generator functions unless the schema had fatal problems.
   */
  loadFromString(mainSchema: string, imports: Record<string, string>, inferMessages?: boolean, importMap?: Record<string, string>): LoadResult;

  /**
   * Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from a path into the [[imports]].  Unlike [[loadFromString]], the main schema is read through the imports by its own path, so it is tracked from the start of import resolution. This makes it immune to cyclic imports involving the root schema: an import that (transitively) references the root back by path resolves to the already-loaded root instead of loading it again.  Keys in the [[imports]] are import paths as seen from the root schema. The `.yaml` and `.yml` extensions are optional and interchangeable, and each imported schema is loaded only once, even when it is reached through different relative paths.
   * @param path Path of the main LinkML model within the [[imports]] (e.g. `"model.yaml"`).
   * @param imports JS dictionary (object) containing a mapping from path to LinkML models (in YAML format), including the main schema itself under [[path]].
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details`.
   * @param importMap Optional JS dictionary from an import to the location to load it from, which is a key of the [[imports]], such as `{"https://example.org/units/": "deps/units/"}`. Keys are full URIs or paths, not CURIEs. A key that ends with `/` or `#` matches every import that starts with it. CURIE imports are expanded with the prefixes of the schema that contains them before they are matched.
   * @returns The validation report, and a handle to pass to the generator functions unless the schema had fatal problems.
   */
  loadFromPath(path: string, imports: Record<string, string>, inferMessages?: boolean, importMap?: Record<string, string>): LoadResult;

  /**
   * Generate JSON Schema from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `JsonSchemaGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns Serialized JSON Schema
   */
  jsonSchema(schema: SchemaView, options?: Partial<JsonSchemaGeneratorOptions>): string;

  /**
   * Generate SHACL shapes from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `ShaclGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns SHACL shapes in the requested format
   */
  shacl(schema: SchemaView, options?: Partial<ShaclGeneratorOptions>): string;

  /**
   * Generate Scala code from a loaded LinkML schema. This is primarily used for the metamodel
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `ScalaGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns JS dictionary (object) containing a mapping from filename to the generated Scala code.
   */
  scala(schema: SchemaView, options?: Partial<ScalaGeneratorOptions>): Record<string, string>;

  /**
   * Generate RDFS from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `RdfsGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns RDFS in the requested format
   */
  rdfs(schema: SchemaView, options?: Partial<RdfsGeneratorOptions>): string;

  /**
   * Generate an OWL 2 ontology from a loaded LinkML schema. Classes, slots, enums and types become OWL classes, properties, classes of individuals and datatypes, with the same IRIs as [[shacl]] and [[rdfs]].
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `OwlGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns The ontology in the requested format
   */
  owl(schema: SchemaView, options?: Partial<OwlGeneratorOptions>): string;

  /**
   * Read an OWL ontology and produce the LinkML schema it describes.  The opposite of [[owl]]. Feed the result to [[loadFromString]] if you want to run a generator over it.
   * @param ontology The ontology, as Turtle (or N-Triples, which is Turtle too).
   * @param config How to map the ontology: the text of the config itself, as YAML or JSON, not a path to a file (see https://linkml.ohi.dev/docs/owl/). By default, settings that suit most ontologies.
   * @param outFormat Output serialization format to use. One of yaml|json. Default: yaml
   * @param listNotImported Whether to list what could not be imported, in comments at the top of the YAML. Default: false
   * @param imports The LinkML schemas that the config maps `owl:imports` to, keyed as in [[loadFromString]]. The schema then uses their terms by name rather than copying them in.
   * @param inputFormat RDF syntax of the ontology. One of ttl|nt. Default: ttl, which also reads N-Triples.
   * @param fullUris Write URIs in full, instead of compacting them to CURIEs with the schema's prefixes. Default: false
   * @returns The LinkML schema, serialized in the specified format.
   */
  fromOwl(ontology: string, config?: string, outFormat?: string, listNotImported?: boolean, imports?: Record<string, string>, inputFormat?: string, fullUris?: boolean): string;

  /**
   * Materialize a derived LinkML schema from a loaded LinkML schema. Derives classes and prunes unreachable elements.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `LinkMlGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns The derived [[SchemaDefinition]] serialized in the specified format.
   */
  linkml(schema: SchemaView, options?: Partial<LinkMlGeneratorOptions>): string;

  /**
   * Generate a Frictionless Data Package from a loaded LinkML schema. Every class becomes a CSV table, described by its own Table Schema, and references between classes become foreign keys between the tables.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `FrictionlessGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns JS dictionary (object) containing a mapping from filename to file content: a `datapackage.json` plus one `schemas/<table>.json` per table.
   */
  frictionless(schema: SchemaView, options?: Partial<FrictionlessGeneratorOptions>): Record<string, string>;

  /**
   * Generate a GraphQL Schema from a loaded LinkML schema. Only types/interfaces/scalar/enums, queries must be provided for a specific implementation.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `GraphQlGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns Table Schema, serialized as a JSON
   */
  graphQl(schema: SchemaView, options?: Partial<GraphQlGeneratorOptions>): string;

  /**
   * Generate TypeScript types from a loaded LinkML schema. The types describe the same JSON as [[jsonSchema]], with no runtime code: load data with `JSON.parse(text) as X` and dump it with `JSON.stringify(x)`.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `TypeScriptGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns TypeScript source code
   */
  typeScript(schema: SchemaView, options?: Partial<TypeScriptGeneratorOptions>): string;

  /**
   * Generate Python classes based on pydantic from a loaded LinkML schema. The classes load and dump the same JSON as [[jsonSchema]] describes: load data with `X.model_validate_json(text)` and dump it with `x.model_dump_json()`.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `PydanticGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns Python source code
   */
  pydantic(schema: SchemaView, options?: Partial<PydanticGeneratorOptions>): string;

  /**
   * Generate a Mermaid entity relationship diagram from a loaded LinkML schema. Classes become entities, type- and enum-ranged slots become their attributes, and class-ranged slots become relationship lines.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `ErDiagramGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns The ER diagram, serialized as Mermaid
   */
  erDiagram(schema: SchemaView, options?: Partial<ErDiagramGeneratorOptions>): string;

  /**
   * Statistics about a loaded LinkML schema: how many elements of each kind it has, how many of them are documented, deprecated or mapped, the strings in each language, and the imports.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `StatsGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns A `SchemaStats` object, as described by https://linkml.ohi.dev/model/schema-stats
   */
  stats(schema: SchemaView, options?: Partial<StatsGeneratorOptions>): SchemaStats;

  /**
   * Generate Markdown documentation from a loaded LinkML schema: an index page plus a page for each class, slot, enum and type, which link to each other.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `DocsGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns JS dictionary (object) containing a mapping from filename to file content: `index.md` plus `classes/<name>.md`, `slots/<name>.md`, `enums/<name>.md`, `types/<name>.md` and `functions/<name>.md`, or only `docs.md` if [[singleFile]] is true.
   */
  docs(schema: SchemaView, options?: Partial<DocsGeneratorOptions>): Record<string, string>;

  /**
   * Generate JSON dictionaries that translate the LinkML name to specific frameworks. This is useful when the framework symbols are significant and must be known, like when constructing a query that is meant to be executed against a database conformant to a LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `TranslationGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns Translation dictionary for translating the linkml names to framework names.
   */
  translation(schema: SchemaView, options?: Partial<TranslationGeneratorOptions>): string;

  /**
   * Generate an Apache Ossie ontology from a loaded LinkML schema. Classes become entity types, enums and named types become value types, and slots become the relationships grouped under the concept that plays their first role.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param options The options of the generator, as in `OssieGeneratorOptions` of `model/generator-options.yaml`, with their names in snake case. The options left out take their defaults.
   * @returns The ontology, serialized in the specified format.
   */
  ossie(schema: SchemaView, options?: Partial<OssieGeneratorOptions>): string;

  /**
   * Read an Apache Ossie ontology and produce the LinkML schema it describes.  The opposite of [[ossie]]. Feed the result to [[loadFromString]] if you want to run a generator over it.
   * @param ontology The ontology document, as YAML or JSON.
   * @param schemaId The `id` of the schema to produce. An Ossie ontology has none of its own, so by default it is a placeholder built from the ontology's name.
   * @param outFormat Output serialization format to use. One of yaml|json. Default: yaml
   * @param fullUris Write URIs in full, instead of compacting them to CURIEs with the schema's prefixes. Default: false
   * @returns The LinkML schema, serialized in the specified format.
   */
  fromOssie(ontology: string, schemaId?: string, outFormat?: string, fullUris?: boolean): string;

  /**
   * Lint a loaded LinkML schema, finding problems that may cause issues when using the model. This method returns a structured JSON that follows the validation-report.yaml model.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details` from the model's `equals_expression`s. Turn it off to get only the structured fields.
   * @returns A `SchemaValidationReport` as a plain JS object. `issues` is empty if the schema is clean.
   */
  lint(schema: SchemaView, inferMessages?: boolean): SchemaValidationReport;
}

export declare const LinkML: LinkMLApi;
