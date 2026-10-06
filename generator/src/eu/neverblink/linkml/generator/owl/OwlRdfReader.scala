package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.rdf.*

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

  private val classTypes: Set[Iri] = Set(Owl.Class, Rdfs.Class, Owl.DeprecatedClass)

  private val characteristics: Map[Iri, PropertyCharacteristic] =
    PropertyCharacteristic.values.map(c => c.iri -> c).toMap

  private def isVocabulary(iri: String): Boolean =
    iri.startsWith(Owl.prefix) || iri.startsWith(Rdf.prefix) || iri.startsWith(Rdfs.prefix)

  private final class Reader(graph: Graph) {

    private val triples = graph.triples

    private val kinds = mutable.HashMap.empty[String, mutable.Set[EntityKind]]

    /** Annotations on axioms, keyed by the axiom's main triple. */
    private val axiomAnnotations = mutable.HashMap.empty[(Resource, Iri, Node), Seq[Annotation]]

    private val axioms = Vector.newBuilder[Axiom]

    def read(): Result = {
      // Read axiom annotations first, because declarations can have them too.
      readAxiomAnnotations()
      readDeclarations()
      val header = readHeader()
      graph.subjects.foreach {
        case (subject: Iri, indices) if !header.contains(subject) => readEntity(subject, indices)
        case (subject: AnyBlankNode, indices) if !graph.isObject(subject) =>
          readAnonymous(subject, indices)
        case _ =>
      }
      val ontology = header.fold(Ontology())(_._2).copy(axioms = axioms.result())
      Result(ontology, graph.unused)
    }

    private def declare(iri: String, kind: EntityKind): Unit =
      kinds.getOrElseUpdate(iri, mutable.LinkedHashSet.empty[EntityKind]) += kind

    private def isKind(iri: String, kind: EntityKind): Boolean =
      kinds.get(iri).exists(_.contains(kind))

    private def isProperty(iri: String): Boolean =
      isKind(iri, EntityKind.ObjectProperty) || isKind(iri, EntityKind.DataProperty)

    private def isDatatype(iri: String): Boolean =
      isKind(iri, EntityKind.Datatype) || isBuiltInDatatype(iri)

    /** Reads `rdf:type` triples of named entities. A typed blank node is read as part of the
      * expression it belongs to.
      */
    private def readDeclarations(): Unit = {
      val untypedProperties = mutable.LinkedHashSet.empty[String]
      val declarations = Vector.newBuilder[(Int, String, EntityKind)]
      triples.indices.foreach { i =>
        triples(i) match {
          case Triple(Iri(s), Rdf.`type`, t: Iri) =>
            val kind: Option[EntityKind] = t match {
              case _ if classTypes.contains(t) => Some(EntityKind.Class)
              case Rdfs.Datatype => Some(EntityKind.Datatype)
              case Owl.ObjectProperty => Some(EntityKind.ObjectProperty)
              case Owl.DatatypeProperty => Some(EntityKind.DataProperty)
              case Owl.AnnotationProperty => Some(EntityKind.AnnotationProperty)
              case Owl.NamedIndividual => Some(EntityKind.NamedIndividual)
              case _ => None
            }
            kind.foreach { k =>
              declare(s, k)
              declarations += ((i, s, k))
            }
            if t == Rdf.Property || t == Owl.DeprecatedProperty then {
              untypedProperties += s
              graph.use(i)
            }
          case _ =>
        }
      }
      // An `rdf:Property` is a data property if its range is a datatype, and an object property
      // otherwise. Skip it if it already has an OWL property type, annotation property included.
      untypedProperties.filterNot(p => isProperty(p) || isKind(p, EntityKind.AnnotationProperty))
        .foreach { p =>
          val dataRange = graph.about(Iri(p)).exists { i =>
            triples(i) match {
              case Triple(_, Rdfs.range, Iri(r)) => isDatatype(r)
              case _ => false
            }
          }
          val kind = if dataRange then EntityKind.DataProperty else EntityKind.ObjectProperty
          declare(p, kind)
          axioms += Declaration(kind, p)
        }
      declarations.result().foreach { (i, s, k) =>
        graph.use(i)
        axioms += Declaration(k, s, annotationsFor(triples(i)))
      }
      // `owl:DeprecatedClass` and `owl:DeprecatedProperty` also mean `owl:deprecated true`.
      triples.indices.foreach { i =>
        triples(i) match {
          case Triple(Iri(s), Rdf.`type`, Owl.DeprecatedClass | Owl.DeprecatedProperty) =>
            axioms += AnnotationAssertion(
              s,
              Annotation(Owl.deprecated.value, Literal("true", XmlSchema.boolean)),
            )
          case _ =>
        }
      }
    }

    /** Collects `owl:Axiom` nodes, keyed by the triple they annotate. */
    private def readAxiomAnnotations(): Unit =
      graph.subjects.foreach {
        case (_: AnyBlankNode, indices)
            if indices.exists(i => triples(i).pred == Rdf.`type` && triples(i).obj == Owl.Axiom) =>
          var source: Option[Resource] = None
          var property: Option[Iri] = None
          var target: Option[Node] = None
          val annotations = Seq.newBuilder[Annotation]
          indices.foreach { i =>
            triples(i) match {
              case Triple(_, Rdf.`type`, Owl.Axiom) =>
              case Triple(_, Owl.annotatedSource, s: Resource) => source = Some(s)
              case Triple(_, Owl.annotatedProperty, p: Iri) => property = Some(p)
              case Triple(_, Owl.annotatedTarget, t) => target = Some(t)
              case Triple(_, p, o: (Iri | Literal | LanguageLiteral)) =>
                annotations += Annotation(p.value, o)
              case _ =>
            }
          }
          (source, property, target) match {
            case (Some(s), Some(p), Some(t)) =>
              indices.foreach(graph.use)
              axiomAnnotations((s, p, t)) = annotations.result()
            case _ =>
          }
        case _ =>
      }

    /** Annotations on the axiom whose main triple is `t`.
      *
      * If the object is a blank node, the `owl:Axiom` may point at a copy of the expression, not
      * the same node, as the OWL API writes it. So a copy with the same structure matches too.
      */
    private def annotationsFor(t: Triple): Seq[Annotation] =
      axiomAnnotations.get((t.subj, t.pred, t.obj)).orElse {
        t.obj match {
          case b: AnyBlankNode =>
            val copies = axiomAnnotations.toSeq.collect {
              case ((s, p, target: AnyBlankNode), anns) if s == t.subj && p == t.pred =>
                target -> anns
            }
            lazy val expr = filler(b, mutable.ArrayBuffer.empty, dataProperty = false)
            copies.collectFirst(Function.unlift { (target, anns) =>
              val touched = mutable.ArrayBuffer.empty[Int]
              val copy = filler(target, touched, dataProperty = false)
              Option.when(copy.isDefined && copy == expr) {
                touched.foreach(graph.use)
                anns
              }
            })
          case _ => None
        }
      }.getOrElse(Nil)

    private def readHeader(): Option[(Iri, Ontology)] =
      triples.collectFirst { case Triple(s: Iri, Rdf.`type`, Owl.Ontology) => s }.map { subject =>
        var ontology = Ontology(iri = Some(subject.value))
        graph.about(subject).foreach { i =>
          triples(i) match {
            case Triple(_, Rdf.`type`, Owl.Ontology) => graph.use(i)
            case Triple(_, Owl.versionIRI, Iri(v)) =>
              graph.use(i)
              ontology = ontology.copy(versionIri = Some(v))
            case Triple(_, Owl.imports, Iri(v)) =>
              graph.use(i)
              ontology = ontology.copy(imports = ontology.imports :+ v)
            case Triple(_, Shacl.declare, declaration: AnyBlankNode) =>
              prefixDeclaration(declaration).foreach { prefix =>
                graph.use(i)
                ontology = ontology.copy(prefixes = ontology.prefixes :+ prefix)
              }
            case Triple(_, p, o: (Iri | Literal | LanguageLiteral)) =>
              graph.use(i)
              ontology = ontology.copy(annotations = ontology.annotations :+ Annotation(p.value, o))
            case _ =>
          }
        }
        subject -> ontology
      }

    private def prefixDeclaration(node: AnyBlankNode): Option[(String, String)] = {
      val indices = graph.about(node)
      val prefix = indices.collectFirst(i =>
        triples(i) match { case Triple(_, `shaclPrefix`, Literal(p, _)) => p },
      )
      val namespace = indices.collectFirst(i =>
        triples(i) match { case Triple(_, Shacl.namespace, Literal(ns, _)) => ns },
      )
      for p <- prefix; ns <- namespace yield {
        indices.foreach(graph.use)
        p -> ns
      }
    }

    private def readEntity(subject: Iri, indices: Iterable[Int]): Unit = {
      val s = subject.value
      val named = ClassExpr.Named(s)
      val isDatatypeSubject = isDatatype(s)
      indices.foreach { i =>
        val t = triples(i)
        if !graph.isUsed(i) then
          def add(axiom: Seq[Annotation] => Option[Axiom], touched: Iterable[Int] = Nil): Unit =
            axiom(annotationsFor(t)).foreach { a =>
              axioms += a
              graph.use(i)
              touched.foreach(graph.use)
            }
          val touched = mutable.ArrayBuffer.empty[Int]
          t match {
            case Triple(_, Rdf.`type`, c: Iri) if characteristics.contains(c) =>
              add(anns => Some(Characteristic(s, characteristics(c), anns)))
            case Triple(_, Rdf.`type`, Iri(c)) if c == Owl.Thing.value || !isVocabulary(c) =>
              add(anns => Some(ClassAssertion(ClassExpr.Named(c), s, anns)))
            case Triple(_, Rdf.`type`, b: AnyBlankNode) =>
              add(anns => classExpr(b, touched).map(ClassAssertion(_, s, anns)), touched)
            case Triple(_, Rdfs.subClassOf, o) =>
              add(anns => classExpr(o, touched).map(SubClassOf(named, _, anns)), touched)
            case Triple(_, Owl.equivalentClass, o) if isDatatypeSubject || isDataRange(o) =>
              add(anns => dataRange(o, touched).map(DatatypeDefinition(s, _, anns)), touched)
            case Triple(_, Owl.equivalentClass, o) =>
              add(
                anns => classExpr(o, touched).map(e => EquivalentClasses(Seq(named, e), anns)),
                touched,
              )
            case Triple(_, Owl.disjointWith, o) =>
              add(
                anns => classExpr(o, touched).map(e => DisjointClasses(Seq(named, e), anns)),
                touched,
              )
            case Triple(_, Owl.disjointUnionOf, o) =>
              add(
                anns =>
                  graph.list(o, touched).flatMap(all(_)(classExpr(_, touched)))
                    .map(DisjointUnion(s, _, anns)),
                touched,
              )
            case Triple(_, Owl.onDatatype, _) if isDatatypeSubject =>
              // OWL 1 style: the restriction is directly on the named datatype.
              add(
                anns => datatypeRestriction(subject, touched).map(DatatypeDefinition(s, _, anns)),
                touched,
              )
            case Triple(_, Owl.unionOf | Owl.intersectionOf | Owl.oneOf | Owl.complementOf, _) =>
              // OWL 1 style: the expression is directly on the named class or datatype.
              if isDatatypeSubject then
                add(
                  anns => booleanDataRange(subject, touched).map(DatatypeDefinition(s, _, anns)),
                  touched,
                )
              else
                add(
                  anns =>
                    booleanClassExpr(subject, touched).map(e =>
                      EquivalentClasses(Seq(named, e), anns),
                    ),
                  touched,
                )
            case Triple(_, Owl.hasKey, o) =>
              add(
                anns => graph.list(o, touched).flatMap(all(_)(iriOf)).map(HasKey(named, _, anns)),
                touched,
              )
            case Triple(_, Rdfs.subPropertyOf, Iri(o)) =>
              add(anns => Some(SubPropertyOf(s, o, anns)))
            case Triple(_, Owl.equivalentProperty, Iri(o)) =>
              add(anns => Some(EquivalentProperties(Seq(s, o), anns)))
            case Triple(_, Owl.propertyDisjointWith, Iri(o)) =>
              add(anns => Some(DisjointProperties(Seq(s, o), anns)))
            case Triple(_, Owl.inverseOf, Iri(o)) =>
              add(anns => Some(InverseProperties(s, o, anns)))
            case Triple(_, Owl.propertyChainAxiom, o) =>
              add(
                anns =>
                  graph.list(o, touched).flatMap(all(_)(propertyRef(_, touched)))
                    .map(PropertyChain(s, _, anns)),
                touched,
              )
            case Triple(_, Rdfs.domain, o) =>
              add(anns => classExpr(o, touched).map(Domain(s, _, anns)), touched)
            case Triple(_, Rdfs.range, o) =>
              add(
                anns =>
                  filler(o, touched, isKind(s, EntityKind.DataProperty)).map(Range(s, _, anns)),
                touched,
              )
            case Triple(_, Owl.sameAs, Iri(o)) =>
              add(anns => Some(SameIndividual(Seq(s, o), anns)))
            case Triple(_, Owl.differentFrom, Iri(o)) =>
              add(anns => Some(DifferentIndividuals(Seq(s, o), anns)))
            case Triple(_, p, o: (Iri | Literal | LanguageLiteral))
                if isProperty(p.value) && !isTerminology(s) =>
              add(anns => Some(PropertyAssertion(p.value, s, o, anns)))
            case Triple(_, p, o: (Iri | Literal | LanguageLiteral)) =>
              add(anns => Some(AnnotationAssertion(s, Annotation(p.value, o), anns)))
            case _ =>
          }
      }
    }

    /** Whether the IRI is a class, property or datatype, not an individual. */
    private def isTerminology(iri: String): Boolean =
      kinds.get(iri).exists(_.exists(_ != EntityKind.NamedIndividual))

    private def readAnonymous(node: AnyBlankNode, indices: Iterable[Int]): Unit = {
      val types =
        indices.collect(i => triples(i) match { case Triple(_, Rdf.`type`, t: Iri) => t }).toSet
      val touched = mutable.ArrayBuffer.empty[Int]
      def commit(axiom: Option[Axiom]): Unit = axiom.foreach { a =>
        axioms += a
        indices.foreach(graph.use)
        touched.foreach(graph.use)
      }
      def members(predicates: Iri*): Option[Seq[Node]] =
        indices.iterator.map(triples).collectFirst {
          case Triple(_, p, o) if predicates.contains(p) => o
        }.flatMap(graph.list(_, touched))

      if types.contains(Owl.AllDisjointClasses) then
        commit(members(Owl.members).flatMap(all(_)(classExpr(_, touched))).map(DisjointClasses(_)))
      else if types.contains(Owl.AllDisjointProperties) then
        commit(members(Owl.members).flatMap(all(_)(iriOf)).map(DisjointProperties(_)))
      else if types.contains(Owl.AllDifferent) then
        commit(
          members(Owl.distinctMembers, Owl.members).flatMap(all(_)(iriOf))
            .map(DifferentIndividuals(_)),
        )
      else {
        // General class inclusion or equivalence, where the subject is an expression.
        indices.foreach { i =>
          triples(i) match {
            case t @ Triple(_, Rdfs.subClassOf | Owl.equivalentClass, o) =>
              val exprTouched = mutable.ArrayBuffer.empty[Int]
              val subject = classExpr(node, exprTouched, skip = Set(i))
              val sup = classExpr(o, exprTouched)
              for sub <- subject; s <- sup do {
                axioms += (
                  if t.pred == Rdfs.subClassOf then SubClassOf(sub, s, annotationsFor(t))
                  else EquivalentClasses(Seq(sub, s), annotationsFor(t))
                )
                graph.use(i)
                exprTouched.foreach(graph.use)
              }
            case _ =>
          }
        }
      }
    }

    // Each expression parser below adds the triples it reads to `touched`. The caller marks them
    // as used only after the whole axiom has been read.

    private def literalOf(node: Node): Option[Literal | LanguageLiteral] = node match {
      case l: Literal => Some(l)
      case l: LanguageLiteral => Some(l)
      case _ => None
    }

    private def iriOf(node: Node): Option[String] = node match {
      case Iri(v) => Some(v)
      case _ => None
    }

    private def all[A, B](xs: Seq[A])(f: A => Option[B]): Option[Seq[B]] = {
      val out = Seq.newBuilder[B]
      val it = xs.iterator
      while it.hasNext do
        f(it.next()) match {
          case Some(b) => out += b
          case None => return None
        }
      Some(out.result())
    }

    /** Triples about a node. IRIs are included for OWL 1 style expressions on named classes. */
    private def triplesOf(node: Node, skip: Set[Int] = Set.empty): Seq[(Int, Triple)] =
      node match {
        case r: Resource =>
          graph.about(r).filterNot(skip.contains).map(i => i -> triples(i)).toSeq
        case _ => Nil
      }

    private def objectOf(node: Node, predicate: Iri, touched: mutable.Buffer[Int]): Option[Node] =
      triplesOf(node).collectFirst {
        case (i, Triple(_, p, o)) if p == predicate =>
          touched += i
          o
      }

    private def has(node: Node, predicate: Iri): Boolean =
      triplesOf(node).exists(_._2.pred == predicate)

    private def propertyRef(node: Node, touched: mutable.Buffer[Int]): Option[PropertyRef] =
      node match {
        case Iri(p) => Some(PropertyRef(p))
        case b: AnyBlankNode =>
          objectOf(b, Owl.inverseOf, touched).collect { case Iri(p) =>
            PropertyRef(p, inverse = true)
          }
        case _ => None
      }

    /** Reads a restriction filler or a range. For a data property it is always a data range, even
      * if the datatype isn't declared.
      */
    private def filler(
        node: Node,
        touched: mutable.Buffer[Int],
        dataProperty: Boolean,
    ): Option[Filler] =
      if dataProperty || isDataRange(node) then dataRange(node, touched)
      else classExpr(node, touched)

    private def isDataRange(node: Node): Boolean = node match {
      case Iri(iri) => isDatatype(iri)
      case b: AnyBlankNode =>
        val ts = triplesOf(b).map(_._2)
        ts.exists(t => t.pred == Rdf.`type` && t.obj == Rdfs.Datatype) ||
        ts.exists(t => t.pred == Owl.onDatatype || t.pred == Owl.datatypeComplementOf) ||
        ts.exists {
          case Triple(_, Owl.oneOf, list) =>
            graph.list(list, mutable.ArrayBuffer.empty).exists(_.exists {
              case _: Literal | _: LanguageLiteral => true
              case _ => false
            })
          case Triple(_, Owl.unionOf | Owl.intersectionOf, list) =>
            graph.list(list, mutable.ArrayBuffer.empty).exists(_.headOption.exists(isDataRange))
          case _ => false
        }
      case _ => false
    }

    private def classExpr(
        node: Node,
        touched: mutable.Buffer[Int],
        skip: Set[Int] = Set.empty,
    ): Option[ClassExpr] = node match {
      case Iri(iri) => Some(ClassExpr.Named(iri))
      case b: AnyBlankNode =>
        val ts = triplesOf(b, skip)
        val local = mutable.ArrayBuffer.empty[Int]
        // `rdf:type owl:Class` and `rdf:type owl:Restriction` add nothing, so just mark them read.
        ts.foreach {
          case (i, Triple(_, Rdf.`type`, Owl.Class | Owl.Restriction)) => local += i
          case _ =>
        }
        val result =
          if ts.exists(_._2.pred == Owl.onProperty) then restriction(b, local)
          else booleanClassExpr(b, local)
        result.foreach(_ => touched ++= local)
        result
      case _ => None
    }

    private def booleanClassExpr(node: Node, touched: mutable.Buffer[Int]): Option[ClassExpr] =
      if has(node, Owl.intersectionOf) then
        objectOf(node, Owl.intersectionOf, touched).flatMap(graph.list(_, touched))
          .flatMap(all(_)(classExpr(_, touched))).map(ClassExpr.IntersectionOf(_))
      else if has(node, Owl.unionOf) then
        objectOf(node, Owl.unionOf, touched).flatMap(graph.list(_, touched))
          .flatMap(all(_)(classExpr(_, touched))).map(ClassExpr.UnionOf(_))
      else if has(node, Owl.complementOf) then
        objectOf(node, Owl.complementOf, touched).flatMap(classExpr(_, touched))
          .map(ClassExpr.ComplementOf(_))
      else if has(node, Owl.oneOf) then
        objectOf(node, Owl.oneOf, touched).flatMap(graph.list(_, touched)).flatMap(all(_)(iriOf))
          .map(ClassExpr.OneOf(_))
      else None

    private def restriction(node: AnyBlankNode, touched: mutable.Buffer[Int]): Option[ClassExpr] = {
      val property = objectOf(node, Owl.onProperty, touched).flatMap(propertyRef(_, touched))
      def count(predicate: Iri): Option[Int] =
        objectOf(node, predicate, touched).collect { case Literal(v, _) => v.trim.toIntOption }
          .flatten
      def qualifier: Option[Option[Filler]] =
        if has(node, Owl.onClass) then
          objectOf(node, Owl.onClass, touched).flatMap(classExpr(_, touched)).map(Some(_))
        else if has(node, Owl.onDataRange) then
          objectOf(node, Owl.onDataRange, touched).flatMap(dataRange(_, touched)).map(Some(_))
        else Some(None)
      def cardinality(bound: ClassExpr.Bound, plain: Iri, qualified: Iri): Option[ClassExpr] =
        if has(node, plain) then
          for p <- property; n <- count(plain) yield ClassExpr.Cardinality(bound, n, p)
        else
          for p <- property; n <- count(qualified); q <- qualifier
          yield ClassExpr.Cardinality(bound, n, p, q)

      val data = property.exists(p => !p.inverse && isKind(p.iri, EntityKind.DataProperty))
      def fillerOf(predicate: Iri) =
        objectOf(node, predicate, touched).flatMap(filler(_, touched, data))

      import ClassExpr.Bound.*
      if has(node, Owl.someValuesFrom) then
        for p <- property; f <- fillerOf(Owl.someValuesFrom) yield ClassExpr.SomeValuesFrom(p, f)
      else if has(node, Owl.allValuesFrom) then
        for p <- property; f <- fillerOf(Owl.allValuesFrom) yield ClassExpr.AllValuesFrom(p, f)
      else if has(node, Owl.hasValue) then
        for
          p <- property
          v <- objectOf(node, Owl.hasValue, touched).collect {
            case v: (Iri | Literal | LanguageLiteral) => v
          }
        yield ClassExpr.HasValue(p, v)
      else if has(node, Owl.hasSelf) then
        objectOf(node, Owl.hasSelf, touched)
        property.map(ClassExpr.HasSelf(_))
      else if has(node, Owl.minCardinality) || has(node, Owl.minQualifiedCardinality) then
        cardinality(Min, Owl.minCardinality, Owl.minQualifiedCardinality)
      else if has(node, Owl.maxCardinality) || has(node, Owl.maxQualifiedCardinality) then
        cardinality(Max, Owl.maxCardinality, Owl.maxQualifiedCardinality)
      else if has(node, Owl.cardinality) || has(node, Owl.qualifiedCardinality) then
        cardinality(Exact, Owl.cardinality, Owl.qualifiedCardinality)
      else None
    }

    private def dataRange(node: Node, touched: mutable.Buffer[Int]): Option[DataRange] =
      node match {
        case Iri(iri) => Some(DataRange.Datatype(iri))
        case b: AnyBlankNode =>
          val local = mutable.ArrayBuffer.empty[Int]
          triplesOf(b).foreach {
            case (i, Triple(_, Rdf.`type`, Rdfs.Datatype)) => local += i
            case _ =>
          }
          val result =
            if has(b, Owl.onDatatype) then datatypeRestriction(b, local)
            else if has(b, Owl.datatypeComplementOf) then
              objectOf(b, Owl.datatypeComplementOf, local).flatMap(dataRange(_, local))
                .map(DataRange.ComplementOf(_))
            else if has(b, Owl.equivalentClass) then
              objectOf(b, Owl.equivalentClass, local).flatMap(dataRange(_, local))
            else booleanDataRange(b, local)
          result.foreach(_ => touched ++= local)
          result
        case _ => None
      }

    /** `owl:onDatatype` with its `owl:withRestrictions`. */
    private def datatypeRestriction(node: Node, touched: mutable.Buffer[Int]): Option[DataRange] =
      for
        base <- objectOf(node, Owl.onDatatype, touched).flatMap(iriOf)
        facets <-
          if has(node, Owl.withRestrictions) then
            objectOf(node, Owl.withRestrictions, touched).flatMap(graph.list(_, touched))
          else Some(Nil)
        parsed <- all(facets)(facet(_, touched))
      yield DataRange.Restriction(base, parsed)

    private def booleanDataRange(node: Node, touched: mutable.Buffer[Int]): Option[DataRange] =
      if has(node, Owl.intersectionOf) then
        objectOf(node, Owl.intersectionOf, touched).flatMap(graph.list(_, touched))
          .flatMap(all(_)(dataRange(_, touched))).map(DataRange.IntersectionOf(_))
      else if has(node, Owl.unionOf) then
        objectOf(node, Owl.unionOf, touched).flatMap(graph.list(_, touched))
          .flatMap(all(_)(dataRange(_, touched))).map(DataRange.UnionOf(_))
      else if has(node, Owl.oneOf) then
        objectOf(node, Owl.oneOf, touched).flatMap(graph.list(_, touched))
          .flatMap(all(_)(literalOf)).map(DataRange.OneOf(_))
      else None

    private def facet(node: Node, touched: mutable.Buffer[Int]): Option[(String, Literal)] =
      triplesOf(node).collectFirst { case (i, Triple(_, Iri(f), l: Literal)) =>
        touched += i
        f -> l
      }
  }
}
