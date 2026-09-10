package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[LoadsAssertion]] LinkML class
  *
  * @inheritdoc
  */
final case class LoadsAssertionImpl(
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("LoadsAssertion"),
    description: Option[String] = None,
    title: Option[String] = None,
) extends LoadsAssertion {

  override def infer(): LoadsAssertionImpl =
    this
}

/** @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
  */
abstract class LoadsAssertion extends Assertion {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): LoadsAssertion
}
