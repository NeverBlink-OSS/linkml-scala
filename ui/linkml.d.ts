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

// The generators and their options, generated from model/generator-options.yaml.
import type { GeneratorOptionsFunctions, StatsGeneratorOptions } from "../generator/npm/generator-options.js";
export type { DocsGeneratorOptions, ErDiagramGeneratorOptions, FrictionlessGeneratorOptions, GeneratedFiles, GraphQlGeneratorOptions, JsonSchemaGeneratorOptions, LinkMlGeneratorOptions, MetadataLanguageOptions, OssieGeneratorOptions, OwlGeneratorOptions, PruningOptions, PydanticGeneratorOptions, RdfOptions, RdfsGeneratorOptions, ScalaGeneratorOptions, ShaclGeneratorOptions, StatsGeneratorOptions, TranslationGeneratorOptions, TypeScriptGeneratorOptions, JsonOutputFormat, OwlMetadataProfile, OwlPermissibleValueKind, RdfFormat, ScalaErrorHandling, TypeScriptErrorHandling, GeneratorOptionsFunctions } from "../generator/npm/generator-options.js";

/**
 * A loaded, import-resolved LinkML schema, with the generators as its methods. Create one
 * with {@link LinkMLApi.loadFromString} or {@link LinkMLApi.loadFromPath}.
 * Parse a schema once and reuse it, instead of re-parsing the YAML on every call.
 */
export interface SchemaView extends GeneratorOptionsFunctions {
  /** @internal Nominal brand – do not access. */
  readonly __linkmlSchemaView: unique symbol;
  /** {@inheritDoc GeneratorOptionsFunctions.stats} */
  stats(params?: StatsGeneratorOptions): SchemaStats;
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
   * @returns The validation report, and a handle with the generators unless the schema had fatal problems.
   */
  loadFromString(mainSchema: string, imports: Record<string, string>, inferMessages?: boolean, importMap?: Record<string, string>): LoadResult;

  /**
   * Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from a path into the [[imports]].  Unlike [[loadFromString]], the main schema is read through the imports by its own path, so it is tracked from the start of import resolution. This makes it immune to cyclic imports involving the root schema: an import that (transitively) references the root back by path resolves to the already-loaded root instead of loading it again.  Keys in the [[imports]] are import paths as seen from the root schema. The `.yaml` and `.yml` extensions are optional and interchangeable, and each imported schema is loaded only once, even when it is reached through different relative paths.
   * @param path Path of the main LinkML model within the [[imports]] (e.g. `"model.yaml"`).
   * @param imports JS dictionary (object) containing a mapping from path to LinkML models (in YAML format), including the main schema itself under [[path]].
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details`.
   * @param importMap Optional JS dictionary from an import to the location to load it from, which is a key of the [[imports]], such as `{"https://example.org/units/": "deps/units/"}`. Keys are full URIs or paths, not CURIEs. A key that ends with `/` or `#` matches every import that starts with it. CURIE imports are expanded with the prefixes of the schema that contains them before they are matched.
   * @returns The validation report, and a handle with the generators unless the schema had fatal problems.
   */
  loadFromPath(path: string, imports: Record<string, string>, inferMessages?: boolean, importMap?: Record<string, string>): LoadResult;

  /**
   * Read an OWL ontology and produce the LinkML schema it describes.  The opposite of [[SchemaViewJs.owl]]. Feed the result to [[loadFromString]] if you want to run a generator over it.
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
   * Read an Apache Ossie ontology and produce the LinkML schema it describes.  The opposite of [[SchemaViewJs.ossie]]. Feed the result to [[loadFromString]] if you want to run a generator over it.
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
