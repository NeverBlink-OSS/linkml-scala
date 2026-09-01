package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

/** Base implementation of the [[Test]] LinkML class
  *
  * @inheritdoc
  */
final case class TestImpl(
    action: Action,
    assertion: Assertion,
    description: Option[String] = None,
    title: Option[String] = None,
) extends Test {

  override def infer(): TestImpl =
    this
}

/** @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
  */
abstract class Test {

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def action: Action

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def assertion: Assertion

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def description: Option[String]

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def title: Option[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): Test
}
