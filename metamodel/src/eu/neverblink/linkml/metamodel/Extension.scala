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
  *
  * @see
  *   From schema: https://w3id.org/linkml/extensions
  */
abstract class Extension {

  /** A tag associated with an extension
    *
    * @see
    *   From schema: https://w3id.org/linkml/extensions
    */
  def extensionTag: UriOrCurie

  /** The actual annotation
    *
    * @see
    *   From schema: https://w3id.org/linkml/extensions
    */
  def extensionValue: AnyValue

  /** A tag/text tuple attached to an arbitrary element
    *
    * @see
    *   From schema: https://w3id.org/linkml/extensions
    */
  def extensions: Map[String, ExtensionImpl]
}
