package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[JsonPathAssertion]] LinkML class
  * 
  * @inheritdoc
  */
final case class JsonPathAssertionImpl(
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("JsonPathAssertion"),
    ignore: Option[String] = None,
    path: String,
    value: Any,
) extends JsonPathAssertion {
  
  override def infer(): JsonPathAssertionImpl =
    this
}

/** 
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
  */
abstract class JsonPathAssertion extends Assertion {
  /** 
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def path: String
  /** 
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def value: Any

  /** Fill in the slots that have an `equals_expression` with their computed values, and
    * check that the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression
    *   references a slot that has no value
    */
  def infer(): JsonPathAssertion
}
