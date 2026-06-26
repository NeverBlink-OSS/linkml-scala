package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Extension]] LinkML class
  *
  * @inheritdoc
  */
case class ExtensionImpl(
    @id
    @named("tag")
    extensionTag: UriOrCurie,
    @value
    @named("value")
    extensionValue: AnyValue,
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
) extends Extension

/** A tag/value pair used to add non-model information to an entry
  */
abstract class Extension {

  /** A tag associated with an extension
    */
  def extensionTag: UriOrCurie

  /** The actual annotation
    */
  def extensionValue: AnyValue

  /** A tag/text tuple attached to an arbitrary element
    */
  def extensions: Map[String, ExtensionImpl]
}
