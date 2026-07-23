package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.SlotDefinition

/** Multivalued inlining form of a class, e.g. SimpleDict */
sealed trait CollectionForm

/** Dict form of a class, excludes List from [[CollectionForm]] */
sealed trait DictForm extends CollectionForm

object CollectionForm {

  /** Signifies that a class may be inlined as a SimpleDict.
    *
    * @param key
    *   Name of the field that should be used as a dict key.
    * @param value
    *   Name of the field that should be used as a dict value.
    */
  case class SimpleDict(key: String, value: String) extends DictForm

  /** Signifies that a class may be inlined as a CompactDict.
    *
    * @param key
    *   Name of the field that should be used as a dict key.
    */
  case class CompactDict(key: String) extends DictForm

  /** Signifies that a class must be inlined as a list. */
  case object ListOnly extends CollectionForm

  /** Infer the possible collection forms of a class with given attributes, and record which slots
    * can be used to inline the class if the form is a [[DictForm]].
    *
    * @param classView
    *   ClassView to infer the collection form for
    * @return
    *   The [[CollectionForm]] applicable for this specific class
    */
  def of(classView: ClassView): CollectionForm = {
    val slots = classView.derivedAttributes.values.map(_.slot).toSeq

    if !slots.exists(isIdOrKey) then return ListOnly
    val key = slots.filter(isIdOrKey).head
    if slots.size == 2 then {
      val value = slots.find(_.name != key.name).get
      return SimpleDict(key.name, value.name)
    }
    if slots.count(_.required) == 2 then {
      val value = slots.find(slot => slot.name != key.name && slot.required).get
      return SimpleDict(key.name, value.name)
    }
    CompactDict(key.name)
  }

  /** Infer the possible collection forms of a slot's range if it is a class, or a fallback
    * [[ListOnly]]
    *
    * @return
    *   The [[CollectionForm]] applicable for the slot's range
    */
  def ofRange(slot: SlotView): CollectionForm = {
    val range = slot.derivedRangeView
    given SchemaView = slot.sv
    range.resolve.get match {
      case cls: ClassView => of(cls)
      case _ =>
        // Let's be lax here, `inlined:true` does not make sense on non-classes,
        // since enum/types are already always inlined and the form is always list
        // TODO LNK-27: But we should have this as a warning if possible
        ListOnly
    }

  }

  private def isIdOrKey(slot: SlotDefinition): Boolean = slot.key || slot.identifier
}
