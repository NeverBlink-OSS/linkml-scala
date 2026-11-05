package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.runtime.FastUtils.*

import scala.collection.mutable

/** Reads an [[Ontology]] from RDF triples, following the OWL 2 mapping to RDF graphs
  * ([[https://www.w3.org/TR/owl2-mapping-to-rdf/]]). It also accepts some RDFS and OWL 1 patterns:
  *   - `rdfs:Class` is read as `owl:Class`. `rdf:Property` is read as an object property, or as a
  *     data property when its range is a datatype.
  *   - `owl:unionOf` and the like directly on a named class make the class equivalent to that
  *     expression.
  *   - A triple whose predicate is not a declared object or data property is read as an annotation,
  *     whatever its subject is.
  *
  * Triples that can't be read end up in [[OwlRdfReader.Result.unparsed]], so callers can report
  * them.
  */
object OwlRdfReader {

  final case class Result(ontology: Ontology, unparsed: Seq[Triple])

  /** A graph is a set, so a repeated triple counts once. */
  def read(triples: Seq[Triple]): Result = new Reader(Graph(triples)).read()

  /** True for built-in datatypes, which need no declaration. */
  def isBuiltInDatatype(iri: String): Boolean =
    iri.startsWith(XmlSchema.prefix) || builtInDatatypes.contains(iri)

  private val builtInDatatypes: Set[String] = Set(
    Rdfs.Literal.value,
    Rdf.langString.value,
    Rdf.get("PlainLiteral").value,
    Rdf.get("XMLLiteral").value,
    Rdf.get("HTML").value,
    Rdf.get("JSON").value,
    Owl.get("real").value,
    Owl.get("rational").value,
  )

  private val characteristics: Map[Iri, PropertyCharacteristic] =
    PropertyCharacteristic.values.map(c => c.iri -> c).toMap

  private val deprecatedTrue: Annotation =
    Annotation(Owl.deprecated.value, Literal("true", XmlSchema.boolean))

  private def isVocabulary(iri: String): Boolean =
    iri.startsWith(Owl.prefix) || iri.startsWith(Rdf.prefix) || iri.startsWith(Rdfs.prefix)

  /** The entity kind that an `rdf:type` object declares, or `null` if it declares none. */
  private def kindOf(t: Iri): EntityKind = t match {
    case Owl.Class | Rdfs.Class | Owl.DeprecatedClass => EntityKind.Class
    case Rdfs.Datatype => EntityKind.Datatype
    case Owl.ObjectProperty => EntityKind.ObjectProperty
    case Owl.DatatypeProperty => EntityKind.DataProperty
    case Owl.AnnotationProperty => EntityKind.AnnotationProperty
    case Owl.NamedIndividual => EntityKind.NamedIndividual
    case _ => null
  }

  /** The bit of an entity kind in the kinds of an IRI. */
  private def bit(kind: EntityKind): Int = 1 << kind.ordinal

  private val propertyBits: Int = bit(EntityKind.ObjectProperty) | bit(EntityKind.DataProperty)

  private val terminologyBits: Int = ~bit(EntityKind.NamedIndividual)

  private val noIndices: collection.IndexedSeq[Int] = IndexedSeq.empty

  /** Stands for a missing or unreadable cardinality. */
  private val noCount: Int = Int.MinValue

  /** Takes the indices of triples that nobody needs to mark as used. */
  private object Discard extends mutable.Growable[Int] {
    override def addOne(elem: Int): this.type = this
    override def clear(): Unit = ()
  }

  private final class Reader(graph: Graph) {

    private val triples = graph.triples

    /** The kinds each IRI is declared as, one [[bit]] per kind. */
    private val kinds = mutable.HashMap.empty[String, Int]

    /** Annotations on axioms, keyed by the axiom's main triple. */
    private val axiomAnnotations = mutable.HashMap.empty[Triple, Seq[Annotation]]

    /** The annotated axioms whose object is a blank node, by subject, in input order. See
      * [[annotationsOfCopy]].
      */
    private val blankTargets = mutable.HashMap.empty[
      Resource,
      mutable.ArrayBuffer[(predicate: Iri, target: AnyBlankNode, annotations: Seq[Annotation])],
    ]

    private val axioms = Vector.newBuilder[Axiom]

