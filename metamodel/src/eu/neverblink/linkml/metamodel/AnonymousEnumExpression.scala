package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[AnonymousEnumExpression]] LinkML class
  *
  * @inheritdoc
  */
case class AnonymousEnumExpressionImpl(
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
    pvFormula: Option[PvFormulaOptions] = None,
    @named("reachable_from")
    reachableFrom: Option[ReachabilityQueryImpl] = None,
) extends AnonymousEnumExpression

/** An enum_expression that is not named
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class AnonymousEnumExpression extends EnumExpression {}
