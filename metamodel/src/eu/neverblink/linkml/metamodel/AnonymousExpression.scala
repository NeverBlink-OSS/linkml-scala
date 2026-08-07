package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** An abstract parent class for any nested expression
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  * @note
  *   Anonymous expressions are useful for when it is necessary to build a complex expression
  *   without introducing a named element for each sub-expression
  */
abstract class AnonymousExpression extends Expression, Extensible, Annotatable, CommonMetadata {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): AnonymousExpression
}
