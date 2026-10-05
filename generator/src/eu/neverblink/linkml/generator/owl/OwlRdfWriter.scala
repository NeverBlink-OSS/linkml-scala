package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.rdf.*

import scala.collection.mutable

/** Writes an [[Ontology]] as RDF, following the OWL 2 mapping to RDF graphs
  * ([[https://www.w3.org/TR/owl2-mapping-to-rdf/]]).
  *
  * Triples are grouped by subject, so Turtle can write each entity as one statement. The ontology
  * header comes first, then each entity with all its axioms, then axioms with no named subject
  * (such as general class inclusions).
  */
final class OwlRdfWriter(sink: RdfSink) {
  import OwlRdfWriter.*

  private var blankNodes = 0

  /** Triples about blank nodes, written after the statement that refers to them is finished. */
  private val deferred = mutable.ArrayBuffer.empty[() => Unit]

  def write(ontology: Ontology): Unit = {
    val used = usedNamespaces(ontology)
    val namespaces =
      (standardNamespaces ++ ontology.prefixes ++ commonNamespaces.filter(ns => used(ns._2)))
        .foldLeft(Vector.empty[(String, String)]) { (acc, ns) =>
          if acc.exists(_._1 == ns._1) then acc else acc :+ ns
        }
    namespaces.foreach(sink.namespace)

    writeHeader(ontology)
    val (bySubject, general) = groupBySubject(ontology.axioms)
    bySubject.foreach { (_, axioms) =>
      axioms.sortBy {
        case _: Declaration => 0
        case _: AnnotationAssertion => 1
        case _ => 2
      }.foreach(writeAxiom)
      drain()
    }
    general.foreach { axiom =>
      writeAxiom(axiom)
      drain()
    }
  }

  private def writeHeader(ontology: Ontology): Unit = {
    val subject: Resource = ontology.iri.fold(nextBlank())(Iri(_))
    sink.triple(subject, Rdf.`type`, Owl.Ontology)
    ontology.versionIri.foreach(v => sink.triple(subject, Owl.versionIRI, Iri(v)))
    ontology.imports.foreach(i => sink.triple(subject, Owl.imports, Iri(i)))
    ontology.annotations.foreach(a => sink.triple(subject, Iri(a.property), a.value))
    ontology.prefixes.foreach { (prefix, namespace) =>
      val declaration = inlineNode()
      sink.triple(subject, Shacl.declare, declaration)
      sink.triple(declaration, shaclPrefix, Literal(prefix))
      sink.triple(declaration, Shacl.namespace, Literal(namespace, XmlSchema.anyURI))
    }
  }

