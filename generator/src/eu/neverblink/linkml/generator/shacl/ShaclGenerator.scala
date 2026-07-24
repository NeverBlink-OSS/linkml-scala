package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.metamodel.SlotExpression
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.schemaview.SchemaView.defaultRangeResolved
import eu.neverblink.linkml.schemaview.{
  ClassView,
  ElementView,
  EnumView,
  SchemaView,
  SlotView,
  TypeView,
}

class ShaclGenerator(using sv: SchemaView) {

  private var blankNodeCounter = 0

  private def blankNode(): BlankNode = {
    blankNodeCounter += 1
    BlankNode(blankNodeCounter.toString)
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

    /** Process a slot expression, including some support for boolean slot expressions.
      * @param slotView
      *   The defining slots' view, used for default range resolution
      * @param slotExpression
      *   The currently processed expression
      * @param subject
      *   The subject to generate triples for
      */
    def processSlotExpr(
        slotView: SlotView,
        slotExpression: SlotExpression,
        subject: Resource,
    ): Unit = {
      // TODO LNK-129 HACK: Skip the main range if any boolean slot is defined.
      if slotExpression.anyOf.isEmpty then
        slotExpression.range.getOrElse(slotView.definingSchema.defaultRangeResolved)
          .asInstanceOf[Reference[ElementView[?]]].resolve.foreach {
            case typeView: TypeView =>
              val isIri = typeView.isIri || slotExpression.implicitPrefix.isDefined
              if (!isIri) sink.triple(subject, Shacl.datatype, Iri(typeView.uriStr))
              val nodeKind =
                if (isIri) Shacl.IRI
                else Shacl.Literal
              sink.triple(subject, Shacl.nodeKind, nodeKind)
            case classView: ClassView =>
              val cdUri = classView.uriStr
              val isLinkmlAny = cdUri == "https://w3id.org/linkml/Any"
              if (!isLinkmlAny) {
                sink.triple(subject, Shacl.`class`, Iri(cdUri))
                sink.triple(subject, Shacl.nodeKind, Shacl.BlankNodeOrIRI)
              }
            case enumView: EnumView =>
              val permissibleValues =
                enumView.derivedValues.foldRight(Rdf.nil: Resource) { (value, acc) =>
                  val listNode = blankNode()
                  val meaning = value.meaning.uri(using enumView.definingPrefixResolver)
                  sink.triple(listNode, Rdf.first, Iri(meaning))
                  sink.triple(listNode, Rdf.rest, acc)
                  listNode
                }
              sink.triple(subject, Shacl.in, permissibleValues)
            case _ => throw RuntimeException(s"Couldn't map range ${slotExpression.range}")
          }
      // TODO LNK-129: Implement the rest of the boolean slots
      val ors = slotExpression.anyOf.map(curSlotExpression => {
        val curNode = blankNode()
        processSlotExpr(slotView, curSlotExpression, curNode)
        curNode
      })
      val orListHeadMaybe = addShaclList(ors)
      orListHeadMaybe.foreach(sink.triple(subject, Shacl.or, _))
    }

    /** Generate sh:property triples for a given slot. Produces triples of form
      * `propertyDomain sh:property [ ... ] .`
      * @param s
      *   Slot to generate SHACL triples for.
      * @param order
      *   sh:order to use for the slot
      * @param propertyDomain
      *   The RDF subject to add this sh:property to.
      */
    def processSlot(s: SlotView, order: Int, propertyDomain: Resource): Unit = {
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
      processSlotExpr(s, slot, property)
      sink.triple(property, Shacl.order, Literal(order.toString, XmlSchema.integer))
    }

    /** Create a SHACL list of the provided [[values]] and add it to the RDF graph.
      * @param values
      *   Values to include in the SHACL list
      * @return
      *   Head node of the list if [[values]] was non-empty, None otherwise
      */
    def addShaclList(values: Seq[Node]): Option[BlankNode] = {
      if values.isEmpty then return None
      val start = blankNode()
      sink.triple(start, Rdf.first, values.head)
      var prev = start
      values.tail.foreach { value =>
        val cur = blankNode()
        sink.triple(prev, Rdf.rest, cur)
        sink.triple(cur, Rdf.first, value)
        prev = cur
      }
      sink.triple(prev, Rdf.rest, Rdf.nil)
      Some(start)
    }

    val isEmitted = sv.root.defaultPrefix.foldLeft(
      sv.root.emitPrefixes.toSet ++
        Array( // TODO: LNK-43 check if they should be added in the emit_prefixes section of the metamodel
          "bibo",
          "oslc",
          "qudt",
          "skosxl", // TODO: LNK-43 check why `linkml generate shacl` emits `schema1` prefix instead of 'schema'
        ),
    )((acc, p) => acc + p)
    val classes =
      if onlyClassesFromRootSchema then sv.classes.filter(_._2.definingSchema == sv.root)
      else sv.classes
    sv.root.prefixes.values.toArray
      .collect {
        case p if isEmitted(p.prefixPrefix) =>
          (p.prefixPrefix, p.prefixReference.original)
      }
      .appendedAll {
        val prefixes = Array(
          ("rdf", "http://www.w3.org/1999/02/22-rdf-syntax-ns#"),
          ("sh", "http://www.w3.org/ns/shacl#"),
          ("xsd", "http://www.w3.org/2001/XMLSchema#"),
        )
        if (classes.values.exists(_.cls.description.isDefined)) {
          prefixes.appended(("rdfs", "http://www.w3.org/2000/01/rdf-schema#"))
        } else prefixes
      }
      .distinct.sorted.foreach(sink.namespace)

    classes.values.foreach { c =>
      val classNameIri = Iri(c.uriStr)
      sink.triple(classNameIri, Rdf.`type`, Shacl.NodeShape)
      c.cls.description match {
        case Some(d) => sink.triple(classNameIri, Rdfs.comment, Literal(d, XmlSchema.string))
        case _ =>
      }
      val closed = !(enforceOpenShapes || c.cls.`abstract` || c.cls.mixin)
      sink.triple(classNameIri, Shacl.closed, Literal(closed.toString, XmlSchema.boolean))
      val ignoredProperties = blankNode()
      sink.triple(classNameIri, Shacl.ignoredProperties, ignoredProperties)
      sink.triple(ignoredProperties, Rdf.first, Rdf.`type`)
      if c.hasIdentifier then {
        val ignoredId = blankNode()
        sink.triple(ignoredProperties, Rdf.rest, ignoredId)
        sink.triple(ignoredId, Rdf.first, Iri(c.identifier.get.uriStr))
        sink.triple(ignoredId, Rdf.rest, Rdf.nil)
      } else sink.triple(ignoredProperties, Rdf.rest, Rdf.nil)
      var order = 0
      c.derivedAttributes.values.filter(!_.inner.identifier).foreach { x =>
        processSlot(x, order, classNameIri); order += 1
      }
      sink.triple(classNameIri, Shacl.targetClass, classNameIri)
    }
  }
}

