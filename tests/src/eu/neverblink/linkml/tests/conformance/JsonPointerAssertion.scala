package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[JsonPointerAssertion]] LinkML class
  *
  * @inheritdoc
  */
final case class JsonPointerAssertionImpl(
    description: Option[String] = None,
    path: String,
    title: Option[String] = None,
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("JsonPointerAssertion"),
    value: LinkmlAny,
) extends JsonPointerAssertion {

  override def infer(): JsonPointerAssertionImpl =
    this
}

/** An implementation of this assertion must load the result of an Action as a YAML document, and
  * then dereference a JSON pointer provided in the 'path' attribute and compare it against the
  * 'value'.
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class JsonPointerAssertion extends Assertion {

  /** The JSON pointer that will be dereferenced.
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def path: String

  /** The expected value to be found after dereferencing the JSON pointer.
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def value: LinkmlAny

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): JsonPointerAssertion
}
