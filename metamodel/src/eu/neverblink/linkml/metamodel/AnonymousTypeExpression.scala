package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[AnonymousTypeExpression]] LinkML class
  *
  * @inheritdoc
  */
final case class AnonymousTypeExpressionImpl(
    pattern: Option[String] = None,
    @named("any_of")
    anyOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
    @named("exactly_one_of")
    exactlyOneOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
    @named("none_of")
    noneOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
    @named("all_of")
    allOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
    @named("equals_number")
    equalsNumber: Option[Int] = None,
    @named("equals_string")
    equalsString: Option[String] = None,
    @named("equals_string_in")
    equalsStringIn: Seq[String] = Seq(),
    @named("implicit_prefix")
    implicitPrefix: Option[String] = None,
    @named("maximum_value")
    maximumValue: Option[Anything] = None,
    @named("minimum_value")
    minimumValue: Option[Anything] = None,
    @named("structured_pattern")
    structuredPattern: Option[PatternExpressionImpl] = None,
    unit: Option[UnitOfMeasureImpl] = None,
) extends AnonymousTypeExpression {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * Only single-valued slots with a `string` range are inferred; slots with any other range are
    * left untouched.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): AnonymousTypeExpressionImpl =
    this
}

/** A type expression that is not a top-level named type definition. Used for nesting.
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class AnonymousTypeExpression extends TypeExpression {

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): AnonymousTypeExpression
}
