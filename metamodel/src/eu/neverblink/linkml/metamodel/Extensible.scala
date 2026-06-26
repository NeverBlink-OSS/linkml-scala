package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** Mixin for classes that support extension
  */
trait Extensible {

  /** A tag/text tuple attached to an arbitrary element
    */
  def extensions: Map[String, ExtensionImpl]
}
