package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview.SubjectType.implicitPrefix
import eu.neverblink.linkml.schemaview.expression.StringInterpolationExpression
import fastparse.Parsed

/** ADT bundling a slot with its resolved range, handling different edge cases. Generators should
  * match on the subtypes of this trait when handling a slot.
  */
sealed trait AttributeView:
  /** The (derived) slot that this attribute view was constructed for.
    */
  def slotView: SlotView

  /** The class that defines this derived attribute.
    */
  def definingClassView: ClassView

  /** Returns the parsed abstract syntax tree of the slot's `equals_expression`, if any. This is
    * used to generate code that computes the value of this slot from other slots in the same class.
    *
    * Currently, only string interpolation expressions are supported. If the slot's
    * `equals_expression` is not a string interpolation expression, this method will return a
    * parsing failure.
    */
  final def equalsExpression: Option[Parsed[StringInterpolationExpression]] =
    slotView.slot.equalsExpression.map { expr =>
      StringInterpolationExpression.parse(expr)(using this)
    }

  /** One value of this slot per range that its `any_of` and `exactly_one_of` lists allow, from all
    * levels of inheritance. Other constraints of the members are ignored.
    */
  final lazy val rangeAlternatives: Seq[AttributeView] =
    AttributeView.rangesOf(slotView.slot, false)(using slotView.sv)
      .foldFast(Nil)(_.map(singleValue))

  /** One value of this slot, with another range. */
  final def singleValue(range: Reference[Element]): AttributeView = {
    val slot = slotView.slot
    val single = slot.copy(
      range = new Some(range),
      multivalued = false,
      inlined = slot.inlined || slot.inlinedAsList,
      inlinedAsList = false,
      anyOf = Nil,
      exactlyOneOf = Nil,
      noneOf = Nil,
      allOf = Nil,
      minimumCardinality = None,
      maximumCardinality = None,
      exactCardinality = None,
    )
    definingClassView.attributeViewOf(SlotView(single, slotView.definingSchema)(using slotView.sv))
  }

private object AttributeView:
  private type Ranges = Option[Seq[Reference[Element]]]

  /** The ranges allowed by an expression, or None if it doesn't limit them.
    *
    * @param withOwn
    *   Whether the expression's own `range` counts
    */
  def rangesOf(expression: SlotExpression, withOwn: Boolean)(using SchemaView): Ranges = {
    var result: Ranges = if withOwn then expression.range.mapFast(_ :: Nil) else None
    def narrow(other: Ranges): Unit = other.foreachFast { o =>
      result = new Some(result.foldFast(o)(intersect(_, o)))
    }
    narrow(unionOf(expression.anyOf))
    narrow(unionOf(expression.exactlyOneOf))
    expression.allOf.foreach(m => narrow(rangesOf(m, true)))
    result.filter(_.nonEmpty)
  }

  /** The ranges allowed by any of the members, or None if a member doesn't limit them. */
  private def unionOf(members: Seq[SlotExpression])(using SchemaView): Ranges =
    if members.isEmpty then None
    else {
      val each = members.map(rangesOf(_, true))
      if each.exists(_.isEmpty) then None else new Some(each.flatMap(_.get).distinctBy(_.value))
    }

  /** Ranges in both, where a descendant counts as being in its ancestor. */
  private def intersect(a: Seq[Reference[Element]], b: Seq[Reference[Element]])(using
      sv: SchemaView,
  ): Seq[Reference[Element]] = {
    def within(x: Reference[Element], y: Reference[Element]): Boolean =
      x.value == y.value ||
        sv.classes.get(x.value).exists(_.ancestorsWithSelf.exists(_.name == y.value)) ||
        sv.types.get(x.value).exists(_.ancestorsWithSelf.exists(_.name == y.value))
    a.filter(x => b.exists(within(x, _))) ++
      b.filter(y => !a.exists(_.value == y.value) && a.exists(within(y, _)))
  }

/** Slot's range is `linkml:Any` - validator generators should emit an "accept all" schema if
  * possible.
  */
final case class AnyView(slotView: SlotView, definingClassView: ClassView) extends AttributeView

/** Slot's range is an inlined class or a reference to a class. Generators for formats without
  * inlining should match on this instead of its subtypes.
  */
