package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.FastUtils.*
import SchemaReachabilityQuery.*
import ElementTypeTag.*

import scala.collection.mutable

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
    resolved.contains((ElementTypeTag(element), element.name))

  /** @return
    *   true if the underlying [[Element]] of the provided [[ElementView]] is reachable
    */
  def reachable(element: ElementView[?, ?]): Boolean =
    reachable(element.inner.asInstanceOf[Element])

  /** Lazily computed set of [[TaggedReference]]s that are reachable for the default implementation
    * of [[reachable()]]
    */
  protected def resolved: Set[TaggedReference]

  /** Shared method for collecting the [[TaggedReference]]s for a slot's range/domains.
    */
  protected def collectSlotRefs(
      slot: SlotView,
      results: mutable.ArrayBuffer[TaggedReference],
  ): Unit = {
    slot.slot.anyOf.foreach { anyOfNode =>
      anyOfNode.range.foreachFast { rangeNode =>
        rangeNode.resolve.foreachFast(el => results.addOne((ElementTypeTag(el), el.name)))
      }
    }
    slot.derivedRange.resolve.foreachFast { resolvedRange =>
      val el = resolvedRange.inner
      results.addOne((ElementTypeTag(el), el.name))
    }
    slot.slot.domain.foreachFast { domainNode =>
      domainNode.resolve.foreachFast(el => results.addOne((ElementTypeTag(el), el.name)))
    }
  }
}

/** Reachability query that simply returns true for all elements.
  */
final class IncludeAllReachabilityQuery(using SchemaView) extends SchemaReachabilityQuery {
  override def reachable(element: Element): Boolean = true
  override def reachable(element: ElementView[?, ?]): Boolean = true
  protected val resolved: Set[TaggedReference] = Set.empty
}

/** Reachability query for a derived schema (resolved inheritance, all slots inlined to class
  * definitions).
  * @param from
  *   [[Element]]s to start the search from
  * @param inlinedOnly
  *   If true, will exclude by-reference class ranges when computing reachability.
  * @param includeClassAncestors
  *   If true, will include class' ancestors when computing reachability.
  */
final class DerivedReachabilityQuery(
    val from: Seq[ElementView[?, ?]],
    val inlinedOnly: Boolean,
    val includeClassAncestors: Boolean,
)(using sv: SchemaView)
    extends SchemaReachabilityQuery {

  protected lazy val resolved: Set[TaggedReference] = Closure.get(
    start = from.map { ev =>
      val el = ev.inner
      (ElementTypeTag(el), el.name)
    },
    function = walk,
    reflexive = true,
    resultBuilder = Set.newBuilder[TaggedReference],
    useHashCode = true,
  )

  private def walk(current: TaggedReference): Iterable[TaggedReference] = {
    val result = new mutable.ArrayBuffer[TaggedReference]
    val name = current.value
    val tag = current.tag
    if (tag eq ElementTypeTag.classDef) {
      val classView = sv.classes(name)
      classView.derivedAttributes.values.foreach {
        // if the classes are going to be derived, then we can simply skip to the ranges of derived attributes
        case sv if !inlinedOnly || sv.derivedInlined => collectSlotRefs(sv, result)
        case _ =>
      }
      if (includeClassAncestors) {
        classView.parents.foreach(anc => result.addOne((classDef, anc.name)))
      }
    } else if (tag eq ElementTypeTag.typeDef) {
      val type_ = sv.types(name)._type
      type_.typeof.foreach { tr =>
        tr.resolve.foreachFast(td => result.addOne((typeDef, td.name)))
      }
      type_.unionOf.foreach { tr =>
        tr.resolve.foreachFast(td => result.addOne((typeDef, td.name)))
      }
    } else if (tag eq ElementTypeTag.enumDef) {
      sv.enums(name)._enum.inherits.foreach { er =>
        er.resolve.foreachFast(ed => result.addOne((enumDef, ed.name)))
      }
    }
    result
  }
}

/** Reachability query for an underived schema (unresolved inheritance, slots not inlined).
  * @param from
  *   [[Element]]s to start the search from
  */
final class UnderivedReachabilityQuery(
    val from: Seq[ElementView[?, ?]],
)(using sv: SchemaView)
    extends SchemaReachabilityQuery {

  protected lazy val resolved: Set[TaggedReference] = Closure.get(
    start = from.map { ev =>
      val el = ev.inner
      (ElementTypeTag(el), el.name)
    },
    function = walk,
    reflexive = true,
    resultBuilder = Set.newBuilder[TaggedReference],
    useHashCode = true,
  )

  private def walk(current: TaggedReference): Iterable[TaggedReference] =
    val result = new mutable.ArrayBuffer[TaggedReference]
    val name = current.value
    val tag = current.tag
    if (tag eq ElementTypeTag.classDef) {
      val classView = sv.classes(name)
      classView.ancestorsWithSelf.foreach {
        var i = 0
        anc =>
          if (i > 0) result.addOne((classDef, anc.cls.name))
          i += 1
      }
      val cls = classView.cls
      cls.slots.foreach(sr => result.addOne((slotDef, sr.value)))
      // The *ranges* of class-defined slots (attributes, slot_usage)
      val definingSchema = classView.definingSchema
      cls.attributes.values.foreach { sd =>
        collectSlotRefs(new SlotView(sd, definingSchema), result)
      }
      cls.slotUsage.values.foreach { sd =>
        collectSlotRefs(new SlotView(sd, definingSchema), result)
      }
    } else if (tag eq ElementTypeTag.typeDef) {
      val type_ = sv.types(name)._type
      type_.typeof.foreach { tr =>
        tr.resolve.foreachFast(td => result.addOne((typeDef, td.name)))
      }
      type_.unionOf.foreach { tr =>
        tr.resolve.foreachFast(td => result.addOne((typeDef, td.name)))
      }
    } else if (tag eq ElementTypeTag.slotDef) {
      val slotView = sv.slotDefinitions(name)
      slotView.slot.isA.foreach { sr =>
        sr.resolve.foreachFast(sd => result.addOne((slotDef, sd.name)))
      }
      slotView.slot.mixins.foreach { sr =>
        sr.resolve.foreachFast(sd => result.addOne((slotDef, sd.name)))
      }
      collectSlotRefs(slotView, result)
    } else if (tag eq ElementTypeTag.enumDef) {
      sv.enums(name)._enum.inherits.foreach { er =>
        er.resolve.foreachFast(ed => result.addOne((enumDef, ed.name)))
      }
    }
    result
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
