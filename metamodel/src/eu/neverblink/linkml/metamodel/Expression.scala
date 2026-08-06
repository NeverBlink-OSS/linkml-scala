package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** General mixin for any class that can represent some form of expression
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
trait Expression {

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): Expression
}
