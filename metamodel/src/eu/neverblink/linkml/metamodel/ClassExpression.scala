package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** A boolean expression that can be used to dynamically determine membership of a class
  */
trait ClassExpression {

  /** Holds if at least one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def anyOf: Seq[AnonymousClassExpressionImpl]

  /** Holds if only one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def exactlyOneOf: Seq[AnonymousClassExpressionImpl]

  /** Holds if none of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def noneOf: Seq[AnonymousClassExpressionImpl]

  /** Holds if all of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def allOf: Seq[AnonymousClassExpressionImpl]

  /** Expresses constraints on a group of slots for a class expression
    */
  def slotConditions: Map[String, SlotDefinitionImpl]
}
