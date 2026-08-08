package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.metamodel.SlotExpression
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.schemaview.*

class ShaclGenerator(using sv: SchemaView) extends RdfGenerator {

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
      slotExpression.range.getOrElse(sv.getDefaultRange(slotView.definingSchema))
        .asInstanceOf[Reference[ElementView[?, ?]]].resolve.foreach {
          case typeView: TypeView =>
            val isIri = typeView.isIri || slotExpression.implicitPrefix.isDefined
            if !isIri then
              sink.triple(subject, Shacl.datatype, Iri(typeView.uriStr))
              sink.triple(subject, Shacl.nodeKind, Shacl.Literal)
            else sink.triple(subject, Shacl.nodeKind, Shacl.IRI)
          case classView: ClassView =>
            val cdUri = classView.uriStr
            val isLinkmlAny = cdUri == "https://w3id.org/linkml/Any"
            if (!isLinkmlAny) {
              sink.triple(subject, Shacl.`class`, Iri(cdUri))
              sink.triple(subject, Shacl.nodeKind, Shacl.BlankNodeOrIRI)
            }
          case enumView: EnumView =>
            val permissibleValues =
              enumView.derivedValues.map(value => {
                Iri(value.meaning.uri(using enumView.definingPrefixResolver))
              })
            val rdfListHead = addList(sink, permissibleValues)
            sink.triple(subject, Shacl.in, rdfListHead)
          case _ => throw RuntimeException(s"Couldn't map range ${slotExpression.range}")
        }
    // TODO LNK-129: Implement the rest of the boolean slots
    val ors = slotExpression.anyOf.map(curSlotExpression => {
      val curNode = blankNode()
      processSlotExpr(sink, slotView, curSlotExpression, curNode)
      curNode
    })
    val orListHeadMaybe = addList(sink, ors)
    if orListHeadMaybe != Rdf.nil then sink.triple(subject, Shacl.or, orListHeadMaybe)
  }

  /** Generate sh:property triples for a given slot. Produces triples of form
    * `propertyDomain sh:property [ ... ] .`
    *
    * @param s
    *   Slot to generate SHACL triples for.
    * @param order
    *   sh:order to use for the slot
    * @param propertyDomain
    *   The RDF subject to add this sh:property to.
    */
  private def processSlot(
      sink: RdfSink,
      s: SlotView,
      order: Int,
      propertyDomain: Resource,
  ): Unit = {
    val slot = s.slot
    val property = blankNode()
    sink.triple(propertyDomain, Shacl.property, property)
    slot.description match {
      case Some(d) => sink.triple(property, Shacl.description, Literal(d, XmlSchema.string))
      case _ =>
    }
    // TODO LNK-129: N-arity has to be done on the top-level-only,
    //  as SHACL boolean operators attached to a PropertyShape have to be NodeShapes
    //  and NodeShapes don't allow max/min count. To do this properly we would have
    //  to roll-down slots to the leaves of the boolean op tree and add make the
    //  leaves PropertyShapes.
    if (!slot.multivalued) sink.triple(property, Shacl.maxCount, Literal.one)
    if (slot.required) sink.triple(property, Shacl.minCount, Literal.one)
    sink.triple(property, Shacl.path, Iri(s.uriStr))
    processSlotExpr(sink, s, slot, property)
    sink.triple(property, Shacl.order, Literal(order.toString, XmlSchema.integer))
  }

  /** Generates SHACL shapes and pushes the namespaces and triples into the provided [[RdfSink]].
    *
    * @param sink
    *   The sink that receives namespace declarations and triples.
    * @param enforceOpenShapes
    *   A flag that enforces all shapes to be open (turned off by default)
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    */
  final def generate(
      sink: RdfSink,
      enforceOpenShapes: Boolean = false,
      onlyClassesFromRootSchema: Boolean = false,
  ): Unit = {
    addNamespaces(
      sink,
      Array(
        ("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
        ("sh", "http://www.w3.org/ns/shacl#"),
        ("xsd", "http://www.w3.org/2001/XMLSchema#"),
        ("rdfs", "http://www.w3.org/2000/01/rdf-schema#"),
      ),
    )

    val classes =
      if onlyClassesFromRootSchema then sv.classes.filter(_._2.definingSchema == sv.root)
      else sv.classes

    classes.values.foreach { c =>
      val classNameIri = Iri(c.uriStr)
      sink.triple(classNameIri, Rdf.`type`, Shacl.NodeShape)
      c.cls.description match {
        case Some(d) => sink.triple(classNameIri, Rdfs.comment, Literal(d, XmlSchema.string))
        case _ =>
      }
      val closed = !(enforceOpenShapes || c.cls.`abstract` || c.cls.mixin)
      sink.triple(classNameIri, Shacl.closed, Literal(closed.toString, XmlSchema.boolean))
      val ignoredPropertiesListHead = addList(
        sink,
        Seq(Rdf.`type`) ++ c.identifier.map(id => Iri(id.uriStr)),
      )
      sink.triple(classNameIri, Shacl.ignoredProperties, ignoredPropertiesListHead)
      c.derivedAttributes.values.foreach {
        var order = 0
        x =>
          if (!x.inner.identifier) {
            processSlot(sink, x, order, classNameIri)
            order += 1
          }
      }
      sink.triple(classNameIri, Shacl.targetClass, classNameIri)
    }
  }
}
