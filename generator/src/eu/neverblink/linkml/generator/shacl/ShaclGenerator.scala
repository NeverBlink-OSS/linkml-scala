package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.RdfGeneratorBase
import eu.neverblink.linkml.generator.RdfGeneratorBase.{RdfFormat, RdfOptions}
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.metamodel.SlotExpression
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

import ShaclGenerator.Check

class ShaclGenerator(using sv: SchemaView) extends RdfGeneratorBase[ShaclGenerator.Options] {

  override protected def defaultOptions: ShaclGenerator.Options = ShaclGenerator.Options()

  /** Emit the slot's range on its PropertyShape, with the constraints of the slot and its range
    * type. A slot without `range` gets the schema's `default_range`.
    *
    * No range is emitted if an `any_of` or `exactly_one_of` member sets a range, because the member
    * NodeShapes already list the allowed ranges. LinkML-Python does the same for `any_of`.
    *
    * For example, a slot without `range` would otherwise get both `sh:datatype xsd:string` (from
    * `default_range`) and `sh:or ( [ sh:class A ] ... )`. No value can match both.
    *
    * The `default_range` is also not emitted if an `all_of` member sets a range.
    *
    * Without a range, only the constraints are emitted, see [[processConstraints]].
    */
  private def processMainRange(
      sink: RdfSink,
      attributeView: AttributeView,
      subject: Resource,
  ): Unit = {
    val slotView = attributeView.slotView
    val slot = slotView.slot
    if (!slot.anyOf.exists(_.range.isDefined) && !slot.exactlyOneOf.exists(_.range.isDefined))
      slot.range match {
        case Some(r) => processRange(sink, r, slot, subject)
        case _ =>
          if (!slot.allOf.exists(_.range.isDefined))
            processRange(sink, sv.getDefaultRange(slotView.definingSchema), slot, subject)
          else processConstraints(sink, attributeView, subject)
      }
    else processConstraints(sink, attributeView, subject)
  }

  /** Emit `sh:datatype`, `sh:class` or `sh:in` for a range. A type range also gets the constraints
    * of `constraints` and the type: as one shape if the type has one value alternative, or as
    * `sh:or` with one shape per alternative for a `union_of` type.
    *
    * @param constraints
    *   The slot or the member of a boolean slot whose range this is
    */
  private def processRange(
      sink: RdfSink,
      range: Reference[?],
      constraints: SlotExpression,
      subject: Resource,
  ): Unit =
    range.asInstanceOf[Reference[ElementView[?, ?]]].resolve.foreachFast {
      case typeView: TypeView =>
        // Values with an implicit prefix are IRIs, even for a literal type
        val isIri = constraints.implicitPrefix.isDefined
        val alternatives = typeView.alternatives
        if (alternatives.sizeIs == 1)
          emitAlternative(sink, alternatives.head.withConstraintsOf(constraints), isIri, subject)
        else {
          sink.beginList(subject, Shacl.or)
          alternatives.foreach { alternative =>
            val shape = inlineBlankNode()
            sink.listMember(shape)
            emitAlternative(sink, alternative.withConstraintsOf(constraints), isIri, shape)
          }
          sink.endList()
        }
      case classView: ClassView =>
        if (!classView.isAny) {
          sink.triple(subject, Shacl.`class`, Iri(classView.uriStr))
          sink.triple(subject, Shacl.nodeKind, Shacl.BlankNodeOrIRI)
        }
      case enumView: EnumView =>
        sink.list(subject, Shacl.in, enumView.derivedValues.map(value => Iri(value.uriStr)))
      case _ => throw RuntimeException(s"Couldn't map range $range")
    }

  /** Emit `sh:datatype` and `sh:nodeKind` for a value alternative, then its constraints.
    *
    * @param isIri
    *   Whether values are IRIs even for a literal type (e.g. with `implicit_prefix`)
    */
  private def emitAlternative(
      sink: RdfSink,
      alternative: ValueAlternative,
      isIri: Boolean,
      subject: Resource,
  ): Unit = {
    val typeView = alternative.typeView
    if (!typeView.isIri && !isIri) {
      sink.triple(subject, Shacl.datatype, Iri(typeView.uriStr))
      sink.triple(subject, Shacl.nodeKind, Shacl.Literal)
    } else sink.triple(subject, Shacl.nodeKind, Shacl.IRI)
    emitConstraints(sink, alternative, isIri, subject)
  }

