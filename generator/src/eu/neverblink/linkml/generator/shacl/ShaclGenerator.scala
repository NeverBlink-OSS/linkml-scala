package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.RdfGeneratorBase
import eu.neverblink.linkml.generator.RdfGeneratorBase.{RdfFormat, RdfOptions}
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.metamodel.SlotExpression
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

class ShaclGenerator(using sv: SchemaView) extends RdfGeneratorBase[ShaclGenerator.Options] {

  override protected def defaultOptions: ShaclGenerator.Options = ShaclGenerator.Options()

  /** Emit the slot's own range.
    *
    * Skipped if an `any_of` or `exactly_one_of` member sets a range: the members then list the
    * allowed ranges (LinkML-Python does the same for `any_of`). Otherwise a slot without `range`
    * would get e.g. `sh:datatype xsd:string` next to `sh:or ( [ sh:class A ] ... )`, which no value
    * can pass.
    *
    * The default range is also skipped if an `all_of` member sets a range.
    *
    * @param slotView
    *   The defining slot's view, used to find the default range
    */
  private def processMainRange(
      sink: RdfSink,
      slotView: SlotView,
      slot: SlotExpression,
      subject: Resource,
  ): Unit =
    if (!slot.anyOf.exists(_.range.isDefined) && !slot.exactlyOneOf.exists(_.range.isDefined))
      slot.range match {
        case Some(r) => processRange(sink, r, slot.implicitPrefix.isDefined, subject)
        case _ =>
          if (!slot.allOf.exists(_.range.isDefined))
            processRange(
              sink,
              sv.getDefaultRange(slotView.definingSchema),
              slot.implicitPrefix.isDefined,
              subject,
            )
      }

  /** Emit `sh:datatype`, `sh:class` or `sh:in` for a range.
    *
    * @param isIri
    *   Whether values are IRIs even for a literal type (e.g. with `implicit_prefix`)
    */
  private def processRange(
      sink: RdfSink,
      range: Reference[?],
      isIri: Boolean,
      subject: Resource,
  ): Unit =
    range.asInstanceOf[Reference[ElementView[?, ?]]].resolve.foreachFast {
      case typeView: TypeView =>
        if (!typeView.isIri && !isIri) {
          sink.triple(subject, Shacl.datatype, Iri(typeView.uriStr))
          sink.triple(subject, Shacl.nodeKind, Shacl.Literal)
        } else sink.triple(subject, Shacl.nodeKind, Shacl.IRI)
      case classView: ClassView =>
        if (!classView.isAny) {
          sink.triple(subject, Shacl.`class`, Iri(classView.uriStr))
          sink.triple(subject, Shacl.nodeKind, Shacl.BlankNodeOrIRI)
        }
      case enumView: EnumView =>
        val permissibleValues =
          enumView.derivedValues.map { value =>
            Iri(value.meaning.uri(using enumView.definingPrefixResolver))
          }
        sink.list(subject, Shacl.in, permissibleValues)
      case _ => throw RuntimeException(s"Couldn't map range $range")
    }

  /** Emit `sh:minCount` and `sh:maxCount`. Cardinality metaslots win over `required`.
    *
    * @param impliedMax
    *   Max count to use if no cardinality metaslot sets one
    */
  private def processCardinality(
      sink: RdfSink,
      expression: SlotExpression,
      impliedMax: Option[Int],
      subject: Resource,
  ): Unit = {
    expression.minimumCardinality
      .orElseFast(expression.exactCardinality)
      .orElseFast(if (expression.required) ShaclGenerator.one else None)
      .foreachFast { c =>
        sink.triple(subject, Shacl.minCount, Literal(c.toString, XmlSchema.integer))
      }
    expression.maximumCardinality
      .orElseFast(expression.exactCardinality)
      .orElseFast(impliedMax)
      .foreachFast { c =>
        sink.triple(subject, Shacl.maxCount, Literal(c.toString, XmlSchema.integer))
      }
  }

  /** Whether the expression or anything nested in it limits the number of values. `multivalued` is
    * ignored, as an explicit `false` looks the same as the default.
    */
  private def hasCardinality(expression: SlotExpression): Boolean =
    expression.required || expression.minimumCardinality.isDefined ||
      expression.maximumCardinality.isDefined || expression.exactCardinality.isDefined ||
      perInstanceSlots(expression) != 0

