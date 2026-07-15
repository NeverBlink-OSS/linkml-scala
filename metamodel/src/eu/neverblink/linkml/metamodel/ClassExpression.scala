package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** A boolean expression that can be used to dynamically determine membership of a class
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
trait ClassExpression {

  /** Holds if at least one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def anyOf: Seq[AnonymousClassExpressionImpl]

  /** Holds if only one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def exactlyOneOf: Seq[AnonymousClassExpressionImpl]

  /** Holds if none of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def noneOf: Seq[AnonymousClassExpressionImpl]

  /** Holds if all of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def allOf: Seq[AnonymousClassExpressionImpl]

  /** Expresses constraints on a group of slots for a class expression
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def slotConditions: Map[String, SlotDefinitionImpl]
}
