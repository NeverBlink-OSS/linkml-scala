package eu.neverblink.linkml.schemaview

sealed trait AttributeView:
  def slotView: SlotView

case class AnyView(slotView: SlotView) extends AttributeView

sealed trait ClassAttributeView:
  this: AttributeView =>
  def classView: ClassView

case class ClassInlineAttributeView(
    slotView: SlotView,
    classView: ClassView,
    inlineType: InlineType,
) extends AttributeView,
      ClassAttributeView

case class ClassReferenceAttributeView(
    slotView: SlotView,
    classView: ClassView,
    identifierView: TypeView,
) extends AttributeView,
      ClassAttributeView

case class TypeAttributeView(
    slotView: SlotView,
    typeView: TypeView,
) extends AttributeView

case class EnumAttributeView(
    slotView: SlotView,
    enumView: EnumView,
) extends AttributeView