  /** Emit `sh:pattern`, `sh:minLength`, `sh:maxLength`, `sh:minInclusive` and `sh:maxInclusive` for
    * a value alternative.
    *
    * Lengths and value bounds are only emitted for literals. An IRI's length would be the length of
    * the full IRI, not of the text in the data. This is also why a URI or CURIE gets no lengths.
    *
    * @param isIri
    *   Whether values are IRIs even for a literal type (e.g. with `implicit_prefix`)
    */
  private def emitConstraints(
      sink: RdfSink,
      alternative: ValueAlternative,
      isIri: Boolean,
      subject: Resource,
  ): Unit = {
    alternative.pattern.foreachFast { p =>
      sink.triple(subject, Shacl.pattern, Literal(p.linkml))
    }
    if (!isIri && !alternative.typeView.isIri) {
      alternative.minimumLength.foreachFast { n =>
        sink.triple(subject, Shacl.minLength, Literal(n.toString, XmlSchema.integer))
      }
      alternative.maximumLength.foreachFast { n =>
        sink.triple(subject, Shacl.maxLength, Literal(n.toString, XmlSchema.integer))
      }
      datatypeForValueBound(alternative.typeView).foreachFast { datatype =>
        alternative.minimumValue.foreachFast { v =>
          sink.triple(subject, Shacl.minInclusive, Literal(v.value.strip, datatype))
        }
        alternative.maximumValue.foreachFast { v =>
          sink.triple(subject, Shacl.maxInclusive, Literal(v.value.strip, datatype))
        }
      }
    }
  }

  /** Emit `sh:minCount` and `sh:maxCount`. Cardinality metaslots have precedence over `required`.
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

  /** Whether the expression or anything nested in it limits the number of values.
    *
    * @param expression
    *   A slot or a member of a boolean slot
    */
  private def hasCardinality(expression: SlotExpression): Boolean =
    hasOwnCardinality(expression) || checkOf(expression.anyOf) == Check.PerInstance ||
      checkOf(expression.exactlyOneOf) == Check.PerInstance ||
      checkOf(expression.allOf) == Check.PerInstance ||
      checkOf(expression.noneOf) == Check.PerInstance

  /** Whether the expression itself limits the number of values. `multivalued` is ignored, as an
    * explicit `false` looks the same as the default.
    *
    * @param expression
    *   A slot or a member of a boolean slot
    */
  private def hasOwnCardinality(expression: SlotExpression): Boolean =
    expression.required || expression.minimumCardinality.isDefined ||
      expression.maximumCardinality.isDefined || expression.exactCardinality.isDefined

  /** Whether the expression sets a range, `pattern`, `minimum_value`, `maximum_value`,
    * `minimum_length` or `maximum_length`.
    *
    * @param expression
    *   A member of a boolean slot
    */
  private def hasValueConstraints(expression: SlotExpression): Boolean =
    expression.range.isDefined || expression.pattern.isDefined ||
      expression.minimumValue.isDefined || expression.maximumValue.isDefined ||
      expression.minimumLength.isDefined || expression.maximumLength.isDefined

  /** How a boolean slot with these members is checked: per instance if a member limits the number
    * of values, otherwise per value. See [[processBooleanSlots]].
    *
    * @param members
    *   The members of the boolean slot
    */
  private def checkOf(members: Seq[SlotExpression]): Check =
    if (members.exists(hasCardinality)) Check.PerInstance
    else Check.PerValue

  /** Whether the expression has a non-empty boolean slot that is checked per value.
    *
    * @param expression
    *   A member of a boolean slot
    */
  private def hasPerValueSlot(expression: SlotExpression): Boolean =
    isPerValue(expression.anyOf) || isPerValue(expression.exactlyOneOf) ||
      isPerValue(expression.allOf) || isPerValue(expression.noneOf)

  /** Whether a boolean slot has members and is checked per value.
    *
    * @param members
    *   The members of the boolean slot
    */
  private def isPerValue(members: Seq[SlotExpression]): Boolean =
    members.nonEmpty && checkOf(members) == Check.PerValue