  /** The boolean slots that have members, as a bit set of [[ShaclGenerator.AnyOf]] etc. */
  private def booleanSlots(expression: SlotExpression): Int =
    (if (expression.anyOf.nonEmpty) ShaclGenerator.AnyOf else 0) |
      (if (expression.exactlyOneOf.nonEmpty) ShaclGenerator.ExactlyOneOf else 0) |
      (if (expression.allOf.nonEmpty) ShaclGenerator.AllOf else 0) |
      (if (expression.noneOf.nonEmpty) ShaclGenerator.NoneOf else 0)

  /** The boolean slots checked per instance (a member limits the number of values), as a bit set.
    * See [[processBooleanSlots]].
    */
  private def perInstanceSlots(expression: SlotExpression): Int =
    (if (expression.anyOf.exists(hasCardinality)) ShaclGenerator.AnyOf else 0) |
      (if (expression.exactlyOneOf.exists(hasCardinality)) ShaclGenerator.ExactlyOneOf else 0) |
      (if (expression.allOf.exists(hasCardinality)) ShaclGenerator.AllOf else 0) |
      (if (expression.noneOf.exists(hasCardinality)) ShaclGenerator.NoneOf else 0)

  /** Emit the range, `pattern`, `minimum_value` and `maximum_value` of a nested expression. No
    * default range is applied.
    *
    * @param outerRange
    *   The nearest enclosing range, used to type the value bounds if the expression has no range
    */
  private def processNestedValueConstraints(
      sink: RdfSink,
      outerRange: Reference[?],
      expression: SlotExpression,
      subject: Resource,
  ): Unit = {
    expression.range.foreachFast { r =>
      processRange(sink, r, expression.implicitPrefix.isDefined, subject)
    }
    expression.pattern.foreachFast { p =>
      sink.triple(subject, Shacl.pattern, Literal(p))
    }
    if (
      (expression.minimumValue.isDefined || expression.maximumValue.isDefined) &&
      expression.implicitPrefix.isEmpty
    ) {
      expression.range
        .getOrElseFast(outerRange)
        .asInstanceOf[Reference[ElementView[?, ?]]]
        .resolve
        .flatMapFast {
          case typeView: TypeView => datatypeForValueBound(typeView)
          case _ => None
        }
        .foreachFast { datatype =>
          expression.minimumValue.foreachFast { v =>
            sink.triple(subject, Shacl.minInclusive, Literal(v.value.strip, datatype))
          }
          expression.maximumValue.foreachFast { v =>
            sink.triple(subject, Shacl.maxInclusive, Literal(v.value.strip, datatype))
          }
        }
    }
  }

  /** Emit the boolean slots: `any_of` as `sh:or`, `all_of` as `sh:and`, `exactly_one_of` as
    * `sh:xone`, `none_of` as one `sh:not` per member.
    *
    * Per value: by default, the members are value shapes, checked against each value.
    *
    * Per instance: value shapes can't count values. So if a member sets a cardinality, the operator
    * goes on the class shape instead, and each member becomes `[ sh:property [ sh:path <slot>; ...
    * ] ]`, checked against all values together.
    *
    * Member shapes are described later (see [[drain]]), so that the current shape stays in one
    * piece in Turtle.
    *
    * @param outerRange
    *   The range of `expression`, or the nearest enclosing one if it has none
    * @param path
    *   The slot's property, used in per-instance members
    * @param slots
    *   Which boolean slots to emit, as a bit set of [[ShaclGenerator.AnyOf]] etc.
    * @param subject
    *   The property shape (if `perValue`) or the class shape
    * @param perValue
    *   Whether `slots` are per-value (true) or per-instance (false) ones
    * @param deferred
    *   Collects the member descriptions
    */
  private def processBooleanSlots(
      sink: RdfSink,
      outerRange: Reference[?],
      path: Iri,
      expression: SlotExpression,
      slots: Int,
      subject: Resource,
      perValue: Boolean,
      deferred: mutable.Buffer[() => Unit],
  ): Unit = {
    if ((slots & ShaclGenerator.AnyOf) != 0)
      processBooleanSlot(
        sink,
        outerRange,
        path,
        Shacl.or,
        expression.anyOf,
        subject,
        perValue,
        deferred,
      )
    if ((slots & ShaclGenerator.ExactlyOneOf) != 0)
      processBooleanSlot(
        sink,
        outerRange,
        path,
        Shacl.xone,
        expression.exactlyOneOf,
        subject,
        perValue,
        deferred,
      )
    if ((slots & ShaclGenerator.AllOf) != 0)
      processBooleanSlot(
        sink,
        outerRange,
        path,
        Shacl.and,
        expression.allOf,
        subject,
        perValue,
        deferred,
      )
    if ((slots & ShaclGenerator.NoneOf) != 0)
      processBooleanSlot(
        sink,
        outerRange,
        path,
        Shacl.not,
        expression.noneOf,
        subject,
        perValue,
        deferred,
      )
  }

