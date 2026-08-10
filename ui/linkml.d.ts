// AUTO-GENERATED from generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala.
// Do not edit by hand – regenerate with ./mill uiTypes (or generator.js.npmPackage).

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
  readonly report: any;
}

export interface LinkMLApi {
  /**
   * Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from the schema's YAML text.  The main schema is parsed directly from `mainSchema`, so it has no path of its own. If one of its imports (transitively) imports the main schema back by filename, that import cannot be matched against the root and the main schema will be loaded a second time. Use [[loadFromPath]] instead when the root schema takes part in an import cycle.
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details`.
   * @returns The validation report, and a handle to pass to the generator functions unless the schema had fatal problems.
   */
  loadFromString(mainSchema: string, importMap: Record<string, string>, inferMessages?: boolean): LoadResult;

  /**
   * Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from a path into the [[importMap]].  Unlike [[loadFromString]], the main schema is read through the import map by its own path, so it is tracked from the start of import resolution. This makes it immune to cyclic imports involving the root schema: an import that (transitively) references the root back by path resolves to the already-loaded root instead of loading it again.  Paths behave like file paths: a `.yaml` extension is appended when missing, and relative imports are resolved against the directory of their importing schema. The [[importMap]] keys must therefore be the paths as seen from the root (e.g. `"model.yaml"`, `"nested/person.yaml"`).
   * @param path Path of the main LinkML model within the [[importMap]] (e.g. `"model.yaml"`).
   * @param importMap JS dictionary (object) containing a mapping from path to LinkML models (in YAML format), including the main schema itself under [[path]].
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details`.
   * @returns The validation report, and a handle to pass to the generator functions unless the schema had fatal problems.
   */
  loadFromPath(path: string, importMap: Record<string, string>, inferMessages?: boolean): LoadResult;

  /**
   * Generate JSON Schema from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param open Whether the JSON Schema should allow `additionalProperties` or not.
   * @param treeRootOverride Override for the LinkML `tree_root` class which will be at the root of the JSON Schema.
   * @returns Serialized JSON Schema
   */
  jsonSchema(schema: SchemaView, open?: boolean, treeRootOverride?: string): string;

  /**
   * Generate SHACL shapes (in N-Triples format) from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param open Whether the SHACL shapes should be open (`_:b sh:closed false .`, allowing additional properties).
   * @param onlyClassesFromRootSchema Whether to include only classes from the root schema (turned off by default). This is useful if you intend to generate SHACL shapes for each schema file separately, and you don't need the imported classes to be included in the generated SHACL shapes.
   * @returns SHACL shapes in N-Triples format
   */
  shacl(schema: SchemaView, open?: boolean, onlyClassesFromRootSchema?: boolean): string;

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
   * @returns RDFS in N-Triples format
   */
  rdfs(schema: SchemaView, onlyClassesFromRootSchema?: boolean): string;

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
   * Generate a Frictionless Table Schema from a loaded LinkML schema.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root.
   * @returns Table Schema, serialized as a JSON
   */
  tableSchema(schema: SchemaView, treeRoot?: string): string;

  /**
   * Generate a GraphQL Schema from a loaded LinkML schema. Only types/interfaces/scalar/enums, queries must be provided for a specific implementation.
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param pruningMode Pruning mode to use for removing unused elements (classes, types, enums). One of treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class. schema - remove all elements unreachable from any of the classes defined in the root schema. skip - do not remove unused elements. Default: treeRoot
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root.
   * @returns Table Schema, serialized as a JSON
   */
  graphQl(schema: SchemaView, pruningMode?: string, treeRoot?: string): string;

  /**
   * Lint a loaded LinkML schema, finding problems that may cause issues when using the model. This method returns a structured JSON that follows the validation-report.yaml model.  TODO: consider typing the return value in TypeScript using a TypeScript generator. See: https://github.com/NeverBlink-OSS/linkml-scala/issues/127
   * @param schema A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
   * @param inferMessages Whether to fill in each issue's human-readable `message` and `details` from the model's `equals_expression`s. Turn it off to get only the structured fields.
   * @returns A `SchemaValidationReport` as a plain JS object. `issues` is empty if the schema is clean.
   */
  lint(schema: SchemaView, inferMessages?: boolean): any;
}

export declare const LinkML: LinkMLApi;