  /** Emit the boolean slots of a slot that are checked in the given way, as logical constraint
    * components: `any_of` as `sh:or`, `exactly_one_of` as `sh:xone`, `all_of` as `sh:and`, and
    * `none_of` as one `sh:not` per member. Each member becomes a NodeShape, written inline right
    * where it is referenced. Members may have boolean slots of their own, nested to any depth.
    *
    * A boolean slot is checked in one of two ways, see [[checkOf]]:
    *
    *   - Per value, if no member limits the number of values. The logical constraint component goes
    *     on the slot's PropertyShape, and each value of the slot is checked against the member
    *     NodeShapes: `[ sh:path <slot>; sh:or ( [ sh:datatype xsd:integer ] ... ) ]`.
    *   - Per instance, if a member limits the number of values. Only a PropertyShape can count
    *     values, so the logical constraint component goes on the class's NodeShape, and each member
    *     is a NodeShape with a PropertyShape for the slot: `sh:or ( [ sh:property [ sh:path <slot>;
    *     sh:maxCount 1 ] ] ... )`. This PropertyShape is checked against the instance, so it can
    *     count the values of the slot.
    *
    * So [[processSlot]] calls this twice, once for each check and with a different `subject`.
    *
    * The work is done by nested methods, which use the parameters of the methods they are in
    * instead of taking them again:
    *
    *   - `emitBooleanSlots` emits the boolean slots of the slot or of a member. Members call it
    *     again for their own boolean slots, with their own range, shape and check.
    *   - `emitBooleanSlot` emits one boolean slot.
    *   - `emitMember` emits one member, as a per-value or a per-instance member.
    *
    * @param sink
    *   Receives the triples
    * @param path
    *   The slot's property
    * @param range
    *   The slot's range
    * @param slot
    *   The slot whose boolean slots to emit
    * @param subject
    *   The slot's PropertyShape for per-value checks, or the class's NodeShape for per-instance
    *   checks
    * @param check
    *   Which boolean slots to emit: those checked per value or those checked per instance
    */
  private def processBooleanSlots(
      sink: RdfSink,
      path: Iri,
      range: Reference[?],
      slot: SlotExpression,
      subject: Resource,
      check: Check,
  ): Unit = {

    /** Emit the boolean slots of the slot or a member that are checked in the given way.
      *
      * @param expression
      *   The slot or a member
      * @param range
      *   The range of `expression`, or the nearest enclosing one if it has none
      * @param subject
      *   The shape to put the logical constraint components on
      * @param check
      *   Which boolean slots to emit
      */
    def emitBooleanSlots(
        expression: SlotExpression,
        range: Reference[?],
        subject: Resource,
        check: Check,
    ): Unit = {

      /** Emit one boolean slot of `expression`, if it has members and is checked as `check` says:
        * one `sh:not` per member, or an RDF list of members for the other logical constraint
        * components.
        *
        * @param component
        *   The logical constraint component: `sh:or`, `sh:xone`, `sh:and` or `sh:not`
        * @param members
        *   The members of the boolean slot
        */
      def emitBooleanSlot(component: Iri, members: Seq[SlotExpression]): Unit = {

        /** Emit a member as an inline NodeShape, and link it from `subject` with `component`: as a
          * list member, or directly for `sh:not`. The NodeShape is per value or per instance, as
          * `check` says.
          *
          * @param member
          *   The member to emit
          */
        def emitMember(member: SlotExpression): Unit = {
          val shape = inlineBlankNode()
          val memberRange = member.range.getOrElseFast(range)

          /** Emit the range of the member and its value constraints, see [[processRange]]. A member
            * without a range gets no `default_range`, but its constraints still apply to the
            * nearest enclosing range: in one shape, or in `sh:or` for a `union_of` type.
            *
            * @param target
            *   The shape to put the value constraints on
            */
          def emitValueConstraints(target: Resource): Unit =
            member.range match {
              case Some(r) => processRange(sink, r, member, target)
              case _ =>
                if (hasValueConstraints(member))
                  range.asInstanceOf[Reference[ElementView[?, ?]]].resolve.foreachFast {
                    case typeView: TypeView =>
                      if (typeView.isUnion) processRange(sink, range, member, target)
                      else
                        emitConstraints(
                          sink,
                          typeView.alternatives.head.withConstraintsOf(member),
                          member.implicitPrefix.isDefined,
                          target,
                        )
                    case _ =>
                      member.pattern.foreachFast { p =>
                        sink.triple(target, Shacl.pattern, Literal(p))
                      }
                  }
            }

          /** Emit a per-value member: a NodeShape that values of the slot are checked against, with
            * the member's value constraints and its nested boolean slots.
            */
          def emitValueMember(): Unit = {
            emitValueConstraints(shape)
            // Nothing nested in a per-value member limits the number of values, so its boolean
            // slots are checked per value too
            emitBooleanSlots(member, memberRange, shape, Check.PerValue)
          }

          /** Emit a per-instance member: a NodeShape with a PropertyShape for the slot. The
            * PropertyShape has the member's cardinality, value constraints and per-value boolean
            * slots. The member's per-instance boolean slots go on the NodeShape.
            */
          def emitInstanceMember(): Unit = {
            // No PropertyShape if the member only has per-instance boolean slots
            if (
              hasOwnCardinality(member) || hasValueConstraints(member) || hasPerValueSlot(member)
            ) {
              val property = inlineBlankNode()
              sink.triple(shape, Shacl.property, property)
              sink.triple(property, Shacl.path, path)
              processCardinality(sink, member, None, property)
              emitValueConstraints(property)
              emitBooleanSlots(member, memberRange, property, Check.PerValue)
            }
            emitBooleanSlots(member, memberRange, shape, Check.PerInstance)
          }

          // sh:not takes one NodeShape, not a list
          if (component eq Shacl.not) sink.triple(subject, Shacl.not, shape)
          else sink.listMember(shape)
          check match {
            case Check.PerValue => emitValueMember()
            case _ => emitInstanceMember()
          }
        }

        if (members.nonEmpty && checkOf(members) == check) {
          if (component eq Shacl.not) members.foreach(emitMember)
          else {
            sink.beginList(subject, component)
            members.foreach(emitMember)
            sink.endList()
          }
        }
      }

      emitBooleanSlot(Shacl.or, expression.anyOf)
      emitBooleanSlot(Shacl.xone, expression.exactlyOneOf)
      emitBooleanSlot(Shacl.and, expression.allOf)
      emitBooleanSlot(Shacl.not, expression.noneOf)
    }

    emitBooleanSlots(slot, range, subject, check)
  }

