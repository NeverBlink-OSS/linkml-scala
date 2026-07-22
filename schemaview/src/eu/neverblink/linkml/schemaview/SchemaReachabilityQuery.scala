package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import SchemaReachabilityQuery.*
import ElementTypeTag.*

/** Base class for LinkML schema reachability queries. Provides information about whether metamodel
  * [[Element]]s should are reachable in some way, specified by concrete implementations.
  * @todo
  *   Make this search more robust (LNK-110). Currently, this will prune things incorrectly if there
  *   are any complex boolean slots (like `exactly_one_of`)
  */
sealed abstract class SchemaReachabilityQuery(using sv: SchemaView) {

  /** @return
    *   true if the provided [[Element]] is reachable
    */
  def reachable(element: Element): Boolean =
    resolved.contains(ElementTypeTag(element) -> element.name)

  /** @return
    *   true if the underlying [[Element]] of the provided [[ElementView]] is reachable
    */
  def reachable(element: ElementView[?]): Boolean =
    reachable(element.inner.asInstanceOf[Element])

  /** Lazily computed set of [[TaggedReference]]s that are reachable for the default implementation
    * of [[reachable()]]
    */
  protected lazy val resolved: Set[TaggedReference]

  /** Shared method for computing the [[TaggedReference]]s for a slot's range/domains.
    */
  protected def slotRefs(slot: SlotView): Iterable[TaggedReference] = {
    val booleanSlots = slot.slot.anyOf.flatMap(_.range.flatMap(_.resolve))
    val mainRange: Option[Element] = slot.derivedRangeView.resolve.map(_.inner)
    val blep = slot.slot.domain.flatMap(_.resolve)
    (booleanSlots ++ mainRange ++ blep).map(el => ElementTypeTag(el) -> el.name)
  }
}

/** Reachability query that simply returns true for all elements.
  */
final class IncludeAllReachabilityQuery(using SchemaView) extends SchemaReachabilityQuery {
  override def reachable(element: Element): Boolean = true
  override def reachable(element: ElementView[?]): Boolean = true
  protected lazy val resolved: Set[TaggedReference] = Set.empty
}

/** Reachability query for a derived schema (resolved inheritance, all slots inlined to class
  * definitions).
  * @param from
  *   [[Element]]s to start the search from
  * @param inlinedOnly
  *   If true, will exclude by-reference classes when performing the query
  */
final class DerivedReachabilityQuery(
    val from: Seq[ElementView[?]],
    val inlinedOnly: Boolean,
)(using sv: SchemaView)
    extends SchemaReachabilityQuery {

  protected lazy val resolved: Set[TaggedReference] = {
    val start: Seq[TaggedReference] = from.map(ev => ElementTypeTag(ev.inner) -> ev.inner.name)
    Closure.reflexive(start, walk(inlinedOnly)).toSet
  }

  private def walk(
      inlinedOnly: Boolean,
  )(current: TaggedReference): Iterable[TaggedReference] = {
    val (tag, name) = current
    val res: Iterable[TaggedReference] = tag match {
      case ElementTypeTag.classDef =>
        val classView = sv.classes(name)
        classView.derivedAttributes.values.flatMap {
          // if the classes are going to be derived, then we can simply skip to the ranges of derived attributes
          case s if !inlinedOnly || s.derivedInlined => slotRefs(s)
          case _ => None
        }
      case ElementTypeTag.typeDef =>
        val typeView = sv.types(name)
        (typeView._type.typeof ++ typeView._type.unionOf)
          .flatMap(_.resolve)
          .map(typeDef -> _.name)
      case ElementTypeTag.slotDef =>
        None // this should not happen
      case ElementTypeTag.enumDef =>
        sv.enums(name)._enum.inherits.flatMap(_.resolve).map(enumDef -> _.name)
      case _ => None
    }
    res
  }
}

/** Reachability query for an underived schema (unresolved inheritance, slots not inlined).
  * @param from
  *   [[Element]]s to start the search from
  */
final class UnderivedReachabilityQuery(
    val from: Seq[ElementView[?]],
)(using sv: SchemaView)
    extends SchemaReachabilityQuery {

  protected lazy val resolved: Set[TaggedReference] = {
    val start: Seq[TaggedReference] = from.map(ev => ElementTypeTag(ev.inner) -> ev.inner.name)
    Closure.reflexive(start, walk).toSet
  }

  private def walk(current: TaggedReference): Iterable[TaggedReference] =
    val (typeTag, name) = current
    typeTag match {
      case ElementTypeTag.classDef =>
        val classView = sv.classes(name)
        val inheritance = classView.ancestors(reflexive = false).map(classDef -> _.cls.name)
        val referencedSlots = classView.cls.slots.map(slotDef -> _.value)
        // The *ranges* of class-defined slots (attributes, slot_usage)
        val classDefinedSlots =
          (classView.cls.attributes.values ++ classView.cls.slotUsage.values)
            .flatMap(sd => slotRefs(SlotView(sd, classView.definingSchema)))
        inheritance ++ referencedSlots ++ classDefinedSlots
      case ElementTypeTag.typeDef =>
        val typeView = sv.types(name)
        (typeView._type.typeof ++ typeView._type.unionOf)
          .flatMap(_.resolve)
          .map(typeDef -> _.name)
      case ElementTypeTag.slotDef =>
        val slotView = sv.slotDefinitions(name)
        val inheritance = slotView.slot.isA ++ slotView.slot.mixins
        val ranges = slotRefs(slotView)
        inheritance
          .flatMap(_.resolve)
          .map(el => ElementTypeTag(el) -> el.name) ++ ranges
      case ElementTypeTag.enumDef =>
        sv.enums(name)._enum.inherits.flatMap(_.resolve).map(enumDef -> _.name)
      case _ => None
    }
}

private object SchemaReachabilityQuery {

  /** Runtime type tag for [[Element]]s
    */
  enum ElementTypeTag:
    case classDef, typeDef, slotDef, enumDef, other

  object ElementTypeTag:
    def apply(el: Element): ElementTypeTag = el match {
      case _: ClassDefinition => classDef
      case _: TypeDefinition => typeDef
      case _: SlotDefinition => slotDef
      case _: EnumDefinition => enumDef
      case _ => other
    }

  /** [[Element]]'s runtime type tag and name. */
  type TaggedReference = (tag: ElementTypeTag, value: String)
}
