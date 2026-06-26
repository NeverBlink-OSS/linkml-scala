package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[ExtraSlotsExpression]] LinkML class
  *
  * @inheritdoc
  */
case class ExtraSlotsExpressionImpl(
    allowed: Boolean = false,
    @named("range_expression")
    rangeExpression: Option[AnonymousSlotExpressionImpl] = None,
) extends ExtraSlotsExpression

/** An expression that defines how to handle additional data in an instance of class beyond the
  * slots/attributes defined for that class. See `extra_slots` for usage examples.
  */
abstract class ExtraSlotsExpression extends Expression {

  /** Whether or not something is allowed. Usage defined by context.
    */
  def allowed: Boolean

  /** A range that is described as a boolean expression combining existing ranges
    *
    * @note
    *   One use for this is being able to describe a range using any_of expressions, for example to
    *   combine two enums
    */
  def rangeExpression: Option[AnonymousSlotExpressionImpl]
}