    /** The triples read for the axiom being built. They are marked as used once it is complete. */
    private val touched = mutable.ArrayBuffer.empty[Int]

    def read(): Result = {
      // Read axiom annotations first, because declarations can have them too.
      readAxiomAnnotations()
      val header = readDeclarations()
      val ontology = if (header ne null) readHeader(header) else Ontology()
      graph.subjects.foreach { entry =>
        entry._1 match {
          case subject: Iri => readEntity(subject, entry._2)
          case subject: AnyBlankNode =>
            if (!graph.isObject(subject)) readAnonymous(subject, entry._2)
        }
      }
      Result(ontology.copy(axioms = axioms.result()), graph.unused)
    }

    private def kindsOf(iri: String): Int = kinds.getOrElse(iri, 0)

    private def declare(iri: String, kind: EntityKind): Unit =
      kinds.update(iri, kindsOf(iri) | bit(kind))

    private def isKind(iri: String, kind: EntityKind): Boolean = (kindsOf(iri) & bit(kind)) != 0

    private def isProperty(iri: String): Boolean = (kindsOf(iri) & propertyBits) != 0

    /** Whether the IRI is a class, property or datatype, not an individual. */
    private def isTerminology(iri: String): Boolean = (kindsOf(iri) & terminologyBits) != 0

    private def isDatatype(iri: String): Boolean =
      isKind(iri, EntityKind.Datatype) || isBuiltInDatatype(iri)

    /** Reads `rdf:type` triples of named entities. A typed blank node is read as part of the
      * expression it belongs to.
      *
      * @return
      *   the first subject typed `owl:Ontology`, or `null` if there is none
      */
    private def readDeclarations(): Iri = {
      var header: Iri = null
      val untypedProperties = mutable.LinkedHashSet.empty[Iri]
      val declarations = mutable.ArrayBuffer.empty[(index: Int, iri: String, kind: EntityKind)]
      val deprecated = mutable.ArrayBuffer.empty[String]
      var i = 0
      while (i < triples.length) {
        val t = triples(i)
        if (t.pred == Rdf.`type`) {
          t.subj match {
            case subject: Iri =>
              t.obj match {
                case o: Iri =>
                  val s = subject.value
                  val kind = kindOf(o)
                  if (kind ne null) {
                    declare(s, kind)
                    declarations.addOne((index = i, iri = s, kind = kind))
                  }
                  if (o == Rdf.Property || o == Owl.DeprecatedProperty) {
                    untypedProperties.addOne(subject)
                    graph.use(i)
                  }
                  if (o == Owl.DeprecatedClass || o == Owl.DeprecatedProperty) deprecated.addOne(s)
                  if ((header eq null) && o == Owl.Ontology) header = subject
                case _ =>
              }
            case _ =>
          }
        }
        i += 1
      }
      // An `rdf:Property` is a data property if its range is a datatype, and an object property
      // otherwise. Skip it if it already has an OWL property type, annotation property included.
      untypedProperties.foreach { p =>
        val iri = p.value
        if (!isProperty(iri) && !isKind(iri, EntityKind.AnnotationProperty)) {
          val kind =
            if (hasDatatypeRange(p)) EntityKind.DataProperty else EntityKind.ObjectProperty
          declare(iri, kind)
          axioms.addOne(Declaration(kind, iri))
        }
      }
      declarations.foreach { d =>
        graph.use(d.index)
        axioms.addOne(Declaration(d.kind, d.iri, annotationsFor(triples(d.index))))
      }
      // `owl:DeprecatedClass` and `owl:DeprecatedProperty` also mean `owl:deprecated true`.
      deprecated.foreach(s => axioms.addOne(AnnotationAssertion(s, deprecatedTrue)))
      header
    }

    private def hasDatatypeRange(property: Iri): Boolean = graph.about(property).exists { i =>
      val t = triples(i)
      t.pred == Rdfs.range && (t.obj match {
        case r: Iri => isDatatype(r.value)
        case _ => false
      })
    }

    /** Collects `owl:Axiom` nodes, keyed by the triple they annotate. */
    private def readAxiomAnnotations(): Unit =
      graph.subjects.foreach { entry =>
        entry._1 match {
          case _: AnyBlankNode =>
            val indices = entry._2
            if (indices.exists(isAxiomType)) readAxiomAnnotation(indices)
          case _ =>
        }
      }

