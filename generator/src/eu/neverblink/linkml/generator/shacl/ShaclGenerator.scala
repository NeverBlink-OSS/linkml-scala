package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.metamodel.SlotExpression
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.*

import scala.collection.mutable

class ShaclGenerator(using sv: SchemaView) extends RdfGenerator[ShaclGenerator.Options] {

  override protected def defaultOptions: ShaclGenerator.Options = ShaclGenerator.Options()

  /** Process a slot expression, including some support for boolean slot expressions.
    *
    * @param slotView
    *   The defining slots' view, used for default range resolution
    * @param slotExpression
    *   The currently processed expression
    * @param subject
    *   The subject to generate triples for
    */
  private def processSlotExpr(
      sink: RdfSink,
      slotView: SlotView,
      slotExpression: SlotExpression,
      subject: Resource,
  ): Unit = {
    // TODO LNK-129 HACK: Skip the main range if any boolean slot is defined.
    if slotExpression.anyOf.isEmpty then
      slotExpression.range.getOrElseFast(sv.getDefaultRange(slotView.definingSchema))
        .asInstanceOf[Reference[ElementView[?, ?]]].resolve.foreachFast {
          case typeView: TypeView =>
            val isIri = typeView.isIri || slotExpression.implicitPrefix.isDefined
            if !isIri then
              sink.triple(subject, Shacl.datatype, new Iri(typeView.uriStr))
              sink.triple(subject, Shacl.nodeKind, Shacl.Literal)
            else sink.triple(subject, Shacl.nodeKind, Shacl.IRI)
          case classView: ClassView =>
            if (!classView.isAny) {
              sink.triple(subject, Shacl.`class`, new Iri(classView.uriStr))
              sink.triple(subject, Shacl.nodeKind, Shacl.BlankNodeOrIRI)
            }
          case enumView: EnumView =>
            val permissibleValues =
              enumView.derivedValues.map { value =>
                new Iri(value.meaning.uri(using enumView.definingPrefixResolver))
              }
            val rdfListHead = addList(sink, permissibleValues)
            sink.triple(subject, Shacl.in, rdfListHead)
          case _ => throw RuntimeException(s"Couldn't map range ${slotExpression.range}")
        }
    // TODO LNK-129: Implement the rest of the boolean slots
    val ors = slotExpression.anyOf.map(curSlotExpression => {
      val currentNode = blankNode()
      processSlotExpr(sink, slotView, curSlotExpression, currentNode)
      currentNode
    })
    val orListHeadMaybe = addList(sink, ors)
    if (orListHeadMaybe ne Rdf.nil) sink.triple(subject, Shacl.or, orListHeadMaybe)
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
        sink.triple(subject, Shacl.pattern, Literal(p))
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
    val property = blankNode()
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
    // TODO LNK-129: N-arity has to be done on the top-level-only,
    //  as SHACL boolean operators attached to a PropertyShape have to be NodeShapes
    //  and NodeShapes don't allow max/min count. To do this properly we would have
    //  to roll-down slots to the leaves of the boolean op tree and add make the
    //  leaves PropertyShapes.
    // The explicit cardinality metaslots take precedence over `required` / `multivalued`.
    slot.minimumCardinality.orElseFast(slot.exactCardinality)
      .orElseFast(if (slot.required) ShaclGenerator.one else None)
      .foreachFast { c =>
        sink.triple(property, Shacl.minCount, Literal(c.toString, XmlSchema.integer))
      }
    slot.maximumCardinality.orElseFast(slot.exactCardinality)
      .orElseFast(if (slot.multivalued) None else ShaclGenerator.one)
      .foreachFast { c =>
        sink.triple(property, Shacl.maxCount, Literal(c.toString, XmlSchema.integer))
      }
    sink.triple(property, Shacl.path, new Iri(s.uriStr))
    processSlotExpr(sink, s, slot, property)
    processConstraints(sink, attributeView, property)
    // Use rank if possible. Other slots are put at the end.
    val rank = slot.rank.getOrElseFast(order)
    sink.triple(property, Shacl.order, Literal(rank.toString, XmlSchema.integer))
  }

  /** Declare the slots used as `slot_group` targets as sh:PropertyGroup instances, so that the
    * sh:group references made by property shapes point at well-typed, labeled nodes.
    */
  private def processGroups(sink: RdfSink, groups: mutable.Map[String, SlotView]): Unit =
    groups.values.foreach { g =>
      val groupIri = new Iri(g.uriStr)
      sink.triple(groupIri, Rdf.`type`, Shacl.PropertyGroup)
      g.slot.title match {
        case Some(t) => langStringProperty(sink, groupIri, Rdfs.label, t)
        case None => sink.triple(groupIri, Rdfs.label, Literal(g.name, XmlSchema.string))
      }
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
      val closed = !(open || c.cls.`abstract` || c.cls.mixin)
      sink.triple(classNameIri, Shacl.closed, Literal(closed.toString, XmlSchema.boolean))
      val ignoredPropertiesListHead = addList(
        sink,
        c.identifier.foldFast(Seq(Rdf.`type`))(id => Seq(Rdf.`type`, new Iri(id.uriStr))),
      )
      sink.triple(classNameIri, Shacl.ignoredProperties, ignoredPropertiesListHead)
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

  /** Options for [[ShaclGenerator]].
    *
    * @param open
    *   Whether the generated shapes should be open, allowing properties the schema does not mention
    *   (turned off by default).
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    */
  final case class Options(
      open: Boolean = false,
      onlyClassesFromRootSchema: Boolean = false,
  )
}
