// AUTO-GENERATED from generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala.
// Do not edit by hand — regenerate with ./mill uiTypes (or generator.js.npmPackage).

export interface LinkMLApi {
  /**
   * Generate JSON Schema from the provided LinkML model
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param open Whether the JSON Schema should allow `additionalProperties` or not.
   * @param treeRootOverride Override for the LinkML `tree_root` class which will be at the root of the JSON Schema.
   * @returns Serialized JSON Schema
   */
  jsonSchema(mainSchema: string, importMap: Record<string, string>, open?: boolean, treeRootOverride?: string): string;

  /**
   * Generate SHACL shapes (in N-Triples format) from the provided LinkML model
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param open Whether the SHACL shapes should be open (`_:b sh:closed false .`, allowing additional properties).
   * @param onlyClassesFromRootSchema Whether to include only classes from the root schema (turned off by default). This is useful if you intend to generate SHACL shapes for each schema file separately, and you don't need the imported classes to be included in the generated SHACL shapes.
   * @returns SHACL shapes in N-Triples format
   */
  shacl(mainSchema: string, importMap: Record<string, string>, open?: boolean, onlyClassesFromRootSchema?: boolean): string;

  /**
   * Generate Scala code from the provided LinkML model. This is primarily used for the metamodel
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param packageName Package to generate the classes in
   * @returns JS dictionary (object) containing a mapping from filename to the generated Scala code.
   */
  scala(mainSchema: string, importMap: Record<string, string>, packageName: string): Record<string, string>;

  /**
   * Generate RDFS from the provided LinkML model.
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param onlyClassesFromRootSchema Whether to include only classes from the root schema (turned off by default). This is useful if you intend to generate SHACL shapes for each schema file separately, and you don't need the imported classes to be included in the generated SHACL shapes.
   * @returns JS dictionary (object) containing a mapping from filename to the generated Scala code.
   */
  rdfs(mainSchema: string, importMap: Record<string, string>, onlyClassesFromRootSchema: boolean): string;

  /**
   * Materialize a derived LinkML schema from a LinkML model. Resolves imports, derives classes, and prunes unreachable elements.
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param pruningMode Pruning mode to use for removing unused elements (classes, types, enums). One of treeRoot|schemaRoot|skip. treeRoot - remove all elements unreachable from the tree_root class. schema - remove all elements unreachable from any of the classes defined in the root schema. skip - do not remove unused elements. Default: treeRoot
   * @param skipDerivation If true, will not derive classes and instead copy them as-is.
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root. Does nothing if not in tree root pruning mode.
   * @param outFormat Output serialization format to use. One of yaml|json. Default: yaml
   * @returns The derived [[SchemaDefinition]] serialized in the specified format.
   */
  linkml(mainSchema: string, importMap: Record<string, string>, pruningMode?: string, skipDerivation?: boolean, treeRoot?: string, outFormat?: string): string;

  /**
   * Generate a Frictionless Table Schema from a LinkML model.
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param treeRoot Tree root class name to use instead of the schema defined tree_root.
   * @returns Table Schema, serialized as a JSON
   */
  tableSchema(mainSchema: string, importMap: Record<string, string>, treeRoot?: string): string;

  /**
   * Lint the provided LinkML model, finding problems that may cause issues when using the model.
   * @param mainSchema Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all imports must be made available in the [[importMap]].
   * @param importMap JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
   * @param maxProblems Maximum number of problems to include in the summary
   * @param verbose Whether to use the more verbose problem descriptions
   * @returns The summary of detected problems, or an empty string if everything is correct
   */
  lint(mainSchema: string, importMap: Record<string, string>, maxProblems?: number, verbose?: boolean): string;
}

export declare const LinkML: LinkMLApi;
