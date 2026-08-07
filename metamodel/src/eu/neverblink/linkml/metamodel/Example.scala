package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Example]] LinkML class
  *
  * @inheritdoc
  */
final case class ExampleImpl(
    value: Option[String] = None,
    @named("description")
    valueDescription: Option[String] = None,
    @named("object")
    valueObject: Option[Anything] = None,
) extends Example {

  override def infer(): ExampleImpl =
    this
}

/** Usage example and description
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class Example {

  /** Example value
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def value: Option[String]

  /** Description of what the value is doing
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def valueDescription: Option[String]

  /** Direct object representation of the example
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def valueObject: Option[Anything]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): Example
}
