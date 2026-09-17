package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[LoadsAssertion]] LinkML class
  *
  * @inheritdoc
  */
final case class LoadsAssertionImpl(
    description: Option[String] = None,
    title: Option[String] = None,
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("LoadsAssertion"),
) extends LoadsAssertion {

  override def infer(): LoadsAssertionImpl =
    this
}

/** An implementation of this assertion must load the result of an Action into an appropriate
  * internal data structure. Should the process fail, the assertion fails.
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
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