sealed trait ClassAttributeView:
  // Mixin so IJ generates a match over the leaves by default
  this: AttributeView =>

  /** This attribute's range - a class.
    */
  def classView: ClassView

/** Slot's range is an inlined class.
  *
  * @param inlineType
  *   The inline type of this slot/class combination
  */
final case class ClassInlineAttributeView(
    slotView: SlotView,
    definingClassView: ClassView,
    classView: ClassView,
    inlineType: InlineType,
) extends AttributeView,
      ClassAttributeView

/** Slot's range is a reference to a class.
  *
  * @param identifierView
  *   The [[TypeAttributeView]] slot/type bundle for the class' identifier slot.
  */
final case class ClassReferenceAttributeView(
    slotView: SlotView,
    definingClassView: ClassView,
    classView: ClassView,
    identifierView: TypeAttributeView,
) extends AttributeView,
      ClassAttributeView

/** Slot's range is a type. Provides shorthand methods for merging shared slot/type metaslots, such
  * as [[pattern]], [[unit]], and [[implicitPrefix]], as well as [[SubjectType]] computation.
  */
final case class TypeAttributeView(
    slotView: SlotView,
    definingClassView: ClassView,
    typeView: TypeView,
) extends AttributeView:
  private val slot: SlotDefinition = slotView.slot
  private val _type: TypeDefinition = typeView.derivedType

  private def upgradeToImplicit(st: SubjectType): SubjectType = slot.implicitPrefix.foldFast(st) {
    value =>
      val reference = slotView.definingPrefixes.namespaceOrNull(value)
      if (reference eq null) {
        throw RuntimeException(s"Unknown implicit_prefix for slot ${slot.name}: $value")
      }
      SubjectType.implicitPrefix(reference)
  }

  /** Return the RDF subject type that corresponds to this type/slot combination. This is used to
    * create subjects in the RDF representations.
    */
  def subjectType: SubjectType =
    typeView.subjectType match {
      case _: SubjectType.base.type => upgradeToImplicit(SubjectType.base)
      case ip: SubjectType.implicitPrefix =>
        // this probably should not be allowed
        upgradeToImplicit(new SubjectType.implicitPrefix(ip.prefix))
      case st => st
    }

  /** @see [[slot.pattern]] */
  def pattern: Option[LinkmlPattern] =
    LinkmlPattern.option(combineOption(slot.pattern, _type.pattern, combinePattern))

  /** @see [[slot.structuredPattern]] */
  def structuredPattern: Option[PatternExpression] =
    combineOption(slot.structuredPattern, _type.structuredPattern, combineFallback)

  /** @see [[slot.unit]] */
  def unit: Option[UnitOfMeasure] =
    combineOption(slot.unit, _type.unit, combineFallback)

  /** @see [[slot.equalsString]] */
  def equalsString: Option[String] =
    combineOption(slot.equalsString, _type.equalsString, combineFallback)

  /** @see [[slot.equalsStringIn]] */
  def equalsStringIn: Seq[String] =
    combineSeq(slot.equalsStringIn, _type.equalsStringIn)

  /** @see [[slot.equalsNumber]] */
  def equalsNumber: Option[Int] =
    combineOption(slot.equalsNumber, _type.equalsNumber, combineFallback)

  /** @see [[slot.implicitPrefix]] */
  def implicitPrefix: Option[String] =
    combineOption(slot.implicitPrefix, _type.implicitPrefix, combineFallback)

  /** @see [[slot.minimumValue]] */
  def minimumValue: Option[LinkmlAny] =
    combineOption(slot.minimumValue, _type.minimumValue, combineMin)

  /** @see [[slot.maximumValue]] */
  def maximumValue: Option[LinkmlAny] =
    combineOption(slot.maximumValue, _type.maximumValue, combineMax)

  /** @see [[slot.minimumLength]] */
  def minimumLength: Option[Int] =
    combineOption(slot.minimumLength, _type.minimumLength, combineFallback)

  /** @see [[slot.maximumLength]] */
  def maximumLength: Option[Int] =
    combineOption(slot.maximumLength, _type.maximumLength, combineFallback)

/** Slot's range is an enum.
  */
final case class EnumAttributeView(
    slotView: SlotView,
    definingClassView: ClassView,
    enumView: EnumView,
) extends AttributeView
