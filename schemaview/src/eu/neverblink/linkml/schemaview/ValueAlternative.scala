package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{SlotExpression, TypeDefinition}
import eu.neverblink.linkml.runtime.*

/** One type that a value can take, with the constraints that apply to values of that type.
  *
  * A slot or a type whose range is a `union_of` type has one alternative per member, with nested
  * unions flattened. Any other slot or type has one alternative. See [[TypeView.alternatives]] and
  * [[TypeAttributeView.alternatives]].
  *
  * Each constraint comes from the nearest place that sets it: the slot, then each union on the way
  * to the member, then the member type.
  *
  * A constraint is kept only if it applies to values of [[typeView]]:
  *   - `pattern` applies to anything but numbers and booleans;
  *   - `minimum_value` and `maximum_value` apply to numbers, dates and times;
  *   - `minimum_length` and `maximum_length` apply to text: strings, NCNames, localized text, URIs
  *     and CURIEs.
  *
  * @param typeView
  *   The type of the values, never a union
  */
final case class ValueAlternative private (
    typeView: TypeView,
    pattern: Option[LinkmlPattern],
    minimumValue: Option[LinkmlAny],
    maximumValue: Option[LinkmlAny],
    minimumLength: Option[Int],
    maximumLength: Option[Int],
) {

  /** Whether the alternative has any constraints. */
  def hasConstraints: Boolean =
    pattern.isDefined || minimumValue.isDefined || maximumValue.isDefined ||
      minimumLength.isDefined || maximumLength.isDefined

  /** This alternative with the constraints of a slot or a slot expression, which have precedence
    * over the ones it has.
    */
  def withConstraintsOf(expression: SlotExpression): ValueAlternative =
    withConstraints(
      expression.pattern,
      expression.minimumValue,
      expression.maximumValue,
      expression.minimumLength,
      expression.maximumLength,
    )

  /** This alternative with the constraints of a type, which have precedence over the ones it has.
    */
  private[schemaview] def withConstraintsOf(definition: TypeDefinition): ValueAlternative =
    withConstraints(
      definition.pattern,
      definition.minimumValue,
      definition.maximumValue,
      definition.minimumLength,
      definition.maximumLength,
    )

  private def withConstraints(
      pattern: Option[String],
      minimumValue: Option[LinkmlAny],
      maximumValue: Option[LinkmlAny],
      minimumLength: Option[Int],
      maximumLength: Option[Int],
  ): ValueAlternative =
    ValueAlternative(
      typeView,
      LinkmlPattern.option(
        combineOption(pattern, LinkmlPattern.written(this.pattern), combinePattern),
      ),
      combineOption(minimumValue, this.minimumValue, combineMin),
      combineOption(maximumValue, this.maximumValue, combineMax),
      combineOption(minimumLength, this.minimumLength, combineFallback),
      combineOption(maximumLength, this.maximumLength, combineFallback),
    )
}

object ValueAlternative {

  /** The only alternative of a type that is not a union, with the constraints of the type and its
    * `typeof` ancestors.
    */
  private[schemaview] def of(typeView: TypeView): ValueAlternative = {
    val d = typeView.derivedType
    ValueAlternative(
      typeView,
      LinkmlPattern.option(d.pattern),
      d.minimumValue,
      d.maximumValue,
      d.minimumLength,
      d.maximumLength,
    )
  }

  /** Keeps only the constraints that apply to values of `typeView`. */
  private def apply(
      typeView: TypeView,
      pattern: Option[LinkmlPattern],
      minimumValue: Option[LinkmlAny],
      maximumValue: Option[LinkmlAny],
      minimumLength: Option[Int],
      maximumLength: Option[Int],
  ): ValueAlternative = {
    val runtimeType = typeView.runtimeType
    val ordered = isOrdered(runtimeType)
    val text = isText(runtimeType)
    new ValueAlternative(
      typeView,
      if (isNumberOrBoolean(runtimeType)) None else pattern,
      if (ordered) minimumValue else None,
      if (ordered) maximumValue else None,
      if (text) minimumLength else None,
      if (text) maximumLength else None,
    )
  }

  private def isNumberOrBoolean(runtimeType: RuntimeType): Boolean = runtimeType match {
    case _: IntegerType.type | _: FloatType.type | _: DoubleType.type | _: DecimalType.type |
        _: BooleanType.type =>
      true
    case _ => false
  }

  private def isOrdered(runtimeType: RuntimeType): Boolean = runtimeType match {
    case _: IntegerType.type | _: FloatType.type | _: DoubleType.type | _: DecimalType.type |
        _: DateType.type | _: DateTimeType.type | _: TimeType.type =>
      true
    case _ => false
  }

  private def isText(runtimeType: RuntimeType): Boolean = runtimeType match {
    case _: StringType.type | _: NcNameType.type | _: LocalizedTextType.type | _: UriType.type |
        _: CurieType.type | _: UriOrCurieType.type =>
      true
    case _ => false
  }
}
