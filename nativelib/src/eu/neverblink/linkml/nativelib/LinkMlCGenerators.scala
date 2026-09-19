// AUTO-GENERATED from model/generator-options.yaml and the optiongen Entrypoints registry.
// Do not edit by hand - regenerate with LINKML_NATIVE=1 ./mill bindings.
package eu.neverblink.linkml.nativelib

import scala.scalanative.unsafe.*

/** The generator entry points of the C ABI, one per generator.
  *
  * All of them have the same shape: a schema handle, an options JSON that may be NULL for defaults,
  * and an error out-param. They return the generated document, or NULL with `*error` set. Release
  * returned strings with `linkml_free`.
  *
  * See [[LinkMlCApi]] for loading, linting and the lifecycle.
  */
object LinkMlCGenerators {

  /** Options for generating JSON Schema. Options: `open`, `treeRoot`, `treeRootInlineType`,
    * `indentationStep`, `metadataLanguage`, `includeNull`.
    */
  @exported("linkml_json_schema")
  def jsonSchema(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.jsonSchema)

  /** Options for generating SHACL shapes. Options: `open`, `onlyClassesFromRootSchema`, `format`.
    */
  @exported("linkml_shacl")
  def shacl(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.shacl)

  /** Options for generating RDF schema. Options: `onlyClassesFromRootSchema`, `format`. */
  @exported("linkml_rdfs")
  def rdfs(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.rdfs)

  /** Options for materializing a derived LinkML schema. Derives classes and can prune unreachable
    * elements. Options: `pruningMode`, `skipClassDerivation`, `outputFormat`.
    */
  @exported("linkml_linkml")
  def linkml(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.linkml)

  /** Options for generating a Frictionless Data Package. Each selected class becomes a CSV table,
    * described by its own Table Schema, and references between classes can become foreign keys
    * between the tables. Returns a JSON object mapping filenames to content. Options:
    * `pruningMode`, `skipClassesWithoutIdentifier`, `metadataLanguage`.
    */
  @exported("linkml_frictionless")
  def frictionless(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.frictionlessFiles)

  /** Options for generating a GraphQL schema. Only types/interfaces/scalar/enums, queries must be
    * provided for a specific implementation. Options: `pruningMode`, `metadataLanguage`.
    */
  @exported("linkml_graphql")
  def graphql(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.graphQl)

  /** Options for generating Mermaid entity relationship diagrams. Classes become entities, type-
    * and enum-ranged slots become their attributes, and class-ranged slots become relationship
    * lines. Options: `pruningMode`, `optionalMarker`.
    */
  @exported("linkml_er_diagram")
  def erDiagram(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.erDiagram)

  /** Options for generating an Apache Ossie ontology. Classes become entity types, enums and named
    * types become value types, and slots become the relationships grouped under the concept that
    * plays their first role. Options: `pruningMode`, `outputFormat`, `metadataLanguage`.
    */
  @exported("linkml_ossie")
  def ossie(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.ossie)

  /** Options for generating Scala classes. This is primarily used for the metamodel. Returns a JSON
    * object mapping filenames to content. Options: `package`, `generateEmitPrefixes`,
    * `metadataLanguage`.
    */
  @exported("linkml_scala")
  def scala(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.scalaFiles)

  /** Options for generating JSON dictionaries that translate the LinkML name to specific
    * frameworks. This is useful when the framework symbols are significant and must be known, like
    * when constructing a query that is meant to be executed against a database conformant to a
    * LinkML schema. Options: `to`, `indentationStep`.
    */
  @exported("linkml_translation")
  def translation(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.translation)
}