  /** Groups axioms by subject entity, in first-seen order, and returns the rest separately. */
  private def groupBySubject(
      axioms: Seq[Axiom],
  ): (mutable.LinkedHashMap[String, mutable.ArrayBuffer[Axiom]], Seq[Axiom]) = {
    val grouped = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Axiom]]
    val general = Seq.newBuilder[Axiom]
    axioms.foreach { axiom =>
      subjectOf(axiom) match {
        case Some(iri) => grouped.getOrElseUpdate(iri, mutable.ArrayBuffer.empty) += axiom
        case None => general += axiom
      }
    }
    (grouped, general.result())
  }

  private def writeAxiom(axiom: Axiom): Unit = axiom match {
    case Declaration(kind, iri, anns) =>
      main(Iri(iri), Rdf.`type`, declarationType(kind), anns)
    case SubClassOf(ClassExpr.Named(sub), sup, anns) =>
      main(Iri(sub), Rdfs.subClassOf, sup, anns)
    case SubClassOf(sub, sup, anns) =>
      // General class inclusion: the subject is an expression, so it gets its own blank node.
      val node = nextBlank()
      writeClassBody(node, sub)
      main(node, Rdfs.subClassOf, sup, anns)
    case EquivalentClasses(operands, anns) =>
      val (first, rest) = splitNamed(operands)
      rest.foreach(other => main(first, Owl.equivalentClass, other, anns))
    case DisjointClasses(Seq(a, b), anns) =>
      val (first, rest) = splitNamed(Seq(a, b))
      main(first, Owl.disjointWith, rest.head, anns)
    case DisjointClasses(operands, anns) =>
      val node = nextBlank()
      sink.triple(node, Rdf.`type`, Owl.AllDisjointClasses)
      list(node, Owl.members, operands)
      annotate(node, anns)
    case DisjointUnion(cls, operands, anns) =>
      val node = Iri(cls)
      list(node, Owl.disjointUnionOf, operands)
      annotateMain(node, Owl.disjointUnionOf, None, anns)
    case SubPropertyOf(sub, sup, anns) =>
      main(Iri(sub), Rdfs.subPropertyOf, Iri(sup), anns)
    case PropertyChain(sup, chain, anns) =>
      list(Iri(sup), Owl.propertyChainAxiom, chain)
      annotateMain(Iri(sup), Owl.propertyChainAxiom, None, anns)
    case EquivalentProperties(operands, anns) =>
      operands.tail.foreach(o => main(Iri(operands.head), Owl.equivalentProperty, Iri(o), anns))
    case DisjointProperties(Seq(a, b), anns) =>
      main(Iri(a), Owl.propertyDisjointWith, Iri(b), anns)
    case DisjointProperties(operands, anns) =>
      val node = nextBlank()
      sink.triple(node, Rdf.`type`, Owl.AllDisjointProperties)
      sink.list(node, Owl.members, operands.map(Iri(_)))
      annotate(node, anns)
    case InverseProperties(first, second, anns) =>
      main(Iri(first), Owl.inverseOf, Iri(second), anns)
    case Domain(property, domain, anns) =>
      main(Iri(property), Rdfs.domain, domain, anns)
    case Range(property, range, anns) =>
      main(Iri(property), Rdfs.range, range, anns)
    case Characteristic(property, characteristic, anns) =>
      main(Iri(property), Rdf.`type`, characteristic.iri, anns)
    case DatatypeDefinition(datatype, range, anns) =>
      main(Iri(datatype), Owl.equivalentClass, range, anns)
    case HasKey(cls, properties, anns) =>
      val subject: Resource = cls match {
        case ClassExpr.Named(iri) => Iri(iri)
        case expr =>
          val node = nextBlank()
          writeClassBody(node, expr)
          node
      }
      sink.list(subject, Owl.hasKey, properties.map(Iri(_)))
      annotateMain(subject, Owl.hasKey, None, anns)
    case ClassAssertion(cls, individual, anns) =>
      main(Iri(individual), Rdf.`type`, cls, anns)
    case PropertyAssertion(property, individual, value, anns) =>
      main(Iri(individual), Iri(property), value, anns)
    case SameIndividual(operands, anns) =>
      operands.tail.foreach(o => main(Iri(operands.head), Owl.sameAs, Iri(o), anns))
    case DifferentIndividuals(operands, anns) =>
      val node = nextBlank()
      sink.triple(node, Rdf.`type`, Owl.AllDifferent)
      sink.list(node, Owl.distinctMembers, operands.map(Iri(_)))
      annotate(node, anns)
    case AnnotationAssertion(subject, annotation, anns) =>
      main(Iri(subject), Iri(annotation.property), annotation.value, anns)
  }

  /** Uses the first named class as the subject and returns it with the other operands. If no
    * operand is named, the subject is a blank node.
    */
  private def splitNamed(operands: Seq[ClassExpr]): (Resource, Seq[ClassExpr]) =
    operands.indexWhere(_.isInstanceOf[ClassExpr.Named]) match {
      case -1 =>
        val node = nextBlank()
        writeClassBody(node, operands.head)
        (node, operands.tail)
      case i =>
        (Iri(operands(i).asInstanceOf[ClassExpr.Named].iri), operands.patch(i, Nil, 1))
    }

  /** Writes the main triple of an axiom and its annotations.
    *
    * An expression object is written inline, unless the axiom has annotations. Then the `owl:Axiom`
    * node must point at the expression too, so it gets a labelled blank node.
    */
  private def main(
      subject: Resource,
      predicate: Iri,
      obj: Filler | Node,
      annotations: Seq[Annotation],
  ): Unit = {
    val target: Node = obj match {
      case node: Node => node
      case ClassExpr.Named(iri) => Iri(iri)
      case DataRange.Datatype(iri) => Iri(iri)
      case expr: (ClassExpr | DataRange) =>
        if annotations.isEmpty then {
          val node = inlineNode()
          sink.triple(subject, predicate, node)
          writeBody(node, expr)
          return
        } else {
          val node = nextBlank()
          deferred += (() => writeBody(node, expr))
          node
        }
    }
    sink.triple(subject, predicate, target)
    annotateMain(subject, predicate, Some(target), annotations)
  }

  /** Writes axiom annotations on an `owl:Axiom` node that points at the main triple. If the object
    * is a list, the target isn't known here, so the annotations are dropped.
    */
  private def annotateMain(
      subject: Resource,
      predicate: Iri,
      target: Option[Node],
      annotations: Seq[Annotation],
  ): Unit =
    if annotations.nonEmpty then
      target.foreach { t =>
        deferred += { () =>
          val node = nextBlank()
          sink.triple(node, Rdf.`type`, Owl.Axiom)
          sink.triple(node, Owl.annotatedSource, subject)
          sink.triple(node, Owl.annotatedProperty, predicate)
          sink.triple(node, Owl.annotatedTarget, t)
          annotate(node, annotations)
        }
      }

  private def annotate(node: Resource, annotations: Seq[Annotation]): Unit =
    annotations.foreach(a => sink.triple(node, Iri(a.property), a.value))

  private def writeBody(node: Resource, expr: ClassExpr | DataRange): Unit = expr match {
    case c: ClassExpr => writeClassBody(node, c)
    case d: DataRange => writeDataBody(node, d)
  }

  private def writeClassBody(node: Resource, expr: ClassExpr): Unit = expr match {
    case ClassExpr.Named(iri) =>
      // Only reached for an axiom whose subject has to be a blank node.
      sink.triple(node, Owl.equivalentClass, Iri(iri))
    case ClassExpr.IntersectionOf(operands) =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      list(node, Owl.intersectionOf, operands)
    case ClassExpr.UnionOf(operands) =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      list(node, Owl.unionOf, operands)
    case ClassExpr.ComplementOf(operand) =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      objectOf(node, Owl.complementOf, operand)
    case ClassExpr.OneOf(individuals) =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      sink.list(node, Owl.oneOf, individuals.map(Iri(_)))
    case ClassExpr.SomeValuesFrom(property, filler) =>
      restriction(node, property)
      objectOf(node, Owl.someValuesFrom, filler)
    case ClassExpr.AllValuesFrom(property, filler) =>
      restriction(node, property)
      objectOf(node, Owl.allValuesFrom, filler)
    case ClassExpr.HasValue(property, value) =>
      restriction(node, property)
      sink.triple(node, Owl.hasValue, value)
    case ClassExpr.HasSelf(property) =>
      restriction(node, property)
      sink.triple(node, Owl.hasSelf, Literal("true", XmlSchema.boolean))
    case ClassExpr.Cardinality(bound, n, property, filler) =>
      restriction(node, property)
      val count = Literal(n.toString, XmlSchema.nonNegativeInteger)
      filler match {
        case None =>
          val predicate = bound match {
            case ClassExpr.Bound.Min => Owl.minCardinality
            case ClassExpr.Bound.Max => Owl.maxCardinality
            case ClassExpr.Bound.Exact => Owl.cardinality
          }
          sink.triple(node, predicate, count)
        case Some(f) =>
          val predicate = bound match {
            case ClassExpr.Bound.Min => Owl.minQualifiedCardinality
            case ClassExpr.Bound.Max => Owl.maxQualifiedCardinality
            case ClassExpr.Bound.Exact => Owl.qualifiedCardinality
          }
          sink.triple(node, predicate, count)
          f match {
            case c: ClassExpr => objectOf(node, Owl.onClass, c)
            case d: DataRange => objectOf(node, Owl.onDataRange, d)
          }
      }
  }

  private def restriction(node: Resource, property: PropertyRef): Unit = {
    sink.triple(node, Rdf.`type`, Owl.Restriction)
    if property.inverse then {
      val inverse = inlineNode()
      sink.triple(node, Owl.onProperty, inverse)
      sink.triple(inverse, Owl.inverseOf, Iri(property.iri))
    } else sink.triple(node, Owl.onProperty, Iri(property.iri))
  }

  private def writeDataBody(node: Resource, range: DataRange): Unit = {
    sink.triple(node, Rdf.`type`, Rdfs.Datatype)
    range match {
      case DataRange.Datatype(iri) => sink.triple(node, Owl.equivalentClass, Iri(iri))
      case DataRange.IntersectionOf(operands) => list(node, Owl.intersectionOf, operands)
      case DataRange.UnionOf(operands) => list(node, Owl.unionOf, operands)
      case DataRange.ComplementOf(operand) => objectOf(node, Owl.datatypeComplementOf, operand)
      case DataRange.OneOf(values) => sink.list(node, Owl.oneOf, values)
      case DataRange.Restriction(datatype, facets) =>
        sink.triple(node, Owl.onDatatype, Iri(datatype))
        val facetNodes = facets.map { (facet, value) =>
          val facetNode = nextBlank()
          deferred += (() => sink.triple(facetNode, Iri(facet), value))
          facetNode
        }
        sink.list(node, Owl.withRestrictions, facetNodes)
    }
  }

  /** Writes `node predicate expr`, with the expression inline unless it is named. */
  private def objectOf(node: Resource, predicate: Iri, expr: Filler): Unit = expr match {
    case ClassExpr.Named(iri) => sink.triple(node, predicate, Iri(iri))
    case DataRange.Datatype(iri) => sink.triple(node, predicate, Iri(iri))
    case other =>
      val child = inlineNode()
      sink.triple(node, predicate, child)
      writeBody(child, other)
  }

  /** Anonymous members get a labelled blank node, written after the current statement. */
  private def list(node: Resource, predicate: Iri, members: Seq[Filler | PropertyRef]): Unit = {
    val nodes = members.map {
      case ClassExpr.Named(iri) => Iri(iri)
      case DataRange.Datatype(iri) => Iri(iri)
      case PropertyRef(iri, false) => Iri(iri)
      case PropertyRef(iri, true) =>
        val member = nextBlank()
        deferred += (() => sink.triple(member, Owl.inverseOf, Iri(iri)))
        member
      case expr: (ClassExpr | DataRange) =>
        val member = nextBlank()
        deferred += (() => writeBody(member, expr))
        member
    }
    sink.list(node, predicate, nodes)
  }

  /** Writes the deferred triples. Writing one can defer more, and those are written too. */
  private def drain(): Unit = {
    var i = 0
    while i < deferred.length do {
      deferred(i)()
      i += 1
    }
    deferred.clear()
  }

  private def nextBlank(): BlankNode = {
    blankNodes += 1
    BlankNode("o" + blankNodes)
  }

  private def inlineNode(): InlineBlankNode = {
    blankNodes += 1
    InlineBlankNode("o" + blankNodes)
  }
}

