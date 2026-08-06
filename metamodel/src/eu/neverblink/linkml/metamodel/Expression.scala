package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** General mixin for any class that can represent some form of expression
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
trait Expression {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): Expression
}