    private def isAxiomType(i: Int): Boolean = {
      val t = triples(i)
      t.pred == Rdf.`type` && t.obj == Owl.Axiom
    }

    private def readAxiomAnnotation(indices: collection.IndexedSeq[Int]): Unit = {
      var source: Resource = null
      var property: Iri = null
      var target: Node = null
      val annotations = Seq.newBuilder[Annotation]
      indices.foreach { i =>
        val t = triples(i)
        def annotate(): Unit = t.obj match {
          case o: (Iri | Literal | LanguageLiteral) =>
            annotations.addOne(Annotation(t.pred.value, o))
          case _ =>
        }
        t.pred match {
          case Rdf.`type` => if (t.obj != Owl.Axiom) annotate()
          case Owl.annotatedSource =>
            t.obj match {
              case s: Resource => source = s
              case _ => annotate()
            }
          case Owl.annotatedProperty =>
            t.obj match {
              case p: Iri => property = p
              case _ => annotate()
            }
          case Owl.annotatedTarget => target = t.obj
          case _ => annotate()
        }
      }
      if ((source ne null) && (property ne null) && (target ne null)) {
        indices.foreach(graph.use)
        val result = annotations.result()
        axiomAnnotations.update(Triple(source, property, target), result)
        target match {
          case b: AnyBlankNode =>
            blankTargets.getOrElseUpdate(source, mutable.ArrayBuffer.empty)
              .addOne((predicate = property, target = b, annotations = result))
          case _ =>
        }
      }
    }

    /** Annotations on the axiom whose main triple is `t`. */
    private def annotationsFor(t: Triple): Seq[Annotation] = {
      val annotations = axiomAnnotations.getOrElse(t, null)
      if (annotations ne null) annotations
      else {
        t.obj match {
          case b: AnyBlankNode => annotationsOfCopy(t, b)
          case _ => Nil
        }
      }
    }

    /** The `owl:Axiom` may point at a copy of the expression at `node`, not the same node, as the
      * OWL API writes it. So a copy with the same structure matches too. If several copies match,
      * the first one in input order is taken.
      */
    private def annotationsOfCopy(t: Triple, node: AnyBlankNode): Seq[Annotation] = {
      val copies = blankTargets.getOrElse(t.subj, null)
      if (copies eq null) Nil
      else {
        var expr: Filler = null
        var exprRead = false
        var j = 0
        while (j < copies.length) {
          val copy = copies(j)
          if (copy.predicate == t.pred) {
            if (!exprRead) {
              expr = filler(node, mutable.ArrayBuffer.empty, dataProperty = false)
              exprRead = true
            }
            val read = mutable.ArrayBuffer.empty[Int]
            val parsed = filler(copy.target, read, dataProperty = false)
            if ((parsed ne null) && parsed == expr) {
              read.foreach(graph.use)
              return copy.annotations
            }
          }
          j += 1
        }
        Nil
      }
    }

    private def readHeader(subject: Iri): Ontology = {
      var versionIri: String = null
      val imports = Seq.newBuilder[String]
      val annotations = Seq.newBuilder[Annotation]
      val prefixes = Seq.newBuilder[(String, String)]
      graph.about(subject).foreach { i =>
        val t = triples(i)
        def annotate(): Unit = t.obj match {
          case o: (Iri | Literal | LanguageLiteral) =>
            graph.use(i)
            annotations.addOne(Annotation(t.pred.value, o))
          case _ =>
        }
        t.pred match {
          case Rdf.`type` =>
            if (t.obj == Owl.Ontology) graph.use(i)
            else annotate()
          case Owl.versionIRI =>
            t.obj match {
              case v: Iri =>
                graph.use(i)
                versionIri = v.value
              case _ => annotate()
            }
          case Owl.imports =>
            t.obj match {
              case v: Iri =>
                graph.use(i)
                imports.addOne(v.value)
              case _ => annotate()
            }
          case Shacl.declare =>
            t.obj match {
              case declaration: AnyBlankNode =>
                val prefix = prefixDeclaration(declaration)
                if (prefix ne null) {
                  graph.use(i)
                  prefixes.addOne(prefix)
                }
              case _ => annotate()
            }
          case _ => annotate()
        }
      }
      Ontology(
        iri = Some(subject.value),
        versionIri = if (versionIri ne null) Some(versionIri) else None,
        imports = imports.result(),
        annotations = annotations.result(),
        prefixes = prefixes.result(),
      )
    }