object OwlRdfWriter {

  def write(ontology: Ontology, sink: RdfSink): Unit = OwlRdfWriter(sink).write(ontology)

  /** Always declared, so the OWL vocabulary itself is written with short prefixes. */
  val standardNamespaces: Seq[(String, String)] = Seq(
    "owl" -> "http://www.w3.org/2002/07/owl#",
    "rdf" -> "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "rdfs" -> "http://www.w3.org/2000/01/rdf-schema#",
    "xsd" -> "http://www.w3.org/2001/XMLSchema#",
  )

  /** Declared only when the ontology uses them. Mostly for annotation properties. */
  val commonNamespaces: Seq[(String, String)] = Seq(
    "dcterms" -> "http://purl.org/dc/terms/",
    "skos" -> "http://www.w3.org/2004/02/skos/core#",
    "pav" -> "http://purl.org/pav/",
    "schema" -> "http://schema.org/",
    "sh" -> "http://www.w3.org/ns/shacl#",
    "vann" -> "http://purl.org/vocab/vann/",
  )

  /** Returns a test for whether a namespace is used. Only annotations and subjects are checked. */
  private def usedNamespaces(ontology: Ontology): String => Boolean = {
    val iris = mutable.HashSet.empty[String]
    def node(n: Node): Unit = n match {
      case Iri(v) => iris += v
      case Literal(_, Iri(d)) => iris += d
      case _ =>
    }
    ontology.annotations.foreach(a => { iris += a.property; node(a.value) })
    ontology.axioms.foreach {
      case AnnotationAssertion(_, a, _) => iris += a.property; node(a.value)
      case other => iris ++= OwlRdfWriter.subjectOf(other)
    }
    if ontology.prefixes.nonEmpty then iris += Shacl.declare.value
    ns => iris.exists(_.startsWith(ns))
  }