  /** Emit one boolean slot. See [[processBooleanSlots]].
    *
    * @param operator
    *   `sh:or`, `sh:and`, `sh:xone` or `sh:not`
    */
  private def processBooleanSlot(
      sink: RdfSink,
      outerRange: Reference[?],
      path: Iri,
      operator: Iri,
      members: Seq[SlotExpression],
      subject: Resource,
      perValue: Boolean,
      deferred: mutable.Buffer[() => Unit],
  ): Unit = {
    val shapes = members.map { member =>
      val shape = blankNode()
      deferred.addOne(() =>
        if (perValue) processValueShape(sink, outerRange, path, member, shape, deferred)
        else processInstanceShape(sink, outerRange, path, member, shape, deferred),
      )
      shape
    }
    // sh:not takes one shape, not a list
    if (operator eq Shacl.not) shapes.foreach(sink.triple(subject, Shacl.not, _))
    else sink.list(subject, operator, shapes)
  }

  /** Describe a per-value member: its value rules and nested operators. */
  private def processValueShape(
      sink: RdfSink,
      outerRange: Reference[?],
      path: Iri,
      member: SlotExpression,
      shape: Resource,
      deferred: mutable.Buffer[() => Unit],
  ): Unit = {
    processNestedValueConstraints(sink, outerRange, member, shape)
    // Nothing below a per-value member counts values, so all its boolean slots are per value too
    processBooleanSlots(
      sink,
      member.range.getOrElseFast(outerRange),
      path,
      member,
      booleanSlots(member),
      shape,
      perValue = true,
      deferred,
    )
  }

  /** Describe a per-instance member: a property shape for the slot with the member's cardinality
    * and value rules, plus its nested per-instance operators.
    */
  private def processInstanceShape(
      sink: RdfSink,
      outerRange: Reference[?],
      path: Iri,
      member: SlotExpression,
      shape: Resource,
      deferred: mutable.Buffer[() => Unit],
  ): Unit = {
    val range = member.range.getOrElseFast(outerRange)
    val instanceSlots = perInstanceSlots(member)
    val valueSlots = booleanSlots(member) & ~instanceSlots
    val needsProperty =
      member.required || member.minimumCardinality.isDefined ||
        member.maximumCardinality.isDefined || member.exactCardinality.isDefined ||
        member.range.isDefined || member.pattern.isDefined || member.minimumValue.isDefined ||
        member.maximumValue.isDefined || valueSlots != 0
    if (needsProperty) {
      val property = inlineBlankNode()
      sink.triple(shape, Shacl.property, property)
      sink.triple(property, Shacl.path, path)
      processCardinality(sink, member, None, property)
      processNestedValueConstraints(sink, outerRange, member, property)
      processBooleanSlots(
        sink,
        range,
        path,
        member,
        valueSlots,
        property,
        perValue = true,
        deferred,
      )
    }
    processBooleanSlots(
      sink,
      range,
      path,
      member,
      instanceSlots,
      shape,
      perValue = false,
      deferred,
    )
  }

  /** Run everything that was put off until the class was finished.
    */
  private def drain(deferred: mutable.Buffer[() => Unit]): Unit = {
    var i = 0
    while (i < deferred.length) {
      deferred(i)()
      i += 1
    }
    deferred.clear()
  }