    /** The prefix and namespace of an `sh:declare` node, or `null` if it lacks one of them. */
    private def prefixDeclaration(node: AnyBlankNode): (String, String) = {
      val indices = graph.about(node)
      var prefix: String = null
      var namespace: String = null
      indices.foreach { i =>
        val t = triples(i)
        t.obj match {
          case l: Literal =>
            if ((prefix eq null) && t.pred == shaclPrefix) prefix = l.value
            else if ((namespace eq null) && t.pred == Shacl.namespace) namespace = l.value
          case _ =>
        }
      }
      if ((prefix eq null) || (namespace eq null)) null
      else {
        indices.foreach(graph.use)
        (prefix, namespace)
      }
    }

    private def readEntity(subject: Iri, indices: collection.IndexedSeq[Int]): Unit = {
      val s = subject.value
      val named = ClassExpr.Named(s)
      val isDatatypeSubject = isDatatype(s)
      indices.foreach { i =>
        if (!graph.isUsed(i)) {
          touched.clear()
          val axiom = entityAxiom(subject, named, isDatatypeSubject, triples(i))
          if (axiom ne null) {
            axioms.addOne(axiom)
            graph.use(i)
            touched.foreach(graph.use)
          }
        }
      }
    }

    /** The axiom that `t` states about a named entity, or `null` if it can't be read. Adds the
      * other triples it reads to [[touched]].
      */
    private def entityAxiom(
        subject: Iri,
        named: ClassExpr.Named,
        isDatatypeSubject: Boolean,
        t: Triple,
    ): Axiom = {
      val s = subject.value
      val o = t.obj
      t.pred match {
        case Rdf.`type` =>
          o match {
            case c: Iri =>
              val characteristic = characteristics.getOrElse(c, null)
              if (characteristic ne null) Characteristic(s, characteristic, annotationsFor(t))
              else if (c == Owl.Thing || !isVocabulary(c.value)) {
                ClassAssertion(ClassExpr.Named(c.value), s, annotationsFor(t))
              } else valueAxiom(s, t)
            case b: AnyBlankNode =>
              val e = classExpr(b, touched)
              if (e ne null) ClassAssertion(e, s, annotationsFor(t)) else null
            case _ => valueAxiom(s, t)
          }
        case Rdfs.subClassOf =>
          val e = classExpr(o, touched)
          if (e ne null) SubClassOf(named, e, annotationsFor(t)) else null
        case Owl.equivalentClass =>
          if (isDatatypeSubject || isDataRange(o)) {
            val r = dataRange(o, touched)
            if (r ne null) DatatypeDefinition(s, r, annotationsFor(t)) else null
          } else {
            val e = classExpr(o, touched)
            if (e ne null) EquivalentClasses(Seq(named, e), annotationsFor(t)) else null
          }
        case Owl.disjointWith =>
          val e = classExpr(o, touched)
          if (e ne null) DisjointClasses(Seq(named, e), annotationsFor(t)) else null
        case Owl.disjointUnionOf =>
          val es = all(listOf(o, touched))(classExpr(_, touched))
          if (es ne null) DisjointUnion(s, es, annotationsFor(t)) else null
        case Owl.onDatatype =>
          // OWL 1 style: the restriction is directly on the named datatype.
          if (isDatatypeSubject) {
            val r = datatypeRestriction(subject, touched)
            if (r ne null) DatatypeDefinition(s, r, annotationsFor(t)) else null
          } else valueAxiom(s, t)
        case Owl.unionOf | Owl.intersectionOf | Owl.oneOf | Owl.complementOf =>
          // OWL 1 style: the expression is directly on the named class or datatype.
          if (isDatatypeSubject) {
            val r = booleanDataRange(subject, touched)
            if (r ne null) DatatypeDefinition(s, r, annotationsFor(t)) else null
          } else {
            val e = booleanClassExpr(subject, touched)
            if (e ne null) EquivalentClasses(Seq(named, e), annotationsFor(t)) else null
          }
        case Owl.hasKey =>
          val keys = all(listOf(o, touched))(iriOf)
          if (keys ne null) HasKey(named, keys, annotationsFor(t)) else null
        case Rdfs.subPropertyOf =>
          o match {
            case p: Iri => SubPropertyOf(s, p.value, annotationsFor(t))
            case _ => valueAxiom(s, t)
          }
        case Owl.equivalentProperty =>
          o match {
            case p: Iri => EquivalentProperties(Seq(s, p.value), annotationsFor(t))
            case _ => valueAxiom(s, t)
          }
        case Owl.propertyDisjointWith =>
          o match {
            case p: Iri => DisjointProperties(Seq(s, p.value), annotationsFor(t))
            case _ => valueAxiom(s, t)
          }
        case Owl.inverseOf =>
          o match {
            case p: Iri => InverseProperties(s, p.value, annotationsFor(t))
            case _ => valueAxiom(s, t)
          }
        case Owl.propertyChainAxiom =>
          val chain = all(listOf(o, touched))(propertyRef(_, touched))
          if (chain ne null) PropertyChain(s, chain, annotationsFor(t)) else null
        case Rdfs.domain =>
          val e = classExpr(o, touched)
          if (e ne null) Domain(s, e, annotationsFor(t)) else null
        case Rdfs.range =>
          val f = filler(o, touched, isKind(s, EntityKind.DataProperty))
          if (f ne null) Range(s, f, annotationsFor(t)) else null
        case Owl.sameAs =>
          o match {
            case i: Iri => SameIndividual(Seq(s, i.value), annotationsFor(t))
            case _ => valueAxiom(s, t)
          }
        case Owl.differentFrom =>
          o match {
            case i: Iri => DifferentIndividuals(Seq(s, i.value), annotationsFor(t))
            case _ => valueAxiom(s, t)
          }
        case _ => valueAxiom(s, t)
      }
    }

