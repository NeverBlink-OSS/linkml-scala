package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Extension]] LinkML class
  *
  * @inheritdoc
  */
final case class ExtensionImpl(
    @id
    @named("tag")
    extensionTag: UriOrCurie,
    @value
    @named("value")
    extensionValue: AnyValue,
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
) extends Extension {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): ExtensionImpl =
    this
}

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

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): Extension
}