  /** Emit the value constraints of an attribute whose range isn't emitted, see
    * [[processMainRange]]. These are the constraints of the slot and its range type, see
    * [[emitConstraints]]. A `union_of` range gets none, as only its alternatives can have them.
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
      val alternatives = typeAttribute.alternatives
      if (alternatives.sizeIs == 1)
        emitConstraints(
          sink,
          alternatives.head,
          typeAttribute.slotView.slot.implicitPrefix.isDefined,
          subject,
        )
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
    case _ => None
  }

  /** Generate sh:property triples for a given slot. Produces triples of form
    * `propertyDomain sh:property [ ... ] .`
    *
    * @param sink
    *   Receives the triples
    * @param attributeView
    *   Attribute to generate SHACL triples for.
    * @param order
    *   Fallback sh:order to use for the slot, if it has no `rank`
    * @param propertyDomain
    *   The RDF subject to add this sh:property to.
    * @param groups
    *   Collects the slots referenced via `slot_group`, so that their sh:PropertyGroup declarations
    *   can be emitted once at the end.
    */
  private def processSlot(
      sink: RdfSink,
      attributeView: AttributeView,
      order: Int,
      propertyDomain: Resource,
      groups: mutable.Map[String, SlotView],
  ): Unit = {
    val s = attributeView.slotView
    val slot = s.slot
    // Everything between here and the sh:order below describes the PropertyShape and nothing else,
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
    processMainRange(sink, attributeView, property)
    val range = slot.range.getOrElseFast(sv.getDefaultRange(s.definingSchema))
    processBooleanSlots(sink, path, range, slot, property, Check.PerValue)
    // Use rank if possible. Other slots are put at the end.
    val rank = slot.rank.getOrElseFast(order)
    sink.triple(property, Shacl.order, Literal(rank.toString, XmlSchema.integer))
    // Per-instance logical constraint components go on the class's NodeShape
    processBooleanSlots(sink, path, range, slot, propertyDomain, Check.PerInstance)
  }

  /** Declare the slots used as `slot_group` targets as sh:PropertyGroup instances, so that the
    * sh:group references made by PropertyShapes point at well-typed, labeled nodes.
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
          processSlot(sink, av, order, classNameIri, groups)
          if (slot.rank.isEmpty) order += 1
        }
      }
      sink.triple(classNameIri, Shacl.targetClass, classNameIri)
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

  /** How a boolean slot is checked. See [[ShaclGenerator.processBooleanSlots]]. */
  private enum Check {
    case PerValue, PerInstance
  }

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
