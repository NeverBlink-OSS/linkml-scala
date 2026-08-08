package eu.neverblink.linkml.schemaview

/** Inline type for a with commonly enforceable typing rules:
  *
  * Given a type `T`, the inlined types in Scala would be:
  *   - plain: `T`
  *   - optional: `Option[T]`
  *   - list: `Seq[T]`
  *   - dict: `Map[String, T]`
  */
enum InlineType:
  case plain, optional, list
  case dict(form: DictForm)

object InlineType {

  /** Derive an [[InlineType]] for a given slot's range using an implicit SchemaView
    */
  def apply(v: SlotView): InlineType = {
    val slot = v.slot
    if (slot.multivalued) {
      if (!slot.inlinedAsList && v.derivedInlined) {
        CollectionForm.ofRange(v) match {
          case style: DictForm => dict(style)
          case _ => list
        }
      } else list
    } else if (slot.required) plain
    else optional
  }
}
