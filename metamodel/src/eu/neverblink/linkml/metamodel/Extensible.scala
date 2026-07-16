package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** Mixin for classes that support extension
  *
  * @see
  *   From schema: https://w3id.org/linkml/extensions
  */
trait Extensible {

  /** A tag/text tuple attached to an arbitrary element
    *
    * @see
    *   From schema: https://w3id.org/linkml/extensions
    */
  def extensions: Map[String, ExtensionImpl]
}