  /** Emit the value constraints of an attribute: `pattern`, `minimum_value` and `maximum_value`.
    *
    * @param attributeView
    *   The attribute to generate triples for
    * @param subject
    *   The subject to generate triples for
    */
  private def processConstraints(
      sink: RdfSink,
      attributeView: AttributeView,
      subject: Resource,
  ): Unit = attributeView match {
    case typeAttribute: TypeAttributeView =>
      typeAttribute.pattern.foreachFast { p =>
        sink.triple(subject, Shacl.pattern, Literal(p.linkml))
      }
      // Bounds (min / max values) only make sense for ordered literal ranges.
      if (typeAttribute.implicitPrefix.isEmpty)
        datatypeForValueBound(typeAttribute.typeView).foreachFast { datatype =>
          typeAttribute.minimumValue.foreachFast { v =>
            sink.triple(subject, Shacl.minInclusive, Literal(v.value.strip, datatype))
          }
          typeAttribute.maximumValue.foreachFast { v =>
            sink.triple(subject, Shacl.maxInclusive, Literal(v.value.strip, datatype))
          }
        }
    case _ => ()
  }

  /** The datatype to tag `minimum_value` / `maximum_value` bounds with.
    *
    * SHACL compares the value bound against the data, and it needs one of the XSD datatypes to do
    * that validation. The type's own URI is no good here, as the validator does not know how to
    * validate it. We must coerce it to a base XSD type.
    *
    * @return
    *   The datatype to use, or None if this type has no meaningful ordering.
    */
  private def datatypeForValueBound(typeView: TypeView): Option[Iri] = typeView.runtimeType match {
    case IntegerType => new Some(XmlSchema.integer)
    case FloatType => new Some(XmlSchema.float)
    case DoubleType => new Some(XmlSchema.double)
    case DecimalType => new Some(XmlSchema.decimal)
    case DateType => new Some(XmlSchema.date)
    case DateTimeType => new Some(XmlSchema.dateTime)
    case TimeType => new Some(XmlSchema.time)
    case StringType => new Some(XmlSchema.string)
    case _ => None
  }

  /** Generate sh:property triples for a given slot. Produces triples of form
    * `propertyDomain sh:property [ ... ] .`
    *
    * @param attributeView
    *   Attribute to generate SHACL triples for.
    * @param order
    *   Fallback sh:order to use for the slot, if it has no `rank`
    * @param propertyDomain
    *   The RDF subject to add this sh:property to.
    * @param groups
    *   Collects the slots referenced via `slot_group`, so that their sh:PropertyGroup declarations
    *   can be emitted once at the end.
    * @param deferred
    *   Passed on to [[processBooleanSlots]].
    */
  private def processSlot(
      sink: RdfSink,
      attributeView: AttributeView,
      order: Int,
      propertyDomain: Resource,
      groups: mutable.Map[String, SlotView],
      deferred: mutable.Buffer[() => Unit],
  ): Unit = {
    val s = attributeView.slotView
    val slot = s.slot
    // Everything between here and the sh:order below describes the property shape and nothing else,
    // so Turtle can write the whole thing inline as `[ ... ]`.
    val property = inlineBlankNode()
    sink.triple(propertyDomain, Shacl.property, property)
    slot.title.foreachFast { t =>
      langStringProperty(sink, property, Shacl.name, t)
    }
    slot.description.foreachFast { d =>
      langStringProperty(sink, property, Shacl.description, d)
    }
    slot.slotGroup.foreachFast { groupRef =>
      sv.resolve(groupRef.asInstanceOf[Reference[SlotView]]).foreachFast { groupView =>
        groups.getOrElseUpdate(groupView.uriStr, groupView)
        sink.triple(property, Shacl.group, new Iri(groupView.uriStr))
      }
    }
    processCardinality(
      sink,
      slot,
      if (slot.multivalued) None else ShaclGenerator.one,
      property,
    )
    val path = new Iri(s.uriStr)
    sink.triple(property, Shacl.path, path)
    processMainRange(sink, s, slot, property)
    val range = slot.range.getOrElseFast(sv.getDefaultRange(s.definingSchema))
    val instanceSlots = perInstanceSlots(slot)
    processBooleanSlots(
      sink,
      range,
      path,
      slot,
      booleanSlots(slot) & ~instanceSlots,
      property,
      perValue = true,
      deferred,
    )
    processConstraints(sink, attributeView, property)
    // Use rank if possible. Other slots are put at the end.
    val rank = slot.rank.getOrElseFast(order)
    sink.triple(property, Shacl.order, Literal(rank.toString, XmlSchema.integer))
    // Per-instance operators go on the class shape
    processBooleanSlots(
      sink,
      range,
      path,
      slot,
      instanceSlots,
      propertyDomain,
      perValue = false,
      deferred,
    )
  }

