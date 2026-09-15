package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

/** @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class Action {

  /** Description of an element, including the implementation guide
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def description: Option[String]

  /** Human readable name of an element
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def title: Option[String]

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def `type`: Option[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): Action
}
