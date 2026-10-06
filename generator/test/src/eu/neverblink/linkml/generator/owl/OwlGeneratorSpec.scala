package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.owl.ClassExpr.*
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OwlGeneratorSpec extends AnyWordSpec, Matchers, OwlFixtures {

  private val xsd = XmlSchema.prefix
  private def c(local: String) = Named(ns + local)

  private val ageRange =
    DataRange.Restriction(
      xsd + "integer",
      Seq(xsd + "minInclusive" -> Literal("0", XmlSchema.integer)),
    )

  private def datatypesIn(axiom: Axiom): Seq[String] = {
    def range(r: Filler): Seq[String] = r match {
      case DataRange.Datatype(iri) => Seq(iri)
      case DataRange.Restriction(iri, _) => Seq(iri)
      case DataRange.UnionOf(ops) => ops.flatMap(range)
      case DataRange.IntersectionOf(ops) => ops.flatMap(range)
      case AllValuesFrom(_, f) => range(f)
      case SomeValuesFrom(_, f) => range(f)
      case _ => Nil
    }
    axiom match {
      case Declaration(EntityKind.Datatype, iri, _) => Seq(iri)
      case Range(_, r, _) => range(r)
      case SubClassOf(_, sup, _) => range(sup)
      case _ => Nil
    }
  }

  private val personinfo = """
    |classes:
    |  NamedThing:
    |    abstract: true
    |    description: Anything with a name.
    |    attributes:
    |      id:
    |        identifier: true
    |      name:
    |        required: true
    |  Person:
    |    is_a: NamedThing
    |    title: A person
    |    slots:
    |      - age
    |      - friends
    |      - status
    |  Organisation:
    |    is_a: NamedThing
    |    slots:
    |      - members
    |slots:
    |  age:
    |    range: Age
    |  friends:
    |    range: Person
    |    multivalued: true
    |    symmetric: true
    |  members:
    |    range: Person
    |    multivalued: true
    |    minimum_cardinality: 1
    |  status:
    |    range: Status
    |types:
    |  Age:
    |    typeof: integer
    |    minimum_value: 0
    |enums:
    |  Status:
    |    permissible_values:
    |      ALIVE:
    |        description: Living.
    |      DEAD:
    """

  "OwlGenerator" should {

    "declare classes with their parents and restrictions" in {
      val axioms = ontologyOf(personinfo).axioms
      axioms should contain allOf (
        Declaration(EntityKind.Class, ns + "Person"),
        SubClassOf(c("Person"), c("NamedThing")),
        SubClassOf(c("Person"), AllValuesFrom(PropertyRef(ns + "age"), ageRange)),
        SubClassOf(c("Person"), Cardinality(Bound.Max, 1, PropertyRef(ns + "age"))),
        SubClassOf(c("Person"), AllValuesFrom(PropertyRef(ns + "friends"), c("Person"))),
        SubClassOf(c("Organisation"), Cardinality(Bound.Min, 1, PropertyRef(ns + "members"))),
        SubClassOf(c("NamedThing"), Cardinality(Bound.Exact, 1, PropertyRef(ns + "name"))),
        SubClassOf(c("NamedThing"), UnionOf(Seq(c("Person"), c("Organisation")))),
      )
      // No min-0 restrictions. The identifier is the node's IRI, not a property.
      axioms.collect { case SubClassOf(_, Cardinality(Bound.Min, 0, _, _), _) => () } shouldBe empty
      axioms.collect { case d @ Declaration(_, iri, _) if iri == ns + "id" => d } shouldBe empty
    }

    "declare slots with their range, kind and characteristics" in {
      val axioms = ontologyOf(personinfo).axioms
      axioms should contain allOf (
        Declaration(EntityKind.DataProperty, ns + "age"),
        Range(ns + "age", ageRange),
        Characteristic(ns + "age", PropertyCharacteristic.Functional),
        Declaration(EntityKind.ObjectProperty, ns + "friends"),
        Characteristic(ns + "friends", PropertyCharacteristic.Symmetric),
      )
      axioms should not contain Characteristic(ns + "friends", PropertyCharacteristic.Functional)
    }

    "use the uri of a constrained type, as SHACL does, narrowed by its constraints" in {
      val axioms = ontologyOf(personinfo).axioms
      axioms.collect { case d: DatatypeDefinition => d } shouldBe empty
      axioms.collect { case d @ Declaration(EntityKind.Datatype, _, _) => d } shouldBe empty
    }

    "declare and define a type with a uri of its own" in {
      ontologyOf("""
        |types:
        |  Percentage:
        |    typeof: float
        |    uri: ex:Percentage
        |    maximum_value: 100
        |""").axioms should contain allOf (
        Declaration(EntityKind.Datatype, ns + "Percentage"),
        DatatypeDefinition(
          ns + "Percentage",
          DataRange.Restriction(
            xsd + "float",
            Seq(xsd + "maxInclusive" -> Literal("100", XmlSchema.float)),
          ),
        ),
      )
    }

    "use the title as the label, falling back to the name" in {
      val axioms = ontologyOf(personinfo).axioms
      val label = "http://www.w3.org/2000/01/rdf-schema#label"
      axioms should contain allOf (
        AnnotationAssertion(ns + "Person", Annotation.text(label, "A person")),
        AnnotationAssertion(ns + "Organisation", Annotation.text(label, "Organisation")),
      )
      axioms should not contain AnnotationAssertion(ns + "Person", Annotation.text(label, "Person"))
    }

    "write descriptions as rdfs:comment, or as skos:definition when asked" in {
      val schema =
        """classes:
          |  Dog:
          |    description: A good boy.
          |"""
      val comment = Annotation.text("http://www.w3.org/2000/01/rdf-schema#comment", "A good boy.")
      val definition =
        Annotation.text("http://www.w3.org/2004/02/skos/core#definition", "A good boy.")
      ontologyOf(schema).axioms should contain(AnnotationAssertion(ns + "Dog", comment))
      val linkml = OwlGenerator.Options(metadataProfile = OwlGenerator.MetadataProfile.linkml)
      val axioms = ontologyOf(schema, linkml).axioms
      axioms should contain(AnnotationAssertion(ns + "Dog", definition))
      axioms should not contain AnnotationAssertion(ns + "Dog", comment)
    }

    "make up no label for a class from another namespace" in {
      val axioms = ontologyOf(
        """prefixes:
          |  other: https://other.example.com/
          |classes:
          |  Dog:
          |    is_a: Animal
          |  Animal:
          |    class_uri: other:Animal
          |  Cat:
          |    class_uri: other:Cat
          |    title: A cat
          |""",
      ).axioms
      val label = "http://www.w3.org/2000/01/rdf-schema#label"
      val other = "https://other.example.com/"
      axioms should contain allOf (
        Declaration(EntityKind.Class, other + "Animal"),
        SubClassOf(c("Dog"), Named(other + "Animal")),
        AnnotationAssertion(ns + "Dog", Annotation.text(label, "Dog")),
        AnnotationAssertion(other + "Cat", Annotation.text(label, "A cat")),
      )
      axioms.collect {
        case a @ AnnotationAssertion(s, _, _) if s == other + "Animal" => a
      } shouldBe
        empty
    }

    "make an enum a subclass of its parent enums and classes" in {
      val axioms = ontologyOf(
        """classes:
          |  Action: {}
          |enums:
          |  BaseAction:
          |    mixin: true
          |  ConsumeAction:
          |    is_a: Action
          |    mixins: [BaseAction]
          |    permissible_values:
          |      PAUSE:
          |""",
      ).axioms
      axioms should contain allOf (
        SubClassOf(c("ConsumeAction"), c("Action")),
        SubClassOf(c("ConsumeAction"), c("BaseAction")),
      )
    }

    "make an enum the class of its permissible values" in {
      val axioms = ontologyOf(personinfo).axioms
      val alive = ns + "Status.ALIVE"
      axioms should contain allOf (
        Declaration(EntityKind.NamedIndividual, alive),
        ClassAssertion(c("Status"), alive),
        EquivalentClasses(Seq(c("Status"), OneOf(Seq(alive, ns + "Status.DEAD")))),
        AnnotationAssertion(
          alive,
          Annotation.text("http://www.w3.org/2000/01/rdf-schema#label", "ALIVE"),
        ),
        AnnotationAssertion(
          alive,
          Annotation.text("http://www.w3.org/2000/01/rdf-schema#comment", "Living."),
        ),
      )
    }

    "use the same IRIs as the RDFS and SHACL generators" when {
      for entry <- ModelCatalogue.all do
        s"model '${entry.name}'" in {
          given SchemaView = entry.model
          val owl = OwlGenerator().ontology()
          val declared = owl.axioms.collect { case Declaration(kind, iri, _) => iri -> kind }
            .groupMap(_._1)(_._2)
          def is(iri: String, kinds: EntityKind*) =
            withClue(s"$iri: ")(declared.getOrElse(iri, Nil).intersect(kinds) should not be empty)
          val properties =
            Seq(EntityKind.ObjectProperty, EntityKind.DataProperty, EntityKind.AnnotationProperty)
          val linkml = "https://w3id.org/linkml/"

          val rdfs = CollectingRdfSink()
          RdfsGenerator().generate(rdfs, RdfsGenerator.Options())
          rdfs.triples.foreach {
            case Triple(Iri(s), Rdf.`type`, Rdfs.Class) if !s.startsWith(linkml) =>
              is(s, EntityKind.Class)
            case Triple(Iri(s), Rdf.`type`, Rdf.Property) => is(s, properties*)
            case _ =>
          }

          val shacl = CollectingRdfSink()
          ShaclGenerator().generate(shacl, ShaclGenerator.Options())
          val datatypes = owl.axioms.flatMap(datatypesIn).toSet
          shacl.triples.foreach {
            case Triple(_, Shacl.targetClass | Shacl.`class`, Iri(c)) if !c.startsWith(linkml) =>
              is(c, EntityKind.Class)
            case Triple(_, Shacl.path, Iri(p)) => is(p, properties*)
            case Triple(_, Shacl.datatype, Iri(d)) if !d.startsWith(linkml) =>
              withClue(s"$d: ")(datatypes should contain(d))
            case _ =>
          }
          def members(cell: Node): Seq[Node] =
            if cell == Rdf.nil then Nil
            else {
              val own = shacl.triples.filter(_.subj == cell)
              own.filter(_.pred == Rdf.first).map(_.obj) ++
                own.filter(_.pred == Rdf.rest).flatMap(t => members(t.obj))
            }
          shacl.triples.filter(_.pred == Shacl.in).flatMap(t => members(t.obj)).foreach {
            case Iri(v) => is(v, EntityKind.NamedIndividual)
            case _ =>
          }
        }
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.allOptIn do
        s"model '${entry.name}'" in {
          OwlGenerator(using entry.model).serialize() should not be empty
        }
    }
  }
}
