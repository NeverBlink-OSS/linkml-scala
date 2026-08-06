package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[AltDescription]] LinkML class
  *
  * @inheritdoc
  */
final case class AltDescriptionImpl(
    @id
    @named("source")
    altDescriptionSource: String,
    @value
    @named("description")
    altDescriptionText: String,
) extends AltDescription {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): AltDescriptionImpl =
    this
}

/** An attributed description
  *
  * @see
  *   Aliases: structured description
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class AltDescription {

  /** The source of an attributed description
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def altDescriptionSource: String

  /** Text of an attributed description
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def altDescriptionText: String

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): AltDescription
}