object Shacl {
  val BlankNodeOrIRI: Iri = Iri("http://www.w3.org/ns/shacl#BlankNodeOrIRI")
  val IRI: Iri = Iri("http://www.w3.org/ns/shacl#IRI")
  val Literal: Iri = Iri("http://www.w3.org/ns/shacl#Literal")
  val NodeShape: Iri = Iri("http://www.w3.org/ns/shacl#NodeShape")
  val PropertyShape: Iri = Iri("http://www.w3.org/ns/shacl#PropertyShape")
  val `class`: Iri = Iri("http://www.w3.org/ns/shacl#class")
  val closed: Iri = Iri("http://www.w3.org/ns/shacl#closed")
  val datatype: Iri = Iri("http://www.w3.org/ns/shacl#datatype")
  val description: Iri = Iri("http://www.w3.org/ns/shacl#description")
  val ignoredProperties: Iri = Iri("http://www.w3.org/ns/shacl#ignoredProperties")
  val in: Iri = Iri("http://www.w3.org/ns/shacl#in")
  val maxCount: Iri = Iri("http://www.w3.org/ns/shacl#maxCount")
  val minCount: Iri = Iri("http://www.w3.org/ns/shacl#minCount")
  val nodeKind: Iri = Iri("http://www.w3.org/ns/shacl#nodeKind")
  val or: Iri = Iri("http://www.w3.org/ns/shacl#or")
  val order: Iri = Iri("http://www.w3.org/ns/shacl#order")
  val path: Iri = Iri("http://www.w3.org/ns/shacl#path")
  val property: Iri = Iri("http://www.w3.org/ns/shacl#property")
  val targetClass: Iri = Iri("http://www.w3.org/ns/shacl#targetClass")
}

object Rdfs {
  val comment: Iri = Iri("http://www.w3.org/2000/01/rdf-schema#comment")
}
