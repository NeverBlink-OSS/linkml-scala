package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[AnonymousTypeExpression]] LinkML class
  *
  * @inheritdoc
  */
case class AnonymousTypeExpressionImpl(
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
) extends AnonymousTypeExpression

/** A type expression that is not a top-level named type definition. Used for nesting.
  */
abstract class AnonymousTypeExpression extends TypeExpression {}
