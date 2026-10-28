package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{
  AnonymousSlotExpression,
  AnonymousTypeExpression,
  SlotExpression,
  TypeExpression,
}
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.runtime.FastUtils.*

import scala.collection.mutable

/** One type that a value can take, with the constraints that apply to values of that type.
  *
  * A slot or a type whose range is a `union_of` type has one alternative per member, with nested
  * unions flattened. Any other slot or type has one alternative. See [[TypeView.alternatives]] and
  * [[TypeAttributeView.alternatives]].
  *
  * The constraints come from the slot, then each union on the way to the member, then the member
  * type:
  *   - all value bounds hold: the largest `minimum_value` and the smallest `maximum_value`;
  *   - all lengths hold: the largest `minimum_length` and the smallest `maximum_length`;
  *   - all patterns hold, so [[patterns]] lists each of them once, nearest first.
  *
  * A constraint is kept only if it applies to values of [[typeView]]:
  *   - `pattern` applies to anything but numbers and booleans;
  *   - `minimum_value` and `maximum_value` apply to numbers, dates and times;
  *   - `minimum_length` and `maximum_length` apply to text: strings, NCNames, localized text, URIs
  *     and CURIEs.
  *
  * @param typeView
  *   The type of the values, never a union
  * @param patterns
  *   The patterns that a value must match, nearest first
  */
final case class ValueAlternative private (
    typeView: TypeView,
    patterns: Seq[LinkmlPattern],
    minimumValue: Option[LinkmlAny],
    maximumValue: Option[LinkmlAny],
    minimumLength: Option[Int],
    maximumLength: Option[Int],
) {

  /** Whether the alternative has any constraints. */
  def hasConstraints: Boolean =
    patterns.nonEmpty || minimumValue.isDefined || maximumValue.isDefined ||
      minimumLength.isDefined || maximumLength.isDefined

  /** This alternative with the constraints of a slot or a slot expression, which are nearer than
    * the ones it has. The patterns also come from `all_of`, see [[ValueAlternative.patternsOf]].
    */
  def withConstraintsOf(expression: SlotExpression): ValueAlternative =
    withConstraints(ValueAlternative.patternsOf(expression), expression)

  /** Like [[withConstraintsOf]], but without the patterns of `all_of`. For a generator that writes
    * `all_of` itself.
    */
  def withOwnConstraintsOf(expression: SlotExpression): ValueAlternative =
    withConstraints(expression.pattern.foldFast(Nil)(p => LinkmlPattern(p) :: Nil), expression)

  /** This alternative with the constraints of a union type, which are nearer than the ones it has.
    */
  private[schemaview] def withConstraintsOfUnion(union: TypeView): ValueAlternative = {
    val d = union.derivedType
    withConstraints(
      union.patterns,
      d.minimumValue,
      d.maximumValue,
      d.minimumLength,
      d.maximumLength,
    )
  }

  private def withConstraints(
      patterns: Seq[LinkmlPattern],
      expression: SlotExpression,
  ): ValueAlternative =
    withConstraints(
      patterns,
      expression.minimumValue,
      expression.maximumValue,
      expression.minimumLength,
      expression.maximumLength,
    )

  private def withConstraints(
      patterns: Seq[LinkmlPattern],
      minimumValue: Option[LinkmlAny],
      maximumValue: Option[LinkmlAny],
      minimumLength: Option[Int],
      maximumLength: Option[Int],
  ): ValueAlternative =
    ValueAlternative(
      typeView,
      ValueAlternative.joinPatterns(patterns, this.patterns),
      combineOption(minimumValue, this.minimumValue, combineMin),
      combineOption(maximumValue, this.maximumValue, combineMax),
      combineOption(minimumLength, this.minimumLength, combineMinLength),
      combineOption(maximumLength, this.maximumLength, combineMaxLength),
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
      typeView.patterns,
      d.minimumValue,
      d.maximumValue,
      d.minimumLength,
      d.maximumLength,
    )
  }

  /** The patterns of a slot or a slot expression: its own `pattern`, then the ones of the `all_of`
    * members that set nothing but a `pattern`. A value must match all of them. A derived slot keeps
    * the patterns of its parents this way, as it has only one `pattern`.
    */
  def patternsOf(expression: SlotExpression): Seq[LinkmlPattern] =
    patternsOf(
      expression.pattern,
      expression.allOf,
      m => if (m == AnonymousSlotExpression(pattern = m.pattern)) m.pattern else None,
    )

  /** The patterns of a type, the same way as for a slot. */
  def patternsOf(expression: TypeExpression): Seq[LinkmlPattern] =
    patternsOf(
      expression.pattern,
      expression.allOf,
      m => if (m == AnonymousTypeExpression(pattern = m.pattern)) m.pattern else None,
    )

  /** @param onlyPattern
    *   The pattern of an `all_of` member, if it sets nothing else
    */
  private def patternsOf[T](
      pattern: Option[String],
      allOf: Seq[T],
      onlyPattern: T => Option[String],
  ): Seq[LinkmlPattern] =
    if (allOf.isEmpty) pattern.foldFast(Nil)(p => LinkmlPattern(p) :: Nil)
    else {
      val b = Vector.newBuilder[LinkmlPattern]
      val seen = mutable.HashSet.empty[String]
      pattern.foreachFast(p => if (seen.add(p)) b.addOne(LinkmlPattern(p)))
      allOf.foreach(m =>
        onlyPattern(m).foreachFast(p => if (seen.add(p)) b.addOne(LinkmlPattern(p))),
      )
      b.result()
    }

  /** The patterns of `nearer`, then the ones of `farther` that are not in `nearer`. */
  private[schemaview] def joinPatterns(
      nearer: Seq[LinkmlPattern],
      farther: Seq[LinkmlPattern],
  ): Seq[LinkmlPattern] =
    if (farther.isEmpty) nearer
    else if (nearer.isEmpty) farther
    else {
      val b = Vector.newBuilder[LinkmlPattern]
      b.addAll(nearer)
      farther.foreach(p => if (!nearer.contains(p)) b.addOne(p))
      b.result()
    }

  /** Keeps only the constraints that apply to values of `typeView`. */
  private def apply(
      typeView: TypeView,
      patterns: Seq[LinkmlPattern],
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
      if (isNumberOrBoolean(runtimeType)) Nil else patterns,
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
