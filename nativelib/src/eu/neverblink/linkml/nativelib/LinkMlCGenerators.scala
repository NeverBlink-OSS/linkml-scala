// AUTO-GENERATED from mill-build/src/Entrypoints.scala and the generators' Options case
// classes. Do not edit by hand - regenerate with LINKML_NATIVE=1 ./mill bindings.
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

  /** Generate JSON Schema. Options: `open`, `treeRoot`, `treeRootInlineType`, `indentationStep`,
    * `metadataLanguage`.
    */
  @exported("linkml_json_schema")
  def jsonSchema(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.jsonSchema)

  /** Generate SHACL shapes as RDF. Options: `open`, `onlyClassesFromRootSchema`, `format`. */
  @exported("linkml_shacl")
  def shacl(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.shacl)

  /** Generate RDFS as RDF. Options: `onlyClassesFromRootSchema`, `format`. */
  @exported("linkml_rdfs")
  def rdfs(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.rdfs)

  /** Materialize a derived LinkML schema. Options: `pruningMode`, `skipClassDerivation`,
    * `outputFormat`.
    */
  @exported("linkml_linkml")
  def linkml(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.linkml)

  /** Generate a Frictionless Data Package, as a JSON object mapping filename to content. Options:
    * `pruningMode`, `skipClassesWithoutIdentifier`, `metadataLanguage`.
    */
  @exported("linkml_frictionless")
  def frictionless(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.frictionlessFiles)

  /** Generate a GraphQL schema. Options: `pruningMode`, `metadataLanguage`. */
  @exported("linkml_graphql")
  def graphql(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.graphQl)

  /** Generate a Mermaid entity relationship diagram. Options: `pruningMode`, `optionalMarker`. */
  @exported("linkml_er_diagram")
  def erDiagram(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.erDiagram)

  /** Generate Scala sources, as a JSON object mapping filename to source. Options: `package`,
    * `generateEmitPrefixes`, `metadataLanguage`.
    */
  @exported("linkml_scala")
  def scala(handle: CLongLong, options: CString, error: Ptr[CString]): CString =
    LinkMlCApi.document(handle, options, error, LinkMlNativeApi.scalaFiles)
}
