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
    // The first namespace for each prefix is declared.
    val prefixes = mutable.HashSet.empty[String]
    def declare(prefix: String, namespace: String): Unit =
      if (prefixes.add(prefix)) sink.namespace(prefix, namespace)
    standardNamespaces.foreach(ns => declare(ns._1, ns._2))
    ontology.prefixes.foreach(ns => declare(ns._1, ns._2))
    commonNamespaces.foreach(ns => if (used(ns._2)) declare(ns._1, ns._2))

    writeHeader(ontology)
    // Axioms grouped by subject entity, in first-seen order, and the rest separately.
    val bySubject = mutable.HashMap.empty[String, mutable.ArrayBuffer[Axiom]]
    val subjects = mutable.ArrayBuffer.empty[String]
    val general = mutable.ArrayBuffer.empty[Axiom]
    ontology.axioms.foreach { axiom =>
      val subject = subjectOf(axiom)
      if (subject eq null) general.addOne(axiom)
      else {
        var axioms = bySubject.getOrElse(subject, null)
        if (axioms eq null) {
          axioms = mutable.ArrayBuffer.empty
          bySubject.update(subject, axioms)
          subjects.addOne(subject)
        }
        axioms.addOne(axiom)
      }
    }
    subjects.foreach { subject =>
      val axioms = bySubject(subject)
      // Declarations first, then annotations, then the rest, each in their order.
      axioms.foreach(a => if (a.isInstanceOf[Declaration]) writeAxiom(a))
      axioms.foreach(a => if (a.isInstanceOf[AnnotationAssertion]) writeAxiom(a))
      axioms.foreach { a =>
        if (!a.isInstanceOf[Declaration] && !a.isInstanceOf[AnnotationAssertion]) writeAxiom(a)
      }
      drain()
    }
    general.foreach { axiom =>
      writeAxiom(axiom)
      drain()
    }
  }

  private def writeHeader(ontology: Ontology): Unit = {
    val subject: Resource = if (ontology.iri.isDefined) Iri(ontology.iri.get) else nextBlank()
    sink.triple(subject, Rdf.`type`, Owl.Ontology)
    if (ontology.versionIri.isDefined) {
      sink.triple(subject, Owl.versionIRI, Iri(ontology.versionIri.get))
    }
    ontology.imports.foreach(i => sink.triple(subject, Owl.imports, Iri(i)))
    ontology.annotations.foreach(a => sink.triple(subject, Iri(a.property), a.value))
    ontology.prefixes.foreach { p =>
      val declaration = inlineNode()
      sink.triple(subject, Shacl.declare, declaration)
      sink.triple(declaration, shaclPrefix, Literal(p._1))
      sink.triple(declaration, Shacl.namespace, Literal(p._2, XmlSchema.anyURI))
    }
  }

  private def writeAxiom(axiom: Axiom): Unit = axiom match {
    case a: Declaration => main(Iri(a.iri), Rdf.`type`, declarationType(a.kind), a.annotations)
    case a: SubClassOf =>
      a.sub match {
        case n: ClassExpr.Named => main(Iri(n.iri), Rdfs.subClassOf, a.sup, a.annotations)
        case sub =>
          // General class inclusion: the subject is an expression, so it gets its own blank node.
          val node = nextBlank()
          writeClassBody(node, sub)
          main(node, Rdfs.subClassOf, a.sup, a.annotations)
      }
    case a: EquivalentClasses =>
      foreachOtherOperand(a.operands)((first, other) =>
        main(first, Owl.equivalentClass, other, a.annotations),
      )
    case a: DisjointClasses =>
      if (a.operands.length == 2) {
        foreachOtherOperand(a.operands)((first, other) =>
          main(first, Owl.disjointWith, other, a.annotations),
        )
      } else {
        val node = nextBlank()
        sink.triple(node, Rdf.`type`, Owl.AllDisjointClasses)
        list(node, Owl.members, a.operands)
        annotate(node, a.annotations)
      }
    case a: DisjointUnion =>
      val node = Iri(a.cls)
      list(node, Owl.disjointUnionOf, a.operands)
      annotateMain(node, Owl.disjointUnionOf, null, a.annotations)
    case a: SubPropertyOf => main(Iri(a.sub), Rdfs.subPropertyOf, Iri(a.sup), a.annotations)
    case a: PropertyChain =>
      val node = Iri(a.sup)
      list(node, Owl.propertyChainAxiom, a.chain)
      annotateMain(node, Owl.propertyChainAxiom, null, a.annotations)
    case a: EquivalentProperties =>
      val first = Iri(a.operands.head)
      a.operands.iterator.drop(1)
        .foreach(o => main(first, Owl.equivalentProperty, Iri(o), a.annotations))
    case a: DisjointProperties =>
      if (a.operands.length == 2) {
        main(Iri(a.operands.head), Owl.propertyDisjointWith, Iri(a.operands(1)), a.annotations)
      } else {
        val node = nextBlank()
        sink.triple(node, Rdf.`type`, Owl.AllDisjointProperties)
        sink.list(node, Owl.members, iris(a.operands))
        annotate(node, a.annotations)
      }
    case a: InverseProperties => main(Iri(a.first), Owl.inverseOf, Iri(a.second), a.annotations)
    case a: Domain => main(Iri(a.property), Rdfs.domain, a.domain, a.annotations)
    case a: Range => main(Iri(a.property), Rdfs.range, a.range, a.annotations)
    case a: Characteristic =>
      main(Iri(a.property), Rdf.`type`, a.characteristic.iri, a.annotations)
    case a: DatatypeDefinition => main(Iri(a.datatype), Owl.equivalentClass, a.range, a.annotations)
    case a: HasKey =>
      val subject: Resource = a.cls match {
        case n: ClassExpr.Named => Iri(n.iri)
        case expr =>
          val node = nextBlank()
          writeClassBody(node, expr)
          node
      }
      sink.list(subject, Owl.hasKey, iris(a.properties))
      annotateMain(subject, Owl.hasKey, null, a.annotations)
    case a: ClassAssertion => main(Iri(a.individual), Rdf.`type`, a.cls, a.annotations)
    case a: PropertyAssertion =>
      main(Iri(a.individual), Iri(a.property), a.value, a.annotations)
    case a: SameIndividual =>
      val first = Iri(a.operands.head)
      a.operands.iterator.drop(1).foreach(o => main(first, Owl.sameAs, Iri(o), a.annotations))
    case a: DifferentIndividuals =>
      val node = nextBlank()
      sink.triple(node, Rdf.`type`, Owl.AllDifferent)
      sink.list(node, Owl.distinctMembers, iris(a.operands))
      annotate(node, a.annotations)
    case a: AnnotationAssertion =>
      main(Iri(a.subject), Iri(a.annotation.property), a.annotation.value, a.annotations)
  }

  /** Calls `f` with the subject and each other operand. The subject is the first named class, or a
    * blank node if no operand is named.
    */
  private def foreachOtherOperand(
      operands: Seq[ClassExpr],
  )(f: (Resource, ClassExpr) => Unit): Unit = {
    val i = operands.indexWhere(_.isInstanceOf[ClassExpr.Named])
    if (i < 0) {
      val node = nextBlank()
      writeClassBody(node, operands.head)
      operands.iterator.drop(1).foreach(o => f(node, o))
    } else {
      val first = Iri(operands(i).asInstanceOf[ClassExpr.Named].iri)
      var j = 0
      operands.foreach { o =>
        if (j != i) f(first, o)
        j += 1
      }
    }
  }

  private def iris(values: Seq[String]): Seq[Node] = {
    val out = Vector.newBuilder[Node]
    values.foreach(v => out.addOne(Iri(v)))
    out.result()
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
      case n: ClassExpr.Named => Iri(n.iri)
      case d: DataRange.Datatype => Iri(d.iri)
      case expr: (ClassExpr | DataRange) =>
        if (annotations.isEmpty) {
          val node = inlineNode()
          sink.triple(subject, predicate, node)
          writeBody(node, expr)
          return
        } else {
          val node = nextBlank()
          deferred.addOne(() => writeBody(node, expr))
          node
        }
    }
    sink.triple(subject, predicate, target)
    annotateMain(subject, predicate, target, annotations)
  }

  /** Writes axiom annotations on an `owl:Axiom` node that points at the main triple. If the object
    * is a list, the target (`null`) isn't known here, so the annotations are dropped.
    */
  private def annotateMain(
      subject: Resource,
      predicate: Iri,
      target: Node,
      annotations: Seq[Annotation],
  ): Unit =
    if (annotations.nonEmpty && (target ne null)) {
      deferred.addOne { () =>
        val node = nextBlank()
        sink.triple(node, Rdf.`type`, Owl.Axiom)
        sink.triple(node, Owl.annotatedSource, subject)
        sink.triple(node, Owl.annotatedProperty, predicate)
        sink.triple(node, Owl.annotatedTarget, target)
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
    case e: ClassExpr.Named =>
      // Only reached for an axiom whose subject has to be a blank node.
      sink.triple(node, Owl.equivalentClass, Iri(e.iri))
    case e: ClassExpr.IntersectionOf =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      list(node, Owl.intersectionOf, e.operands)
    case e: ClassExpr.UnionOf =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      list(node, Owl.unionOf, e.operands)
    case e: ClassExpr.ComplementOf =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      objectOf(node, Owl.complementOf, e.operand)
    case e: ClassExpr.OneOf =>
      sink.triple(node, Rdf.`type`, Owl.Class)
      sink.list(node, Owl.oneOf, iris(e.individuals))
    case e: ClassExpr.SomeValuesFrom =>
      restriction(node, e.property)
      objectOf(node, Owl.someValuesFrom, e.filler)
    case e: ClassExpr.AllValuesFrom =>
      restriction(node, e.property)
      objectOf(node, Owl.allValuesFrom, e.filler)
    case e: ClassExpr.HasValue =>
      restriction(node, e.property)
      sink.triple(node, Owl.hasValue, e.value)
    case e: ClassExpr.HasSelf =>
      restriction(node, e.property)
      sink.triple(node, Owl.hasSelf, Literal("true", XmlSchema.boolean))
    case e: ClassExpr.Cardinality =>
      restriction(node, e.property)
      val count = Literal(e.n.toString, XmlSchema.nonNegativeInteger)
      if (e.filler.isEmpty) {
        val predicate = e.bound match {
          case ClassExpr.Bound.Min => Owl.minCardinality
          case ClassExpr.Bound.Max => Owl.maxCardinality
          case ClassExpr.Bound.Exact => Owl.cardinality
        }
        sink.triple(node, predicate, count)
      } else {
        val predicate = e.bound match {
          case ClassExpr.Bound.Min => Owl.minQualifiedCardinality
          case ClassExpr.Bound.Max => Owl.maxQualifiedCardinality
          case ClassExpr.Bound.Exact => Owl.qualifiedCardinality
        }
        sink.triple(node, predicate, count)
        e.filler.get match {
          case c: ClassExpr => objectOf(node, Owl.onClass, c)
          case d: DataRange => objectOf(node, Owl.onDataRange, d)
        }
      }
  }

  private def restriction(node: Resource, property: PropertyRef): Unit = {
    sink.triple(node, Rdf.`type`, Owl.Restriction)
    if (property.inverse) {
      val inverse = inlineNode()
      sink.triple(node, Owl.onProperty, inverse)
      sink.triple(inverse, Owl.inverseOf, Iri(property.iri))
    } else sink.triple(node, Owl.onProperty, Iri(property.iri))
  }

  private def writeDataBody(node: Resource, range: DataRange): Unit = {
    sink.triple(node, Rdf.`type`, Rdfs.Datatype)
    range match {
      case r: DataRange.Datatype => sink.triple(node, Owl.equivalentClass, Iri(r.iri))
      case r: DataRange.IntersectionOf => list(node, Owl.intersectionOf, r.operands)
      case r: DataRange.UnionOf => list(node, Owl.unionOf, r.operands)
      case r: DataRange.ComplementOf => objectOf(node, Owl.datatypeComplementOf, r.operand)
      case r: DataRange.OneOf => sink.list(node, Owl.oneOf, r.values)
      case r: DataRange.Restriction =>
        sink.triple(node, Owl.onDatatype, Iri(r.datatype))
        val facetNodes = Vector.newBuilder[Node]
        r.facets.foreach { f =>
          val facetNode = nextBlank()
          deferred.addOne(() => sink.triple(facetNode, Iri(f._1), f._2))
          facetNodes.addOne(facetNode)
        }
        sink.list(node, Owl.withRestrictions, facetNodes.result())
    }
  }

  /** Writes `node predicate expr`, with the expression inline unless it is named. */
  private def objectOf(node: Resource, predicate: Iri, expr: Filler): Unit = expr match {
    case n: ClassExpr.Named => sink.triple(node, predicate, Iri(n.iri))
    case d: DataRange.Datatype => sink.triple(node, predicate, Iri(d.iri))
    case other =>
      val child = inlineNode()
      sink.triple(node, predicate, child)
      writeBody(child, other)
  }

  /** Anonymous members get a labelled blank node, written after the current statement. */
  private def list(node: Resource, predicate: Iri, members: Seq[Filler | PropertyRef]): Unit = {
    val nodes = Vector.newBuilder[Node]
    members.foreach {
      case n: ClassExpr.Named => nodes.addOne(Iri(n.iri))
      case d: DataRange.Datatype => nodes.addOne(Iri(d.iri))
      case p: PropertyRef =>
        if (!p.inverse) nodes.addOne(Iri(p.iri))
        else {
          val member = nextBlank()
          deferred.addOne(() => sink.triple(member, Owl.inverseOf, Iri(p.iri)))
          nodes.addOne(member)
        }
      case expr: (ClassExpr | DataRange) =>
        val member = nextBlank()
        deferred.addOne(() => writeBody(member, expr))
        nodes.addOne(member)
    }
    sink.list(node, predicate, nodes.result())
  }

  /** Writes the deferred triples. Writing one can defer more, and those are written too. */
  private def drain(): Unit = {
    var i = 0
    while (i < deferred.length) {
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
      case i: Iri => iris.addOne(i.value)
      case l: Literal => iris.addOne(l.datatype.value)
      case _ =>
    }
    ontology.annotations.foreach { a =>
      iris.addOne(a.property)
      node(a.value)
    }
    ontology.axioms.foreach {
      case a: AnnotationAssertion =>
        iris.addOne(a.annotation.property)
        node(a.annotation.value)
      case other =>
        val subject = subjectOf(other)
        if (subject ne null) iris.addOne(subject)
    }
    if (ontology.prefixes.nonEmpty) iris.addOne(Shacl.declare.value)
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

  /** The subject of an axiom's main triple if it is a named entity, or `null` otherwise. */
  private def subjectOf(axiom: Axiom): String = axiom match {
    case a: Declaration => a.iri
    case a: SubClassOf =>
      a.sub match {
        case n: ClassExpr.Named => n.iri
        case _ => null
      }
    case a: EquivalentClasses => firstNamed(a.operands)
    case a: DisjointClasses => if (a.operands.length == 2) firstNamed(a.operands) else null
    case a: DisjointUnion => a.cls
    case a: SubPropertyOf => a.sub
    case a: PropertyChain => a.sup
    case a: EquivalentProperties => if (a.operands.nonEmpty) a.operands.head else null
    case a: DisjointProperties => if (a.operands.length == 2) a.operands.head else null
    case a: InverseProperties => a.first
    case a: Domain => a.property
    case a: Range => a.property
    case a: Characteristic => a.property
    case a: DatatypeDefinition => a.datatype
    case a: HasKey =>
      a.cls match {
        case n: ClassExpr.Named => n.iri
        case _ => null
      }
    case a: ClassAssertion => a.individual
    case a: PropertyAssertion => a.individual
    case a: SameIndividual => if (a.operands.nonEmpty) a.operands.head else null
    case _: DifferentIndividuals => null
    case a: AnnotationAssertion => a.subject
  }

  /** The first named class, or `null` if there is none. */
  private def firstNamed(operands: Seq[ClassExpr]): String = {
    val it = operands.iterator
    while (it.hasNext) {
      it.next() match {
        case n: ClassExpr.Named => return n.iri
        case _ =>
      }
    }
    null
  }
}
