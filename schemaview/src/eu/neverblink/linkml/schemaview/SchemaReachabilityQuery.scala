package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.FastUtils.*
import SchemaReachabilityQuery.*
import ElementTypeTag.*

import scala.collection.mutable

/** Base class for LinkML schema reachability queries. Provides information about whether metamodel
  * [[Element]]s are reachable in some way, specified by concrete implementations.
  *
  * References are found at any depth, such as ranges in nested `any_of`, `exactly_one_of`, `all_of`
  * and `none_of` lists, see [[ReferenceCollector]].
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

  /** Shared method for collecting the [[TaggedReference]]s of a slot, at any depth, and its range
    * from `default_range` when it has no range of its own.
    */
  protected def collectSlotRefs(
      slot: SlotView,
      results: mutable.ArrayBuffer[TaggedReference],
  ): Unit = {
    slotRefs.collect(slot.slot, results)
    // `range` is read above, so only the default range is left
    if (slot.slot.range eq None) slot.derivedRange.resolve.foreachFast { resolvedRange =>
      val el = resolvedRange.inner
      results.addOne((ElementTypeTag(el), el.name))
    }
  }

  /** Shared method for collecting the [[TaggedReference]]s of a type, enum or function. */
  protected def collectOtherRefs(
      current: TaggedReference,
      results: mutable.ArrayBuffer[TaggedReference],
  ): Unit = {
    val name = current.value
    val tag = current.tag
    if (tag eq typeDef) sv.types.get(name).foreachFast(tv => typeRefs.collect(tv._type, results))
    else if (tag eq enumDef)
      sv.enums.get(name).foreachFast(ev => enumRefs.collect(ev._enum, results))
    else if (tag eq functionDef) {
      sv.functions.get(name).foreachFast(fv => functionRefs.collect(fv.function, results))
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
      sv.classes.get(name).foreachFast { classView =>
        classView.derivedAttributes.values.foreach {
          // if the classes are going to be derived, then we can simply skip to the ranges of derived attributes
          case sv if !inlinedOnly || sv.derivedInlined => collectSlotRefs(sv, result)
          case _ =>
        }
        // Class-level references, such as ranges in rules, are never inlined values
        if (!inlinedOnly) derivedClassRefs.collect(classView.cls, result)
        if (includeClassAncestors) {
          classView.parents.foreach(anc => result.addOne((classDef, anc.name)))
        }
      }
    } else collectOtherRefs(current, result)
    // Derived classes have attributes instead of slots, so top-level slots are not reachable
    result.filterInPlace(_.tag ne slotDef)
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
      sv.classes.get(name).foreachFast { classView =>
        val cls = classView.cls
        underivedClassRefs.collect(cls, result)
        // An attribute without a range gets `default_range` of the defining schema. A slot usage
        // without a range keeps the range of its slot, so it is read with the class.
        val definingSchema = classView.definingSchema
        cls.attributes.foreachEntry((_, sd) =>
          collectSlotRefs(new SlotView(sd, definingSchema), result),
        )
      }
    } else if (tag eq ElementTypeTag.slotDef) {
      // A name in `unique_key_slots` or `slot_group` can be an attribute, not a top-level slot
      sv.slotDefinitions.get(name).foreachFast(slotView => collectSlotRefs(slotView, result))
    } else collectOtherRefs(current, result)
    result
}

private object SchemaReachabilityQuery {

  /** Runtime type tag for [[Element]]s
    */
  enum ElementTypeTag:
    case classDef, typeDef, slotDef, enumDef, functionDef, other

  object ElementTypeTag:
    def apply(el: Element): ElementTypeTag = el match {
      case _: ClassDefinition => classDef
      case _: TypeDefinition => typeDef
      case _: SlotDefinition => slotDef
      case _: EnumDefinition => enumDef
      case _: FunctionDefinition => functionDef
      case _ => other
    }

  /** [[Element]]'s runtime type tag and name. */
  type TaggedReference = (tag: ElementTypeTag, value: String)

  // Collectors of the references in metamodel values, at any depth
  val slotRefs: ReferenceCollector[SlotDefinition] = ReferenceCollector.derived[SlotDefinition]()
  val typeRefs: ReferenceCollector[TypeDefinition] = ReferenceCollector.derived[TypeDefinition]()
  val enumRefs: ReferenceCollector[EnumDefinition] = ReferenceCollector.derived[EnumDefinition]()
  val functionRefs: ReferenceCollector[FunctionDefinition] =
    ReferenceCollector.derived[FunctionDefinition]()

  /** For a class as it is written. Attributes are read with [[slotRefs]]. */
  val underivedClassRefs: ReferenceCollector[ClassDefinition] =
    ReferenceCollector.derived[ClassDefinition]("attributes")

  /** For a derived class, without the fields that derivation replaces with attributes, and without
    * parents, which are added only if asked for.
    */
  val derivedClassRefs: ReferenceCollector[ClassDefinition] =
    ReferenceCollector.derived[ClassDefinition](
      "is_a",
      "mixins",
      "slots",
      "attributes",
      "slot_usage",
    )
}
