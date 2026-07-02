package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** An abstract class grouping named types and anonymous type expressions
  */
trait TypeExpression extends Expression {

  /** The string value of the slot must conform to this regular expression expressed in the string
    */
  def pattern: Option[String]

  /** Holds if at least one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def anyOf: Seq[AnonymousTypeExpressionImpl]

  /** Holds if only one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def exactlyOneOf: Seq[AnonymousTypeExpressionImpl]

  /** Holds if none of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def noneOf: Seq[AnonymousTypeExpressionImpl]

  /** Holds if all of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    */
  def allOf: Seq[AnonymousTypeExpressionImpl]

  /** The slot must have range of a number and the value of the slot must equal the specified value
    */
  def equalsNumber: Option[Int]

  /** The slot must have range string and the value of the slot must equal the specified value
    */
  def equalsString: Option[String]

  /** The slot must have range string and the value of the slot must equal one of the specified
    * values
    */
  def equalsStringIn: Seq[String]

  /** Causes the slot value to be interpreted as a uriorcurie after prefixing with this string
    */
  def implicitPrefix: Option[String]

  /** For ordinal ranges, the value must be equal to or lower than this
    *
    * @see
    *   Aliases: high value
    * @note
    *   Range to be refined to an "Ordinal" metaclass - see
    *   https://github.com/linkml/linkml/issues/1384#issuecomment-1892721142
    */
  def maximumValue: Option[LinkMlAny]

  /** For ordinal ranges, the value must be equal to or higher than this
    *
    * @see
    *   Aliases: low value
    * @note
    *   Range to be refined to an "Ordinal" metaclass - see
    *   https://github.com/linkml/linkml/issues/1384#issuecomment-1892721142
    */
  def minimumValue: Option[LinkMlAny]

  /** The string value of the slot must conform to the regular expression in the pattern expression
    *
    * @see
    *   https://linkml.io/linkml/schemas/constraints.html#structured-patterns
    */
  def structuredPattern: Option[PatternExpressionImpl]

  /** An encoding of a unit
    */
  def unit: Option[UnitOfMeasureImpl]
}