    /** A property assertion, or an annotation if the predicate is not a declared property or the
      * subject is not an individual. `null` if the object is a blank node.
      */
    private def valueAxiom(s: String, t: Triple): Axiom = t.obj match {
      case o: (Iri | Literal | LanguageLiteral) =>
        val p = t.pred.value
        if (isProperty(p) && !isTerminology(s)) PropertyAssertion(p, s, o, annotationsFor(t))
        else AnnotationAssertion(s, Annotation(p, o), annotationsFor(t))
      case _ => null
    }

    private def readAnonymous(node: AnyBlankNode, indices: collection.IndexedSeq[Int]): Unit =
      if (hasType(indices, Owl.AllDisjointClasses)) {
        touched.clear()
        val es = all(members(indices, Owl.members, null))(classExpr(_, touched))
        if (es ne null) commit(indices, DisjointClasses(es))
      } else if (hasType(indices, Owl.AllDisjointProperties)) {
        touched.clear()
        val ps = all(members(indices, Owl.members, null))(iriOf)
        if (ps ne null) commit(indices, DisjointProperties(ps))
      } else if (hasType(indices, Owl.AllDifferent)) {
        touched.clear()
        val is = all(members(indices, Owl.distinctMembers, Owl.members))(iriOf)
        if (is ne null) commit(indices, DifferentIndividuals(is))
      } else {
        // General class inclusion or equivalence, where the subject is an expression.
        indices.foreach { i =>
          val t = triples(i)
          if (t.pred == Rdfs.subClassOf || t.pred == Owl.equivalentClass) {
            touched.clear()
            val sub = classExpr(node, touched, skip = i)
            if (sub ne null) {
              val sup = classExpr(t.obj, touched)
              if (sup ne null) {
                axioms.addOne(
                  if (t.pred == Rdfs.subClassOf) SubClassOf(sub, sup, annotationsFor(t))
                  else EquivalentClasses(Seq(sub, sup), annotationsFor(t)),
                )
                graph.use(i)
                touched.foreach(graph.use)
              }
            }
          }
        }
      }

    /** Adds the axiom of an anonymous node and marks its triples as used. */
    private def commit(indices: collection.IndexedSeq[Int], axiom: Axiom): Unit = {
      axioms.addOne(axiom)
      indices.foreach(graph.use)
      touched.foreach(graph.use)
    }

    private def hasType(indices: collection.IndexedSeq[Int], kind: Iri): Boolean =
      indices.exists { i =>
        val t = triples(i)
        t.pred == Rdf.`type` && t.obj == kind
      }

