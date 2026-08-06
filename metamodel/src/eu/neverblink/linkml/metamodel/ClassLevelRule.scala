package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** A rule that is applied to classes
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class ClassLevelRule {

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): ClassLevelRule
}
