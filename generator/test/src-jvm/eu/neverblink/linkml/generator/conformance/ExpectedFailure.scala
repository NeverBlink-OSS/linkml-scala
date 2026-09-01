package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** 
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
  */
trait ExpectedFailure  {
  /** 
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def messageAssertion: Option[StringAssertionImpl]

  /** Fill in the slots that have an `equals_expression` with their computed values, and
    * check that the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression
    *   references a slot that has no value
    */
  def infer(): ExpectedFailure
}
