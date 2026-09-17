package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[StringAssertion]] LinkML class
  *
  * @inheritdoc
  */
final case class StringAssertionImpl(
    description: Option[String] = None,
    includes: Seq[String] = Seq(),
    title: Option[String] = None,
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("StringAssertion"),
) extends StringAssertion {

  override def infer(): StringAssertionImpl =
    this
}

/** An implementation of this assertion must load the result of an Action as a string, then, for
  * each value in 'includes', it must check that the value is a substring of the result of an
  * Action, failing if any of the strings are not present. Should 'includes' be empty, the assertion
  * should pass.
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class StringAssertion extends Assertion {

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
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