    /** The members of the list that the first triple with one of the predicates points at, or
      * `null` if there is no such triple or the list is malformed.
      */
    private def members(
        indices: collection.IndexedSeq[Int],
        predicate: Iri,
        alternative: Iri,
    ): Seq[Node] = {
      var j = 0
      while (j < indices.length) {
        val t = triples(indices(j))
        if (t.pred == predicate || t.pred == alternative) return listOf(t.obj, touched)
        j += 1
      }
      null
    }

    // Each expression parser below adds the triples it reads to `touched`, and returns `null` if it
    // can't read the expression. The caller marks the triples as used only after the whole axiom
    // has been read. A parser that fails removes what it added.

    /** The members of the RDF list at `node`, or `null` if there is no node or the list is
      * malformed.
      */
    private def listOf(node: Node, touched: mutable.Growable[Int]): Seq[Node] =
      if (node eq null) null
      else graph.list(node, touched).orNullFast

    private def isLiteral(node: Node): Boolean = node match {
      case _: (Literal | LanguageLiteral) => true
      case _ => false
    }

    private def literalOf(node: Node): Literal | LanguageLiteral = node match {
      case l: (Literal | LanguageLiteral) => l
      case _ => null
    }

    private def iriOf(node: Node): String = node match {
      case i: Iri => i.value
      case _ => null
    }

    /** The results of `f` for all of `xs`, or `null` if `xs` is `null` or `f` returns `null` for
      * one of them.
      */
    private def all[A, B <: AnyRef](xs: Seq[A])(f: A => B): Seq[B] =
      if (xs eq null) null
      else {
        val out = Seq.newBuilder[B]
        val it = xs.iterator
        var complete = true
        while (complete && it.hasNext) {
          val b = f(it.next())
          if (b eq null) complete = false
          else out.addOne(b)
        }
        if (complete) out.result() else null
      }

    /** Triples about a node. IRIs are included for OWL 1 style expressions on named classes. */
    private def indicesOf(node: Node): collection.IndexedSeq[Int] = node match {
      case r: Resource => graph.about(r)
      case _ => noIndices
    }

    /** The object of the first triple about `node` with the predicate, or `null` if there is none.
      */
    private def objectOf(node: Node, predicate: Iri, touched: mutable.ArrayBuffer[Int]): Node = {
      val indices = indicesOf(node)
      var j = 0
      while (j < indices.length) {
        val i = indices(j)
        val t = triples(i)
        if (t.pred == predicate) {
          touched.addOne(i)
          return t.obj
        }
        j += 1
      }
      null
    }

    private def has(node: Node, predicate: Iri): Boolean =
      indicesOf(node).exists(i => triples(i).pred == predicate)

    /** Removes what a failed parser added to `touched` after `mark`. */
    private def undo(touched: mutable.ArrayBuffer[Int], mark: Int): Unit =
      touched.dropRightInPlace(touched.length - mark)

    private def propertyRef(node: Node, touched: mutable.ArrayBuffer[Int]): PropertyRef =
      node match {
        case i: Iri => PropertyRef(i.value)
        case b: AnyBlankNode =>
          objectOf(b, Owl.inverseOf, touched) match {
            case p: Iri => PropertyRef(p.value, inverse = true)
            case _ => null
          }
        case _ => null
      }

    /** Reads a restriction filler or a range. For a data property it is always a data range, even
      * if the datatype isn't declared.
      */
    private def filler(
        node: Node,
        touched: mutable.ArrayBuffer[Int],
        dataProperty: Boolean,
    ): Filler =
      if (dataProperty || isDataRange(node)) dataRange(node, touched)
      else classExpr(node, touched)

    private def isDataRange(node: Node): Boolean = node match {
      case i: Iri => isDatatype(i.value)
      case b: AnyBlankNode => graph.about(b).exists(i => describesDataRange(triples(i)))
      case _ => false
    }

    /** Whether a triple about a blank node shows that the node is a data range. */
    private def describesDataRange(t: Triple): Boolean = t.pred match {
      case Rdf.`type` => t.obj == Rdfs.Datatype
      case Owl.onDatatype | Owl.datatypeComplementOf => true
      case Owl.oneOf =>
        val members = listOf(t.obj, Discard)
        (members ne null) && members.exists(isLiteral)
      case Owl.unionOf | Owl.intersectionOf =>
        val members = listOf(t.obj, Discard)
        (members ne null) && members.nonEmpty && isDataRange(members.head)
      case _ => false
    }

