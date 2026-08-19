package eu.neverblink.linkml.schemaview

/** Multivalued inlining form of a class, e.g. SimpleDict */
sealed trait CollectionForm

/** Dict form of a class, excludes List from [[CollectionForm]] */
sealed trait DictForm extends CollectionForm:
  def key: String

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
    val slots = classView.derivedAttributes.values
    slots.find(sv => {
      val slot = sv.slot
      slot.key || slot.identifier
    }) match {
      case Some(key) =>
        val keyName = key.name
        if (slots.size == 2) {
          val value = slots.find(_.name != keyName).get
          new SimpleDict(keyName, value.name)
        } else if slots.count(_.slot.required) == 2 then {
          val value = slots.find(slot => slot.name != keyName && slot.slot.required).get
          new SimpleDict(keyName, value.name)
        } else new CompactDict(keyName)
      case _ => ListOnly
    }
  }

  /** Infer the possible collection forms of a slot's range if it is a class, or a fallback
    * [[ListOnly]]
    *
    * @return
    *   The [[CollectionForm]] applicable for the slot's range
    */
  def ofRange(slot: SlotView): CollectionForm =
    slot.derivedRange.resolve(using slot.sv).get match {
      case cls: ClassView => of(cls)
      case _ =>
        // Let's be lax here, `inlined:true` does not make sense on non-classes,
        // since enum/types are already always inlined and the form is always list
        // TODO LNK-27: But we should have this as a warning if possible
        ListOnly
    }
}