  /** Declare the slots used as `slot_group` targets as sh:PropertyGroup instances, so that the
    * sh:group references made by property shapes point at well-typed, labeled nodes.
    */
  private def processGroups(sink: RdfSink, groups: mutable.Map[String, SlotView]): Unit =
    groups.values.foreach { g =>
      val groupIri = new Iri(g.uriStr)
      sink.triple(groupIri, Rdf.`type`, Shacl.PropertyGroup)
      langStringProperty(
        sink,
        groupIri,
        Rdfs.label,
        g.slot.title.getOrElseFast(PlainText(g.name)),
      )
      g.slot.description.foreachFast { d =>
        langStringProperty(sink, groupIri, Rdfs.comment, d)
      }
      g.slot.rank.foreachFast { r =>
        sink.triple(groupIri, Shacl.order, Literal(r.toString, XmlSchema.integer))
      }
    }

  /** Generates SHACL shapes and pushes the namespaces and triples into the provided [[RdfSink]].
    *
    * @param sink
    *   The sink that receives namespace declarations and triples.
    * @param options
    *   What to generate. See [[ShaclGenerator.Options]].
    */
  override final def generate(
      sink: RdfSink,
      options: ShaclGenerator.Options,
  ): Unit = {
    import options.{onlyClassesFromRootSchema, open}
    addNamespaces(sink, defaultPrefixes)
    val classes =
      if (onlyClassesFromRootSchema) sv.classes.filter {
        val root = sv.root
        kv => kv._2.definingSchema eq root
      }
      else sv.classes
    val groups = mutable.LinkedHashMap.empty[String, SlotView]
    // Shared by all classes. Only used by boolean slots.
    val deferred = mutable.ArrayBuffer.empty[() => Unit]
    classes.values.foreach { c =>
      val classNameIri = new Iri(c.uriStr)
      sink.triple(classNameIri, Rdf.`type`, Shacl.NodeShape)
      c.cls.description.foreachFast { d =>
        langStringProperty(sink, classNameIri, Rdfs.comment, d)
      }
      val closed = !open && c.isConcrete && !c.allowsExtraSlots
      sink.triple(classNameIri, Shacl.closed, Literal(closed.toString, XmlSchema.boolean))
      sink.list(
        classNameIri,
        Shacl.ignoredProperties,
        c.identifier.foldFast(Seq(Rdf.`type`))(id => Seq(Rdf.`type`, new Iri(id.uriStr))),
      )
      // LinkML's `rank` gives slots an explicit order. Slots without one keep their
      // declaration order, but start after the highest rank in the class.
      var order = 0
      c.attributeViews.values.foreach { av =>
        val slot = av.slotView.slot
        if (!slot.identifier) slot.rank.foreachFast(r => if (r >= order) order = r + 1)
      }
      c.attributeViews.values.foreach { av =>
        val slot = av.slotView.slot
        if (!slot.identifier) {
          processSlot(sink, av, order, classNameIri, groups, deferred)
          if (slot.rank.isEmpty) order += 1
        }
      }
      sink.triple(classNameIri, Shacl.targetClass, classNameIri)
      drain(deferred)
    }
    processGroups(sink, groups)
  }

  private val defaultPrefixes = Array(
    ("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
    ("sh", "http://www.w3.org/ns/shacl#"),
    ("xsd", "http://www.w3.org/2001/XMLSchema#"),
    ("rdfs", "http://www.w3.org/2000/01/rdf-schema#"),
  )
}

object ShaclGenerator {

  /** The implied cardinality of a `required` / single-valued slot. */
  private val one: Option[Int] = new Some(1)

  /** Bits for the boolean slots, used in bit sets. */
  private final val AnyOf = 1
  private final val ExactlyOneOf = 2
  private final val AllOf = 4
  private final val NoneOf = 8

  /** Options for [[ShaclGenerator]].
    *
    * @param open
    *   Whether the generated shapes should be open, allowing properties the schema does not mention
    *   (turned off by default). Shapes of classes whose `extra_slots` allows extra data are open
    *   anyway.
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    * @param format
    *   Which RDF serialization to write: `ttl` for Turtle (the default), which is prefixed and
    *   pretty-printed, or `nt` for N-Triples.
    */
  final case class Options(
      open: Boolean = false,
      onlyClassesFromRootSchema: Boolean = false,
      format: RdfFormat = RdfFormat.ttl,
  ) extends RdfOptions
}
