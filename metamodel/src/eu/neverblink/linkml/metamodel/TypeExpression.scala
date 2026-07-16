package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** An abstract class grouping named types and anonymous type expressions
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
trait TypeExpression extends Expression {

  /** The string value of the slot must conform to this regular expression expressed in the string
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def pattern: Option[String]

  /** Holds if at least one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def anyOf: Seq[AnonymousTypeExpressionImpl]

  /** Holds if only one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def exactlyOneOf: Seq[AnonymousTypeExpressionImpl]

  /** Holds if none of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def noneOf: Seq[AnonymousTypeExpressionImpl]

  /** Holds if all of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def allOf: Seq[AnonymousTypeExpressionImpl]

  /** The slot must have range of a number and the value of the slot must equal the specified value
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def equalsNumber: Option[Int]

  /** The slot must have range string and the value of the slot must equal the specified value
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def equalsString: Option[String]

  /** The slot must have range string and the value of the slot must equal one of the specified
    * values
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def equalsStringIn: Seq[String]

  /** Causes the slot value to be interpreted as a uriorcurie after prefixing with this string
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def implicitPrefix: Option[String]

  /** For ordinal ranges, the value must be equal to or lower than this
    *
    * @see
    *   Aliases: high value
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Range to be refined to an "Ordinal" metaclass - see
    *   https://github.com/linkml/linkml/issues/1384#issuecomment-1892721142
    */
  def maximumValue: Option[Anything]

  /** For ordinal ranges, the value must be equal to or higher than this
    *
    * @see
    *   Aliases: low value
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Range to be refined to an "Ordinal" metaclass - see
    *   https://github.com/linkml/linkml/issues/1384#issuecomment-1892721142
    */
  def minimumValue: Option[Anything]

  /** The string value of the slot must conform to the regular expression in the pattern expression
    *
    * @see
    *   https://linkml.io/linkml/schemas/constraints.html#structured-patterns
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def structuredPattern: Option[PatternExpressionImpl]

  /** An encoding of a unit
    *
    * @see
    *   From schema: https://w3id.org/linkml/units
    */
  def unit: Option[UnitOfMeasureImpl]
}
