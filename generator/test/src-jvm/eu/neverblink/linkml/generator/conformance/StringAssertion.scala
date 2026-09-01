package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[StringAssertion]] LinkML class
  *
  * @inheritdoc
  */
final case class StringAssertionImpl(
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("StringAssertion"),
    description: Option[String] = None,
    includes: Seq[String] = Seq(),
    title: Option[String] = None,
) extends StringAssertion {

  override def infer(): StringAssertionImpl =
    this
}

/** @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
  */
abstract class StringAssertion extends Assertion {

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def includes: Seq[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): StringAssertion
}
