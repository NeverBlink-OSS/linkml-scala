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
  *
  * [[pattern]], the value bounds and the lengths are as the slot and its type set them, even if
  * they don't apply to the values. To check values, use [[alternatives]].
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

  /** The patterns that a value must match: the ones of the slot, then the ones of its range type.
    * See [[SlotView.patterns]] and [[TypeView.patterns]].
    */
  def patterns: Seq[LinkmlPattern] =
    ValueAlternative.joinPatterns(slotView.patterns, typeView.patterns)

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
    combineOption(slot.minimumLength, _type.minimumLength, combineMinLength)

  /** @see [[slot.maximumLength]] */
  def maximumLength: Option[Int] =
    combineOption(slot.maximumLength, _type.maximumLength, combineMaxLength)

  /** The types a value of the slot can take, each with the constraints that apply to it: from the
    * slot, then from [[TypeView.alternatives]] of the range. A range that is not a union has one
    * alternative.
    */
  lazy val alternatives: Seq[ValueAlternative] =
    typeView.alternatives.map(_.withConstraintsOf(slot))

/** Slot's range is an enum.
  */
final case class EnumAttributeView(
    slotView: SlotView,
    definingClassView: ClassView,
    enumView: EnumView,
) extends AttributeView
