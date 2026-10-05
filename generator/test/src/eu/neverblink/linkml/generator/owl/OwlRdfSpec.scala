package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.owl.ClassExpr.*
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.rdf.io.StringSink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OwlRdfSpec extends AnyWordSpec, Matchers {
  import OwlRdfSpec.*

  "OWL in RDF" should {
    for (label, axiom) <- everyAxiom do
      s"round-trip $label" in {
        val ontology = header.copy(axioms = Seq(axiom))
        val back = roundTrip(ontology)
        back.unparsed shouldBe empty
        back.ontology.axioms.toSet shouldBe ontology.axioms.toSet
      }

    "round-trip the header" in {
      val back = roundTrip(header)
      back.unparsed shouldBe empty
      back.ontology shouldBe header
    }

    "round-trip all axioms together, also through Turtle's grouping" in {
      val ontology = header.copy(axioms = everyAxiom.map(_._2))
      val back = roundTrip(ontology)
      back.unparsed shouldBe empty
      back.ontology.axioms.toSet shouldBe ontology.axioms.toSet
      // TurtleWriter fails on triples in an order it can't group, so this also checks the order.
      val turtle = StringSink()
      val writer = TurtleWriter(turtle)
      OwlRdfWriter.write(ontology, writer)
      writer.finish()
      turtle.result should include("ex:A a owl:Class ;")
    }

    "read rdfs:Class and rdf:Property, picking the property kind from the range" in {
      val back = OwlRdfReader.read(
        Seq(
          Triple(ex("C"), Rdf.`type`, Rdfs.Class),
          Triple(ex("p"), Rdf.`type`, Rdf.Property),
          Triple(ex("p"), Rdfs.range, XmlSchema.string),
          Triple(ex("q"), Rdf.`type`, Rdf.Property),
          Triple(ex("q"), Rdfs.range, ex("C")),
        ),
      )
      back.unparsed shouldBe empty
      back.ontology.axioms should contain allOf (
        Declaration(EntityKind.Class, ns + "C"),
        Declaration(EntityKind.DataProperty, ns + "p"),
        Declaration(EntityKind.ObjectProperty, ns + "q"),
        Range(ns + "p", DataRange.Datatype(XmlSchema.string.value)),
        Range(ns + "q", Named(ns + "C")),
      )
    }

    "read an expression stated directly on a named class as an equivalence" in {
      val list = BlankNode("l")
      val rest = BlankNode("r")
      val back = OwlRdfReader.read(
        Seq(
          Triple(ex("E"), Rdf.`type`, Owl.Class),
          Triple(ex("E"), Owl.oneOf, list),
          Triple(list, Rdf.first, ex("a")),
          Triple(list, Rdf.rest, rest),
          Triple(rest, Rdf.first, ex("b")),
          Triple(rest, Rdf.rest, Rdf.nil),
        ),
      )
      back.unparsed shouldBe empty
      back.ontology.axioms should contain(
        EquivalentClasses(Seq(Named(ns + "E"), OneOf(Seq(ns + "a", ns + "b")))),
      )
    }

    "keep the triples it does not understand" in {
      val odd = Triple(BlankNode("x"), ex("p"), Literal("1"))
      OwlRdfReader.read(Seq(odd)).unparsed shouldBe Seq(odd)
    }
  }
}

object OwlRdfSpec {
  val ns = "http://example.org/"

  def ex(local: String): Iri = Iri(ns + local)

  private def c(local: String): Named = Named(ns + local)

  private val p = ns + "p"
  private val d = ns + "d"

  val header: Ontology = Ontology(
    iri = Some(ns + "ontology"),
    versionIri = Some(ns + "ontology/1.0"),
    imports = Seq("http://example.com/other"),
    annotations = Seq(
      Annotation.text("http://purl.org/dc/terms/title", "Example"),
      Annotation.text(Rdfs.label.value, "Exemple", Some("fr")),
    ),
    prefixes = Seq("ex" -> ns),
  )

  private val label = Seq(Annotation.text(Rdfs.comment.value, "Said so."))

