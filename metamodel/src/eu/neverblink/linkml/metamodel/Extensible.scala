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

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): Extensible
}
