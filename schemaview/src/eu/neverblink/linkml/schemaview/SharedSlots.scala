package eu.neverblink.linkml.schemaview

import scala.collection.mutable

/** Tells which slots a class can describe once for all its descendants.
  *
  * In many formats a subclass inherits what its parent says about a field: the fields of a GraphQL
  * interface, the `def`s of a Scala abstract class, the restrictions of an OWL class. The subclass
  * can only narrow such a field in the ways the format allows. A `slot_usage` can change a slot in
  * other ways, for example make it multivalued, or give it another alias or `slot_uri`. Then the
  * parent must not describe the slot, and the descendants describe their own versions instead.
  *
  * @param compatible
  *   Whether a descendant's version of a slot (the second argument) fits what the format passes
  *   down from an ancestor's version (the first argument). It is not called when both versions are
  *   the same slot.
  */
final class SharedSlots(compatible: (AttributeView, AttributeView) => Boolean) {

  /** The names of the slots of each class that some descendant does not share, by class name. */
  private val unshared = mutable.HashMap.empty[String, collection.Set[String]]

  /** Whether every descendant of the class has a version of the slot that fits the class's own. */
  def isShared(cls: ClassView, slotName: String): Boolean = !unsharedOf(cls).contains(slotName)

  /** Whether the class should describe the slot. That is when all its descendants share its version
    * of the slot, and either the class lists or changes the slot itself, or a parent with the slot
    * does not share it, so does not describe it.
    */
  def describes(cls: ClassView, slotName: String): Boolean =
    isShared(cls, slotName) && (
      cls.cls.slotUsage.contains(slotName) || cls.cls.attributes.contains(slotName) ||
        cls.cls.slots.exists(_.value == slotName) ||
        cls.parents.exists(p => p.derivedAttributes.contains(slotName) && !isShared(p, slotName))
    )

  /** The slots of the class that some descendant does not share. A slot that a child does not share
    * with its own descendants is not shared with the class either, which is simpler than comparing
    * the class with each descendant, and errs on the side of describing the slot lower.
    */
  private def unsharedOf(cls: ClassView): collection.Set[String] = {
    val known = unshared.getOrElse(cls.name, null)
    if (known ne null) known
    else {
      // Stops the recursion if the classes have an inheritance cycle
      unshared.update(cls.name, Set.empty)
      var result: mutable.HashSet[String] = null
      val own = cls.attributeViews
      cls.children.foreach { child =>
        val below = unsharedOf(child)
        val theirs = child.attributeViews
        own.foreachEntry { (name, mine) =>
          val their = theirs.getOrElse(name, null)
          if (
            (their ne null) && (below.contains(name) ||
              (their.slotView ne mine.slotView) && !compatible(mine, their))
          ) {
            if (result eq null) result = mutable.HashSet.empty[String]
            result.addOne(name)
          }
        }
      }
      val names = if (result eq null) Set.empty[String] else result
      unshared.update(cls.name, names)
      names
    }
  }
}