  val everyAxiom: Seq[(String, Axiom)] = Seq(
    "a declaration" -> Declaration(EntityKind.Class, ns + "A"),
    "a data property declaration" -> Declaration(EntityKind.DataProperty, d),
    "an annotated declaration" -> Declaration(EntityKind.ObjectProperty, p, label),
    "a subclass axiom" -> SubClassOf(c("A"), c("B")),
    "an annotated subclass axiom" -> SubClassOf(c("A"), c("C"), label),
    "an annotated subclass of an expression" ->
      SubClassOf(c("A"), SomeValuesFrom(PropertyRef(p), c("B")), label),
    "a general class inclusion" -> SubClassOf(SomeValuesFrom(PropertyRef(p), c("B")), c("A")),
    "an equivalence to an intersection" ->
      EquivalentClasses(
        Seq(c("A"), IntersectionOf(Seq(c("B"), AllValuesFrom(PropertyRef(p), c("C"))))),
      ),
    "a union" -> SubClassOf(c("A"), UnionOf(Seq(c("B"), c("C")))),
    "a complement" -> SubClassOf(c("A"), ComplementOf(c("B"))),
    "an enumeration" -> EquivalentClasses(Seq(c("A"), OneOf(Seq(ns + "i", ns + "j")))),
    "a restriction on an inverse" -> SubClassOf(
      c("A"),
      SomeValuesFrom(PropertyRef(p, true), c("B")),
    ),
    "a value restriction" -> SubClassOf(c("A"), HasValue(PropertyRef(p), ex("i"))),
    "a literal value restriction" -> SubClassOf(c("A"), HasValue(PropertyRef(d), Literal("x"))),
    "a self restriction" -> SubClassOf(c("A"), HasSelf(PropertyRef(p))),
    "a cardinality" -> SubClassOf(c("A"), Cardinality(Bound.Max, 1, PropertyRef(p))),
    "a qualified cardinality" ->
      SubClassOf(c("A"), Cardinality(Bound.Exact, 2, PropertyRef(p), Some(c("B")))),
    "a qualified data cardinality" ->
      SubClassOf(
        c("A"),
        Cardinality(Bound.Min, 1, PropertyRef(d), Some(DataRange.Datatype(XmlSchema.string.value))),
      ),
    "a data restriction" -> SubClassOf(
      c("A"),
      AllValuesFrom(
        PropertyRef(d),
        DataRange.Restriction(
          XmlSchema.integer.value,
          Seq(XmlSchema.minInclusive.value -> Literal("1", XmlSchema.integer)),
        ),
      ),
    ),
    "nested expressions in a list" -> SubClassOf(
      c("A"),
      UnionOf(
        Seq(
          SomeValuesFrom(PropertyRef(p), c("B")),
          IntersectionOf(Seq(c("C"), ComplementOf(c("D")))),
        ),
      ),
    ),
    "a pairwise disjointness" -> DisjointClasses(Seq(c("A"), c("B"))),
    "an all-disjoint axiom" -> DisjointClasses(Seq(c("A"), c("B"), c("C"))),
    "a disjoint union" -> DisjointUnion(ns + "A", Seq(c("B"), c("C"))),
    "a subproperty" -> SubPropertyOf(p, ns + "q"),
    "a property chain" -> PropertyChain(p, Seq(PropertyRef(ns + "q"), PropertyRef(ns + "r", true))),
    "equivalent properties" -> EquivalentProperties(Seq(p, ns + "q")),
    "disjoint properties" -> DisjointProperties(Seq(p, ns + "q")),
    "all-disjoint properties" -> DisjointProperties(Seq(p, ns + "q", ns + "r")),
    "inverse properties" -> InverseProperties(p, ns + "q"),
    "a domain" -> Domain(p, c("A")),
    "a union domain" -> Domain(p, UnionOf(Seq(c("A"), c("B")))),
    "a class range" -> Range(p, c("B")),
    "a datatype range" -> Range(d, DataRange.Datatype(XmlSchema.string.value)),
    "a union datatype range" -> Range(
      d,
      DataRange.UnionOf(
        Seq(
          DataRange.Datatype(XmlSchema.string.value),
          DataRange.Datatype(XmlSchema.integer.value),
        ),
      ),
    ),
    "a literal enumeration range" ->
      Range(d, DataRange.OneOf(Seq(Literal("a"), LanguageLiteral("b", "en")))),
    "a characteristic" -> Characteristic(p, PropertyCharacteristic.Transitive),
    "a datatype definition" -> DatatypeDefinition(
      ns + "Small",
      DataRange.Restriction(
        XmlSchema.integer.value,
        Seq(
          XmlSchema.minInclusive.value -> Literal("0", XmlSchema.integer),
          XmlSchema.maxInclusive.value -> Literal("9", XmlSchema.integer),
        ),
      ),
    ),
    "a key" -> HasKey(c("A"), Seq(p, d)),
    "a class assertion" -> ClassAssertion(c("A"), ns + "i"),
    "a property assertion" -> PropertyAssertion(p, ns + "i", ex("j")),
    "a same-individual axiom" -> SameIndividual(Seq(ns + "i", ns + "j")),
    "a different-individuals axiom" -> DifferentIndividuals(Seq(ns + "i", ns + "j", ns + "k")),
    "an annotation" -> AnnotationAssertion(ns + "A", Annotation.text(Rdfs.label.value, "A")),
    "an annotated annotation" ->
      AnnotationAssertion(ns + "A", Annotation(Rdfs.seeAlso.value, ex("B")), label),
  )

  /** Writes N-Triples and reads them back. The added declarations tell the reader what kind of
    * property or datatype each IRI is. They are removed from the result.
    */
  def roundTrip(ontology: Ontology): OwlRdfReader.Result = {
    val kinds = Seq(
      Declaration(EntityKind.ObjectProperty, p),
      Declaration(EntityKind.DataProperty, d),
      Declaration(EntityKind.ObjectProperty, ns + "q"),
      Declaration(EntityKind.ObjectProperty, ns + "r"),
      Declaration(EntityKind.Datatype, ns + "Small"),
    )
    val withKinds =
      ontology.copy(axioms = ontology.axioms ++ kinds.filterNot(ontology.axioms.contains))
    val out = StringSink()
    val writer = NTriplesWriter(out)
    OwlRdfWriter.write(withKinds, writer)
    writer.finish()
    val sink = CollectingRdfSink()
    NTriplesParser.parse(out.result, sink)
    val result = OwlRdfReader.read(sink.triples)
    result.copy(ontology =
      result.ontology.copy(axioms =
        result.ontology.axioms.filterNot(a => kinds.contains(a) && !ontology.axioms.contains(a)),
      ),
    )
  }
}