    private def classExpr(
        node: Node,
        touched: mutable.ArrayBuffer[Int],
        skip: Int = -1,
    ): ClassExpr = node match {
      case i: Iri => ClassExpr.Named(i.value)
      case b: AnyBlankNode =>
        val mark = touched.length
        val indices = graph.about(b)
        var isRestriction = false
        var j = 0
        while (j < indices.length) {
          val i = indices(j)
          if (i != skip) {
            val t = triples(i)
            // `rdf:type owl:Class` and `rdf:type owl:Restriction` add nothing, so just mark them.
            if (t.pred == Rdf.`type`) {
              if (t.obj == Owl.Class || t.obj == Owl.Restriction) touched.addOne(i)
            } else if (t.pred == Owl.onProperty) isRestriction = true
          }
          j += 1
        }
        val result = if (isRestriction) restriction(b, touched) else booleanClassExpr(b, touched)
        if (result eq null) undo(touched, mark)
        result
      case _ => null
    }

    private def booleanClassExpr(node: Node, touched: mutable.ArrayBuffer[Int]): ClassExpr =
      if (has(node, Owl.intersectionOf)) {
        val es = all(listOf(objectOf(node, Owl.intersectionOf, touched), touched))(
          classExpr(_, touched),
        )
        if (es ne null) ClassExpr.IntersectionOf(es) else null
      } else if (has(node, Owl.unionOf)) {
        val es = all(listOf(objectOf(node, Owl.unionOf, touched), touched))(classExpr(_, touched))
        if (es ne null) ClassExpr.UnionOf(es) else null
      } else if (has(node, Owl.complementOf)) {
        val e = classExpr(objectOf(node, Owl.complementOf, touched), touched)
        if (e ne null) ClassExpr.ComplementOf(e) else null
      } else if (has(node, Owl.oneOf)) {
        val is = all(listOf(objectOf(node, Owl.oneOf, touched), touched))(iriOf)
        if (is ne null) ClassExpr.OneOf(is) else null
      } else null

    private def restriction(node: AnyBlankNode, touched: mutable.ArrayBuffer[Int]): ClassExpr = {
      val property = propertyRef(objectOf(node, Owl.onProperty, touched), touched)
      if (property eq null) null
      else {
        import ClassExpr.Bound.*
        val data = !property.inverse && isKind(property.iri, EntityKind.DataProperty)
        if (has(node, Owl.someValuesFrom)) {
          val f = filler(objectOf(node, Owl.someValuesFrom, touched), touched, data)
          if (f ne null) ClassExpr.SomeValuesFrom(property, f) else null
        } else if (has(node, Owl.allValuesFrom)) {
          val f = filler(objectOf(node, Owl.allValuesFrom, touched), touched, data)
          if (f ne null) ClassExpr.AllValuesFrom(property, f) else null
        } else if (has(node, Owl.hasValue)) {
          objectOf(node, Owl.hasValue, touched) match {
            case v: (Iri | Literal | LanguageLiteral) => ClassExpr.HasValue(property, v)
            case _ => null
          }
        } else if (has(node, Owl.hasSelf)) {
          objectOf(node, Owl.hasSelf, touched)
          ClassExpr.HasSelf(property)
        } else if (has(node, Owl.minCardinality) || has(node, Owl.minQualifiedCardinality)) {
          cardinality(node, property, Min, Owl.minCardinality, Owl.minQualifiedCardinality, touched)
        } else if (has(node, Owl.maxCardinality) || has(node, Owl.maxQualifiedCardinality)) {
          cardinality(node, property, Max, Owl.maxCardinality, Owl.maxQualifiedCardinality, touched)
        } else if (has(node, Owl.cardinality) || has(node, Owl.qualifiedCardinality)) {
          cardinality(node, property, Exact, Owl.cardinality, Owl.qualifiedCardinality, touched)
        } else null
      }
    }

