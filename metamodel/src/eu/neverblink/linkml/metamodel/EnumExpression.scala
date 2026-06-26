package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[EnumExpression]] LinkML class
  *
  * @inheritdoc
  */
case class EnumExpressionImpl(
    @named("code_set")
    codeSet: Option[UriOrCurie] = None,
    @named("code_set_tag")
    codeSetTag: Option[String] = None,
    @named("code_set_version")
    codeSetVersion: Option[String] = None,
    concepts: Seq[UriOrCurie] = Seq(),
    include: Seq[AnonymousEnumExpressionImpl] = Seq(),
    inherits: Seq[Reference[EnumDefinition]] = Seq(),
    matches: Option[MatchQueryImpl] = None,
    minus: Seq[AnonymousEnumExpressionImpl] = Seq(),
    @named("permissible_values")
    @compactDict
    permissibleValues: Map[String, PermissibleValueImpl] = Map(),
    @named("pv_formula")
    pvFormula: Option[Reference[PvFormulaOptions]] = None,
    @named("reachable_from")
    reachableFrom: Option[ReachabilityQueryImpl] = None,
) extends EnumExpression

/** An expression that constrains the range of a slot
  */
trait EnumExpression extends Expression {

  /** The identifier of an enumeration code set.
    */
  def codeSet: Option[UriOrCurie]

  /** The version tag of the enumeration code set
    *
    * @note
    *   Enum_expression cannot have both a code_set_tag and a code_set_version
    */
  def codeSetTag: Option[String]

  /** The version identifier of the enumeration code set
    *
    * @note
    *   We assume that version identifiers lexically sort in temporal order. Recommend semver when
    *   possible
    */
  def codeSetVersion: Option[String]

  /** A list of identifiers that are used to construct a set of permissible values
    */
  def concepts: Seq[UriOrCurie]

  /** An enum expression that yields a list of permissible values that are to be included, after
    * subtracting the minus set
    */
  def include: Seq[AnonymousEnumExpressionImpl]

  /** An enum definition that is used as the basis to create a new enum
    *
    * @note
    *   All permissible values for all inherited enums are copied to form the initial seed set
    */
  def inherits: Seq[Reference[EnumDefinition]]

  /** Specifies a match query that is used to calculate the list of permissible values
    */
  def matches: Option[MatchQueryImpl]

  /** An enum expression that yields a list of permissible values that are to be subtracted from the
    * enum
    */
  def minus: Seq[AnonymousEnumExpressionImpl]

  /** A list of possible values for a slot range
    *
    * @see
    *   Aliases: coded values
    */
  def permissibleValues: Map[String, PermissibleValueImpl]

  /** Defines the specific formula to be used to generate the permissible values.
    *
    * @note
    *   You cannot have BOTH the permissible_values and permissible_value_formula tag
    * @note
    *   Code_set must be supplied for this to be valid
    */
  def pvFormula: Option[Reference[PvFormulaOptions]]

  /** Specifies a query for obtaining a list of permissible values based on graph reachability
    */
  def reachableFrom: Option[ReachabilityQueryImpl]
}