  private def declarationType(kind: EntityKind): Iri = kind match {
    case EntityKind.Class => Owl.Class
    case EntityKind.Datatype => Rdfs.Datatype
    case EntityKind.ObjectProperty => Owl.ObjectProperty
    case EntityKind.DataProperty => Owl.DatatypeProperty
    case EntityKind.AnnotationProperty => Owl.AnnotationProperty
    case EntityKind.NamedIndividual => Owl.NamedIndividual
  }

  /** The subject of an axiom's main triple, if it is a named entity. */
  def subjectOf(axiom: Axiom): Option[String] = axiom match {
    case Declaration(_, iri, _) => Some(iri)
    case SubClassOf(ClassExpr.Named(sub), _, _) => Some(sub)
    case SubClassOf(_, _, _) => None
    case EquivalentClasses(operands, _) => firstNamed(operands)
    case DisjointClasses(Seq(a, b), _) => firstNamed(Seq(a, b))
    case DisjointClasses(_, _) => None
    case DisjointUnion(cls, _, _) => Some(cls)
    case SubPropertyOf(sub, _, _) => Some(sub)
    case PropertyChain(sup, _, _) => Some(sup)
    case EquivalentProperties(operands, _) => operands.headOption
    case DisjointProperties(Seq(a, _), _) => Some(a)
    case DisjointProperties(_, _) => None
    case InverseProperties(first, _, _) => Some(first)
    case Domain(property, _, _) => Some(property)
    case Range(property, _, _) => Some(property)
    case Characteristic(property, _, _) => Some(property)
    case DatatypeDefinition(datatype, _, _) => Some(datatype)
    case HasKey(ClassExpr.Named(cls), _, _) => Some(cls)
    case HasKey(_, _, _) => None
    case ClassAssertion(_, individual, _) => Some(individual)
    case PropertyAssertion(_, individual, _, _) => Some(individual)
    case SameIndividual(operands, _) => operands.headOption
    case DifferentIndividuals(_, _) => None
    case AnnotationAssertion(subject, _, _) => Some(subject)
  }

  private def firstNamed(operands: Seq[ClassExpr]): Option[String] =
    operands.collectFirst { case ClassExpr.Named(iri) => iri }
}