    /** A cardinality restriction: the plain form if `node` has the `plain` predicate, otherwise the
      * qualified form, with an optional `owl:onClass` or `owl:onDataRange`.
      */
    private def cardinality(
        node: AnyBlankNode,
        property: PropertyRef,
        bound: ClassExpr.Bound,
        plain: Iri,
        qualified: Iri,
        touched: mutable.ArrayBuffer[Int],
    ): ClassExpr =
      if (has(node, plain)) {
        val n = count(node, plain, touched)
        if (n != noCount) ClassExpr.Cardinality(bound, n, property) else null
      } else {
        val n = count(node, qualified, touched)
        if (n == noCount) null
        else if (has(node, Owl.onClass)) {
          val e = classExpr(objectOf(node, Owl.onClass, touched), touched)
          if (e ne null) ClassExpr.Cardinality(bound, n, property, Some(e)) else null
        } else if (has(node, Owl.onDataRange)) {
          val r = dataRange(objectOf(node, Owl.onDataRange, touched), touched)
          if (r ne null) ClassExpr.Cardinality(bound, n, property, Some(r)) else null
        } else ClassExpr.Cardinality(bound, n, property, None)
      }

    /** The integer object of the triple with the predicate, or [[noCount]] if there is none. */
    private def count(node: Node, predicate: Iri, touched: mutable.ArrayBuffer[Int]): Int =
      objectOf(node, predicate, touched) match {
        case l: Literal => l.value.trim.toIntOption.getOrElseFast(noCount)
        case _ => noCount
      }

    private def dataRange(node: Node, touched: mutable.ArrayBuffer[Int]): DataRange =
      node match {
        case i: Iri => DataRange.Datatype(i.value)
        case b: AnyBlankNode =>
          val mark = touched.length
          graph.about(b).foreach { i =>
            val t = triples(i)
            if (t.pred == Rdf.`type` && t.obj == Rdfs.Datatype) touched.addOne(i)
          }
          val result =
            if (has(b, Owl.onDatatype)) datatypeRestriction(b, touched)
            else if (has(b, Owl.datatypeComplementOf)) {
              val r = dataRange(objectOf(b, Owl.datatypeComplementOf, touched), touched)
              if (r ne null) DataRange.ComplementOf(r) else null
            } else if (has(b, Owl.equivalentClass)) {
              dataRange(objectOf(b, Owl.equivalentClass, touched), touched)
            } else booleanDataRange(b, touched)
          if (result eq null) undo(touched, mark)
          result
        case _ => null
      }

    /** `owl:onDatatype` with its `owl:withRestrictions`. */
    private def datatypeRestriction(node: Node, touched: mutable.ArrayBuffer[Int]): DataRange =
      objectOf(node, Owl.onDatatype, touched) match {
        case base: Iri =>
          val facets =
            if (has(node, Owl.withRestrictions)) {
              listOf(objectOf(node, Owl.withRestrictions, touched), touched)
            } else Nil
          val parsed = all(facets)(facet(_, touched))
          if (parsed ne null) DataRange.Restriction(base.value, parsed) else null
        case _ => null
      }

    private def booleanDataRange(node: Node, touched: mutable.ArrayBuffer[Int]): DataRange =
      if (has(node, Owl.intersectionOf)) {
        val rs = all(listOf(objectOf(node, Owl.intersectionOf, touched), touched))(
          dataRange(_, touched),
        )
        if (rs ne null) DataRange.IntersectionOf(rs) else null
      } else if (has(node, Owl.unionOf)) {
        val rs = all(listOf(objectOf(node, Owl.unionOf, touched), touched))(dataRange(_, touched))
        if (rs ne null) DataRange.UnionOf(rs) else null
      } else if (has(node, Owl.oneOf)) {
        val ls = all(listOf(objectOf(node, Owl.oneOf, touched), touched))(literalOf)
        if (ls ne null) DataRange.OneOf(ls) else null
      } else null

    /** The first facet with a literal value, or `null` if there is none. */
    private def facet(node: Node, touched: mutable.ArrayBuffer[Int]): (String, Literal) = {
      val indices = indicesOf(node)
      var j = 0
      while (j < indices.length) {
        val i = indices(j)
        val t = triples(i)
        t.obj match {
          case l: Literal =>
            touched.addOne(i)
            return (t.pred.value, l)
          case _ =>
        }
        j += 1
      }
      null
    }
  }
}
