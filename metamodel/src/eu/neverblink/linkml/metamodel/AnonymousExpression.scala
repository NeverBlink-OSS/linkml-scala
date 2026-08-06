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

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): AnonymousExpression
}
