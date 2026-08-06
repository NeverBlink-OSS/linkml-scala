package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[ExtraSlotsExpression]] LinkML class
  *
  * @inheritdoc
  */
final case class ExtraSlotsExpressionImpl(
    allowed: Boolean = false,
    @named("range_expression")
    rangeExpression: Option[AnonymousSlotExpressionImpl] = None,
) extends ExtraSlotsExpression {

  override def infer(): ExtraSlotsExpressionImpl =
    this
}

/** An expression that defines how to handle additional data in an instance of class beyond the
  * slots/attributes defined for that class. See `extra_slots` for usage examples.
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class ExtraSlotsExpression extends Expression {

  /** Whether or not something is allowed. Usage defined by context.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def allowed: Boolean

  /** A range that is described as a boolean expression combining existing ranges
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   One use for this is being able to describe a range using any_of expressions, for example to
    *   combine two enums
    */
  def rangeExpression: Option[AnonymousSlotExpressionImpl]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): ExtraSlotsExpression
}
