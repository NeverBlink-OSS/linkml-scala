package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.SubjectType.implicitPrefix

/** ADT bundling a slot with its resolved range, handling different edge cases. Generators should
  * match on the subtypes of this trait when handling a slot.
  */
sealed trait AttributeView:
  /** The (derived) slot that this attribute view was constructed for.
    */
  def slotView: SlotView

/** Slot's range is `linkml:Any` - validator generators should emit an "accept all" schema if
  * possible.
  */
case class AnyView(slotView: SlotView) extends AttributeView

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
case class ClassInlineAttributeView(
    slotView: SlotView,
    classView: ClassView,
    inlineType: InlineType,
) extends AttributeView,
      ClassAttributeView

/** Slot's range is a reference to a class.
  *
  * @param identifierView
  *   The [[TypeAttributeView]] slot/type bundle for the class' identifier slot.
  */
case class ClassReferenceAttributeView(
    slotView: SlotView,
    classView: ClassView,
    identifierView: TypeAttributeView,
) extends AttributeView,
      ClassAttributeView

/** Slot's range is a type. Provides shorthand methods for merging shared slot/type metaslots, such
  * as [[pattern]], [[unit]], and [[implicitPrefix]], as well as [[SubjectType]] computation.
  */
case class TypeAttributeView(
    slotView: SlotView,
    typeView: TypeView,
) extends AttributeView:
  private val slot: SlotDefinition = slotView.slot
  private val _type: TypeDefinition = typeView._type

  private def upgradeToImplicit(st: SubjectType): SubjectType = slot.implicitPrefix match {
    case Some(value) =>
      SubjectType.implicitPrefix(
        slotView.definingPrefixResolver.resolvePrefix(value)
          .getOrElse(
            throw RuntimeException(s"Unknown implicit_prefix for slot ${slot.name}: $value"),
          ),
      )
    case None => st
  }

  /** Return the RDF subject type that corresponds to this type/slot combination. This is used to
    * create subjects in the RDF representations.
    */
  def subjectType: SubjectType = {
    typeView.subjectType match {
      case SubjectType.base => upgradeToImplicit(SubjectType.base)
      case SubjectType.implicitPrefix(pfx) =>
        // this probably should not be allowed
        upgradeToImplicit(SubjectType.implicitPrefix(pfx))
      case st => st
    }
  }

  /** @see [[slot.pattern]] */
  def pattern: Option[String] =
    combineOption(slot.pattern, _type.pattern, combinePattern)

  /** @see [[slot.structuredPattern]] */
  def structuredPattern: Option[PatternExpressionImpl] =
    combineOption(slot.structuredPattern, _type.structuredPattern, combineFallback)

  /** @see [[slot.unit]] */
  def unit: Option[UnitOfMeasureImpl] =
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
  def minimumValue: Option[Anything] =
    combineOption(slot.minimumValue, _type.minimumValue, combineMin)

  /** @see [[slot.maximumValue]] */
  def maximumValue: Option[Anything] =
    combineOption(slot.maximumValue, _type.maximumValue, combineMax)

/** Slot's range is an enum.
  */
case class EnumAttributeView(
    slotView: SlotView,
    enumView: EnumView,
) extends AttributeView
