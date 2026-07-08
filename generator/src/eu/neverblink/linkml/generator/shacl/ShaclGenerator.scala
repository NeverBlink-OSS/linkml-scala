package eu.neverblink.linkml.generator.shacl

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.metamodel.{AnonymousSlotExpression, SlotDefinition, SlotExpression}
import eu.neverblink.linkml.runtime.Reference
import eu.neverblink.linkml.schemaview.SchemaView.defaultRangeResolved
import eu.neverblink.linkml.schemaview.{ClassView, ElementView, EnumView, SchemaView, SlotView, TypeView}

class ShaclGenerator(using sv: SchemaView) {

  /** Generates SHACL rules as RDF model that is represented as a tuple of sequences of namespaces
    * and triple
    *
    * @param enforceOpenShapes
    *   A flag that enforces all shapes to be open (turned off by default)
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    *
    * @return
    *   a tuple of sequences of namespaces and triples
    */
  final def generate(
      enforceOpenShapes: Boolean = false,
      onlyClassesFromRootSchema: Boolean = false,
  ): (Seq[Namespace], Seq[Triple]) = {
    val namespaces = Seq.newBuilder[Namespace]

    def addNamespace(prefix: String, name: String): Unit =
      namespaces.addOne(Namespace(prefix, name))

    val triples = Seq.newBuilder[Triple]

    def addTriple(subj: Resource, pred: Iri, obj: Node): Unit =
      triples.addOne(Triple(subj, pred, obj))

    var blankNodeCounter = 0

    def blankNode(): BlankNode = {
      blankNodeCounter += 1
      BlankNode(blankNodeCounter.toString)
    }

    def processSlotExpr(
        slotView: SlotView,
        slotExpression: SlotExpression,
        property: Resource,
    ): Unit = {
      slotExpression match {
        case expression: AnonymousSlotExpression =>
          expression.description match {
            case Some(d) => addTriple(property, Shacl.description, Literal(d, XmlSchema.string))
            case _ =>
          }
          if (!expression.multivalued) addTriple(property, Shacl.maxCount, Literal.one)
          if (expression.required) addTriple(property, Shacl.minCount, Literal.one)
        case definition: SlotDefinition =>
          definition.description match {
            case Some(d) => addTriple(property, Shacl.description, Literal(d, XmlSchema.string))
            case _ =>
          }
          if (!definition.multivalued) addTriple(property, Shacl.maxCount, Literal.one)
          if (definition.required) addTriple(property, Shacl.minCount, Literal.one)
        case _ => throw RuntimeException("slot expression not one of anon/def")
      }
      slotExpression.range.getOrElse(slotView.definingSchema.defaultRangeResolved)
        .asInstanceOf[Reference[ElementView[?]]].resolve.foreach {
          case typeView: TypeView =>
            val isIri = typeView.isIri || slotExpression.implicitPrefix.isDefined
            if (!isIri) addTriple(property, Shacl.datatype, Iri(typeView.uriStr))

            val nodeKind =
              if (isIri) Shacl.IRI
              else Shacl.Literal
            addTriple(property, Shacl.nodeKind, nodeKind)
          case classView: ClassView =>
            val cdUri = classView.uriStr
            val isLinkmlAny = cdUri == "https://w3id.org/linkml/Any"
            if (!isLinkmlAny) {
              addTriple(property, Shacl.`class`, Iri(cdUri))
              addTriple(property, Shacl.nodeKind, Shacl.BlankNodeOrIRI)
            }
          case enumView: EnumView =>
            val permissibleValues =
              enumView._enum.permissibleValues.values.foldRight(Rdf.nil: Resource) { (pv, acc) =>
                val listNode = blankNode()
                pv.meaning match {
                  case Some(m) =>
                    addTriple(
                      listNode,
                      Rdf.first,
                      Iri(m.uri(using enumView.definingPrefixResolver)),
                    )
                    addTriple(listNode, Rdf.rest, acc)
                  case _ =>
                    addTriple(
                      listNode,
                      Rdf.first,
                      Literal(pv.text),
                    )
                    addTriple(listNode, Rdf.rest, acc)
                }
                listNode
              }
            addTriple(property, Shacl.in, permissibleValues)
          case _ => throw RuntimeException(s"Couldn't map range ${slotExpression.range}")
        }
      if slotExpression.anyOf.nonEmpty then {
        val or
        addTriple(property, Shacl.or)
        slotExpression.anyOf.foreach { anonExpr => {

        }
        }
      }

    }

    def processSlot(s: SlotView, order: Int, propertyDomain: Iri): Unit = {
      val slot = s.slot
      processSlotExpr(s, slot, propertyDomain)
      val property = blankNode()
      addTriple(propertyDomain, Shacl.property, property)
      addTriple(property, Shacl.order, Literal(order.toString, XmlSchema.integer))
      addTriple(property, Shacl.path, Iri(s.uriStr))
    }

    def addShaclList(values: Seq[Node]): Option[BlankNode] = {
      if values.isEmpty then return None
      val start = blankNode()
      val firstNode = values.head
      addTriple(start, Rdf.first, firstNode)
      //todo fresh brain for this
      var next = blankNode()
      values.foldLeft(()) { (_, value) =>
        addTriple(next, Rdf.rest, next)
        next = next
      }
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
      .distinct.sorted.foreach(addNamespace)

    classes.values.foreach { c =>
      val classNameIri = Iri(c.uriStr)
      addTriple(classNameIri, Rdf.`type`, Shacl.NodeShape)
      c.cls.description match {
        case Some(d) => addTriple(classNameIri, Rdfs.comment, Literal(d, XmlSchema.string))
        case _ =>
      }
      val closed = !(enforceOpenShapes || c.cls.`abstract` || c.cls.mixin)
      addTriple(classNameIri, Shacl.closed, Literal(closed.toString, XmlSchema.boolean))
      val ignoredProperties = blankNode()
      addTriple(classNameIri, Shacl.ignoredProperties, ignoredProperties)
      addTriple(ignoredProperties, Rdf.first, Rdf.`type`)
      if c.hasIdentifier then {
        val ignoredId = blankNode()
        addTriple(ignoredProperties, Rdf.rest, ignoredId)
        addTriple(ignoredId, Rdf.first, Iri(c.identifier.get.uriStr))
        addTriple(ignoredId, Rdf.rest, Rdf.nil)
      } else addTriple(ignoredProperties, Rdf.rest, Rdf.nil)
      var order = 0
      c.derivedAttributes.values.filter(!_.inner.identifier).foreach { x =>
        processSlot(x, order, classNameIri); order += 1
      }
      addTriple(classNameIri, Shacl.targetClass, classNameIri)
    }
    (namespaces.result(), triples.result())
  }
}

object Shacl {
  val BlankNodeOrIRI: Iri = Iri("http://www.w3.org/ns/shacl#BlankNodeOrIRI")
  val IRI: Iri = Iri("http://www.w3.org/ns/shacl#IRI")
  val Literal: Iri = Iri("http://www.w3.org/ns/shacl#Literal")
  val NodeShape: Iri = Iri("http://www.w3.org/ns/shacl#NodeShape")
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
