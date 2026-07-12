/**
 * TypeScript declarations for the LinkML-Scala JS library (`@neverblink/linkml`).
 *
 * The implementation is generated from Scala by Scala.js; this file is
 * hand-maintained and must be kept in sync with
 * `generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala`.
 */

/**
 * Mapping from imported model filename to its LinkML model source (YAML).
 * Every `imports:` entry referenced by the main schema must have a matching
 * entry here.
 */
export type ImportMap = Record<string, string>;

/**
 * Pruning mode for {@link LinkMLApi.linkml}, controlling how unused elements
 * (classes, types, enums) are removed:
 * - `treeRoot` — remove everything unreachable from the `tree_root` class;
 * - `schema` — remove everything unreachable from any class defined in the root schema;
 * - `skip` — do not remove unused elements.
 */
export type PruningMode = "treeRoot" | "schema" | "skip";

/** Output serialization format for {@link LinkMLApi.linkml}. */
export type OutputFormat = "yaml" | "yml" | "json";

export interface LinkMLApi {
  /**
   * Generate JSON Schema from the provided LinkML model.
   *
   * @param mainSchema Main LinkML model in YAML format.
   * @param importMap Mapping from filename to LinkML models (YAML) for imports.
   * @param open Whether the JSON Schema should allow `additionalProperties`. Defaults to `false`.
   * @param treeRootOverride Override for the LinkML `tree_root` class at the root of the schema.
   * @returns Serialized JSON Schema.
   */
  jsonSchema(
    mainSchema: string,
    importMap: ImportMap,
    open?: boolean,
    treeRootOverride?: string,
  ): string;

  /**
   * Generate SHACL shapes (in N-Triples format) from the provided LinkML model.
   *
   * @param mainSchema Main LinkML model in YAML format.
   * @param importMap Mapping from filename to LinkML models (YAML) for imports.
   * @param open Whether the SHACL shapes should be open (allowing additional properties). Defaults to `false`.
   * @param onlyClassesFromRootSchema Emit shapes only for classes defined in the root schema
   *   (excluding imported classes). Defaults to `false`.
   * @returns SHACL shapes in N-Triples format.
   */
  shacl(
    mainSchema: string,
    importMap: ImportMap,
    open?: boolean,
    onlyClassesFromRootSchema?: boolean,
  ): string;

  /**
   * Generate an RDFS representation (in N-Triples format) from the provided LinkML model.
   *
   * @param mainSchema Main LinkML model in YAML format.
   * @param importMap Mapping from filename to LinkML models (YAML) for imports.
   * @param onlyClassesFromRootSchema Emit only elements defined in the root schema
   *   (excluding imported ones). Defaults to `false`.
   * @returns RDFS in N-Triples format.
   */
  rdfs(mainSchema: string, importMap: ImportMap, onlyClassesFromRootSchema?: boolean): string;

  /**
   * Materialize a derived LinkML schema: resolve imports, derive class slots into
   * attributes, and prune unreachable elements.
   *
   * @param mainSchema Main LinkML model in YAML format.
   * @param importMap Mapping from filename to LinkML models (YAML) for imports.
   * @param pruningMode How to remove unused elements. Defaults to `"treeRoot"`.
   * @param skipDerivation If `true`, copy classes as-is instead of deriving them. Defaults to `false`.
   * @param treeRoot Tree-root class name to use instead of the schema's `tree_root`
   *   (only meaningful in `"treeRoot"` pruning mode).
   * @param outFormat Output serialization format. Defaults to `"yaml"`.
   * @returns The derived schema serialized in the requested format.
   */
  linkml(
    mainSchema: string,
    importMap: ImportMap,
    pruningMode?: PruningMode,
    skipDerivation?: boolean,
    treeRoot?: string,
    outFormat?: OutputFormat,
  ): string;

  /**
   * Generate Scala code from the provided LinkML model.
   *
   * @param mainSchema Main LinkML model in YAML format.
   * @param importMap Mapping from filename to LinkML models (YAML) for imports.
   * @param packageName Package to generate the classes in.
   * @returns Mapping from filename to the generated Scala source.
   */
  scala(mainSchema: string, importMap: ImportMap, packageName: string): Record<string, string>;

  /**
   * Generate a Frictionless Table Schema (serialized as JSON) from the provided LinkML model.
   *
   * @param mainSchema Main LinkML model in YAML format.
   * @param importMap Mapping from filename to LinkML models (YAML) for imports.
   * @param treeRoot Tree-root class name to use instead of the schema's `tree_root`.
   * @returns Table Schema serialized as JSON.
   */
  tableSchema(mainSchema: string, importMap: ImportMap, treeRoot?: string): string;

  /**
   * Lint the provided LinkML model, reporting problems that may cause issues.
   *
   * @param mainSchema Main LinkML model in YAML format.
   * @param importMap Mapping from filename to LinkML models (YAML) for imports.
   * @param maxProblems Maximum number of problems to include in the summary. Defaults to `5`.
   * @param verbose Whether to use the more verbose problem descriptions. Defaults to `false`.
   * @returns The summary of detected problems, or an empty string if everything is correct.
   */
  lint(mainSchema: string, importMap: ImportMap, maxProblems?: number, verbose?: boolean): string;
}

export declare const LinkML: LinkMLApi;
