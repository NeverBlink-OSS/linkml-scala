package eu.neverblink.linkml.generator.rdfs

import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.eclipse.rdf4j.rio.{RDFFormat, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.StringReader

class RdfsGeneratorSpec extends AnyWordSpec, Matchers {
  "RdfsGenerator" should {
    def loadWithImports(schemaYaml: String): SchemaView =
      SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schemaYaml))

    // Shared part of the schema
    val schemaShared =
      """id: https://neverblink.eu/linkml/rdfs/test/
        |name: test
        |imports:
        |  - linkml:types"""

    "classes with basic types" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |    - some_yet_another_slot
           |slots:
           |  some_slot:
           |    domain: SomeClass
           |    range: string
           |  some_other_slot:
           |    range: integer
           |  some_yet_another_slot:
           |    range: boolean
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using schemaView).generate(_))
      turtle shouldBe
        """PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
          |PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeClass> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_slot> a rdf:Property ;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/SomeClass> ;
          |  rdfs:range xsd:string .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_other_slot> a rdf:Property ;
          |  rdfs:range xsd:integer .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_yet_another_slot> a rdf:Property ;
          |  rdfs:range xsd:boolean .
          |""".stripMargin
    }

    "emit RDFS label for LinkML titles" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    title: Some class
           |    slots:
           |    - some_slot
           |    - some_yet_another_slot
           |  SomeAnotherClass:
           |    slots:
           |    - some_other_slot
           |slots:
           |  some_slot:
           |    title: String
           |    range: string
           |  some_other_slot:
           |    range: integer
           |  some_yet_another_slot:
           |    range: SomeAnotherClass
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using schemaView).generate(_))
      turtle shouldBe
        """PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
          |PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeClass> a rdfs:Class ;
          |  rdfs:label "Some class" .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_slot> a rdf:Property ;
          |  rdfs:label "String" ;
          |  rdfs:range xsd:string .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_yet_another_slot> a rdf:Property ;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/SomeAnotherClass> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeAnotherClass> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_other_slot> a rdf:Property ;
          |  rdfs:range xsd:integer .
          |""".stripMargin
    }

    "emit RDFS seeAlso for LinkML see_also, expanding CURIEs" in {
      val input =
        s"""$schemaShared
           |prefixes:
           |  schema: http://schema.org/
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    see_also:
           |    - https://example.org/docs/some-class
           |    - schema:Thing
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |slots:
           |  some_slot:
           |    range: string
           |    see_also:
           |    - schema:name
           |  some_other_slot:
           |    range: SomeEnum
           |enums:
           |  SomeEnum:
           |    see_also:
           |    - schema:Enumeration
           |    permissible_values:
           |      SOME_VALUE:
           |        see_also:
           |        - https://example.org/docs/some-value
           |      SOME_OTHER_VALUE: {}
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using schemaView).generate(_))
      turtle shouldBe
        """PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
          |PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeClass> a rdfs:Class ;
          |  rdfs:seeAlso <https://example.org/docs/some-class> , <http://schema.org/Thing> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_slot> a rdf:Property ;
          |  rdfs:seeAlso <http://schema.org/name> ;
          |  rdfs:range xsd:string .
          |
          |<https://neverblink.eu/linkml/rdfs/test/some_other_slot> a rdf:Property ;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/SomeEnum> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/SomeEnum> a rdfs:Class ;
          |  rdfs:seeAlso <http://schema.org/Enumeration> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/SOME_VALUE> a <https://neverblink.eu/linkml/rdfs/test/SomeEnum> ;
          |  rdfs:seeAlso <https://example.org/docs/some-value> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/SOME_OTHER_VALUE> a <https://neverblink.eu/linkml/rdfs/test/SomeEnum> .
          |""".stripMargin
    }

    "classes with inheritance" in {
      val input =
        s"""$schemaShared
           |classes:
           |  Person:
           |    description: Represents a person.
           |    tree_root: true
           |  Course:
           |    description: Represents a course.
           |  Employee:
           |    description: Represents an employee.
           |    is_a: Person
           |    slots:
           |      - worksFor
           |  Professor:
           |    description: Represents a professor.
           |    is_a: Employee
           |    slots:
           |      - teaches
           |slots:
           |  teaches:
           |    description: Property indicating which course a professor teaches.
           |    range: Course
           |  worksFor:
           |    description: Property indicating who an employee works for.
           |    range: Person
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using schemaView).generate(_))
      turtle shouldBe
        """PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
          |PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
          |
          |<https://neverblink.eu/linkml/rdfs/test/Person> a rdfs:Class ;
          |  rdfs:comment "Represents a person." .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Course> a rdfs:Class ;
          |  rdfs:comment "Represents a course." .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Employee> a rdfs:Class ;
          |  rdfs:comment "Represents an employee." ;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/Person> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/worksFor> a rdf:Property ;
          |  rdfs:comment "Property indicating who an employee works for." ;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/Person> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Professor> a rdfs:Class ;
          |  rdfs:comment "Represents a professor." ;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/Employee> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/teaches> a rdf:Property ;
          |  rdfs:comment "Property indicating which course a professor teaches." ;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/Course> .
          |""".stripMargin
    }

    "work for recursive ADT" in {
      val input =
        s"""$schemaShared
           |classes:
           |  Node:
           |    tree_root: true
           |    attributes:
           |      name:
           |        key: true
           |        range: time
           |      children:
           |        # SimpleDict form = { name1: Node1, name2: Node2 }
           |        domain: Node
           |        range: Node
           |        multivalued: true
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using schemaView).generate(_))
      turtle shouldBe
        """PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
          |PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
          |
          |<https://neverblink.eu/linkml/rdfs/test/Node> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/name> a rdf:Property ;
          |  rdfs:range xsd:time .
          |
          |<https://neverblink.eu/linkml/rdfs/test/children> a rdf:Property ;
          |  rdfs:domain <https://neverblink.eu/linkml/rdfs/test/Node> ;
          |  rdfs:range <https://neverblink.eu/linkml/rdfs/test/Node> .
          |""".stripMargin
    }

    "work for mixins and diamond inheritance" in {
      val input =
        s"""$schemaShared
           |classes:
           |  MotorVehicle:
           |  PassengerVehicle:
           |    is_a: MotorVehicle
           |  Van:
           |    is_a: MotorVehicle
           |  Truck:
           |    is_a: MotorVehicle
           |  MiniVan:
           |    is_a: PassengerVehicle
           |    mixins:
           |      - Van
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using schemaView).generate(_))
      turtle shouldBe
        """PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
          |PREFIX rdfs: <http://www.w3.org/2000/01/rdf-schema#>
          |PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
          |
          |<https://neverblink.eu/linkml/rdfs/test/PassengerVehicle> a rdfs:Class ;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/MotorVehicle> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/MotorVehicle> a rdfs:Class .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Truck> a rdfs:Class ;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/MotorVehicle> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/MiniVan> a rdfs:Class ;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/PassengerVehicle> , <https://neverblink.eu/linkml/rdfs/test/Van> .
          |
          |<https://neverblink.eu/linkml/rdfs/test/Van> a rdfs:Class ;
          |  rdfs:subClassOf <https://neverblink.eu/linkml/rdfs/test/MotorVehicle> .
          |""".stripMargin
    }

    "include imported classes by default" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations"),
      )
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using sv).generate(_))
      turtle should include("linkml:Annotatable a rdfs:Class")
      turtle should include("linkml:Annotation a rdfs:Class")
      // imported from linkml:extensions
      turtle should include("linkml:Any a rdfs:Class")
      turtle should include("linkml:Extension a rdfs:Class")
      turtle should include("linkml:Extensible a rdfs:Class")
      "rdfs:Class".r.findAllMatchIn(turtle).size shouldBe 5
    }

    "not include imported classes when onlyClassesFromRootSchema=true" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/annotations"),
      )
      val turtle =
        RdfUtils.toTurtle(
          RdfsGenerator(using sv)
            .generate(_, RdfsGenerator.Options(onlyClassesFromRootSchema = true)),
        )
      turtle should include("linkml:Annotatable a rdfs:Class")
      turtle should include("linkml:Annotation a rdfs:Class")
      turtle should not include "linkml:Any a rdfs:Class"
      turtle should not include "linkml:Extension a rdfs:Class"
      turtle should not include "linkml:Extensible a rdfs:Class"
      "rdfs:Class".r.findAllMatchIn(turtle).size shouldBe 2
    }

    "emit an enum as an rdfs:Class with its permissible values as instances" in {
      val input =
        s"""$schemaShared
           |prefixes:
           |  ex: https://example.org/
           |  sd: http://www.w3.org/ns/sparql-service-description#
           |emit_prefixes:
           |  - ex
           |  - sd
           |enums:
           |  Functions:
           |    enum_uri: sd:Function
           |    title: Functions
           |    description: SPARQL functions.
           |    permissible_values:
           |      cos:
           |        meaning: ex:cos
           |        title: Cosine
           |        description: Computes the cosine.
           |      noMeaning:
           |        title: No meaning
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using schemaView).generate(_))
      // The enum itself becomes an rdfs:Class, its URI taken from enum_uri.
      turtle should include("sd:Function a rdfs:Class")
      turtle should include("""rdfs:label "Functions"""")
      turtle should include("""rdfs:comment "SPARQL functions."""")
      // A permissible value with a meaning becomes an instance of the enum class.
      turtle should include("ex:cos a sd:Function")
      turtle should include("""rdfs:label "Cosine"""")
      turtle should include("""rdfs:comment "Computes the cosine."""")
      // A permissible value without a meaning falls back to defaultPrefix + text.
      turtle should include(
        """<https://neverblink.eu/linkml/rdfs/test/noMeaning> a sd:Function""",
      )
      turtle should include("""rdfs:label "No meaning"""")
    }

    "emit valid, urlencoded synthetic URIs" in {
      val sv = ModelCatalogue.syntheticUris.model
      val turtle = RdfUtils.toTurtle(RdfsGenerator(using sv).generate(_))

      Seq(
        "%C5%81%C4%85czony%28class%29",
        "%C5%82%C4%85czony+%3Ctyp%3E",
        "%C5%82%C4%85czony+%5Bslot%5D",
        "inny+%C5%82%C4%85czony+%22slot%22",
        "%C5%81%C4%85czony%27enum%27",
        "%C5%82%C4%85czony+%7Bvalue%7D",
        "inny+%C5%82%C4%85czony+%5Cvalue%2F%2F",
      ).foreach { snippet =>
        turtle should include(snippet)
      }

      Rio.parse(StringReader(turtle), RDFFormat.TURTLE).isEmpty shouldBe false
    }

    "describe a URI once when several definitions share it" in {
      val input =
        s"""$schemaShared
           |prefixes:
           |  ex: https://example.org/
           |emit_prefixes:
           |  - ex
           |classes:
           |  VariantA:
           |    class_uri: ex:Thing
           |    title: A thing
           |    tree_root: true
           |    slots:
           |    - some_slot
           |  VariantB:
           |    class_uri: ex:Thing
           |    title: A thing
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    range: FunctionsA
           |enums:
           |  FunctionsA:
           |    enum_uri: ex:FunctionA
           |    permissible_values:
           |      cos:
           |        meaning: ex:cos
           |        title: Cosine
           |  FunctionsB:
           |    enum_uri: ex:FunctionB
           |    permissible_values:
           |      cos:
           |        meaning: ex:cos
           |        title: Cosine
           |""".stripMargin
      val schemaView = loadWithImports(input)
      val sink = new CollectingRdfSink
      RdfsGenerator(using schemaView).generate(sink)

      sink.triples.diff(sink.triples.distinct) shouldBe empty
      // Both class definitions describe ex:Thing, so it is typed and labelled once, and the slot
      // they share yields one property with one domain.
      val thing = Iri("https://example.org/Thing")
      sink.triples.count(_ == Triple(thing, Rdf.`type`, Rdfs.Class)) shouldBe 1
      sink.triples.count(_ == Triple(thing, Rdfs.label, Literal("A thing"))) shouldBe 1

      // LinkML seeing two unrelated classes decides it can't emit a domain, because the two classes are unrelated.
      // It's not aware that the two classes mean the same thing in RDF-land. Should this even be allowed?
      // TODO LNK-182: figure something out
//      val slot = Iri("https://neverblink.eu/linkml/rdfs/test/some_slot")
//      sink.triples.count(_ == Triple(slot, Rdfs.domain, thing)) shouldBe 1

      // The permissible value shared by both enums keeps a type for each of them, but is
      // labelled once.
      val cos = Iri("https://example.org/cos")
      sink.triples should contain(Triple(cos, Rdf.`type`, Iri("https://example.org/FunctionA")))
      sink.triples should contain(Triple(cos, Rdf.`type`, Iri("https://example.org/FunctionB")))
      sink.triples.count(_ == Triple(cos, Rdfs.label, Literal("Cosine"))) shouldBe 1
    }

    "not stack rdfs:domains" in {
      val input =
        s"""$schemaShared
          |prefixes:
          |  ex: https://example.org/
          |emit_prefixes:
          |  - ex
          |default_prefix: ex
          |
          |classes:
          |  Base:
          |    abstract: true
          |  A:
          |    is_a: Base
          |    slots:
          |     - some_slot
          |    slot_usage:
          |      some_slot:
          |        domain: A
          |  B:
          |    is_a: Base
          |    slots:
          |     - some_slot
          |    slot_usage:
          |      some_slot:
          |        domain: B
          |slots:
          |  some_slot:
          |    range: string
          |""".stripMargin

      val schemaView = loadWithImports(input)
      val sink = CollectingRdfSink()
      RdfsGenerator(using schemaView).generate(sink)
      sink.triples should contain(
        Triple(Iri("https://example.org/some_slot"), Rdfs.domain, Iri("https://example.org/Base")),
      )
      sink.triples should not contain Triple(
        Iri("https://example.org/some_slot"),
        Rdfs.domain,
        Iri("https://example.org/A"),
      )
      sink.triples should not contain Triple(
        Iri("https://example.org/some_slot"),
        Rdfs.domain,
        Iri("https://example.org/B"),
      )
    }

    "not emit rdfs:domain if a property is reused across unrelated classes" in {
      val input =
        s"""$schemaShared
           |prefixes:
           |  ex: https://example.org/
           |emit_prefixes:
           |  - ex
           |default_prefix: ex
           |
           |classes:
           |  A:
           |    slots:
           |     - some_slot
           |    slot_usage:
           |      some_slot:
           |        domain: A
           |  B:
           |    slots:
           |     - some_slot
           |    slot_usage:
           |      some_slot:
           |        domain: B
           |slots:
           |  some_slot:
           |    range: string
           |""".stripMargin

      val schemaView = loadWithImports(input)
      val sink = CollectingRdfSink()
      RdfsGenerator(using schemaView).generate(sink)
      sink.triples should not contain Triple(
        Iri("https://example.org/some_slot"),
        Rdfs.domain,
        Iri("https://example.org/A"),
      )
      sink.triples should not contain Triple(
        Iri("https://example.org/some_slot"),
        Rdfs.domain,
        Iri("https://example.org/B"),
      )
    }

    "emit rdfs:domain for the lowest common ancestor" in {
      val input =
        s"""$schemaShared
           |prefixes:
           |  ex: https://example.org/
           |emit_prefixes:
           |  - ex
           |default_prefix: ex
           |
           |classes:
           |  Base0:
           |  Base1:
           |    is_a: Base0
           |  A:
           |    is_a: Base1
           |    slots:
           |     - some_slot
           |    slot_usage:
           |      some_slot:
           |        domain: A
           |  B:
           |    is_a: Base1
           |    slots:
           |     - some_slot
           |    slot_usage:
           |      some_slot:
           |        domain: B
           |slots:
           |  some_slot:
           |    range: string
           |""".stripMargin

      val schemaView = loadWithImports(input)
      val sink = CollectingRdfSink()
      RdfsGenerator(using schemaView).generate(sink)
      sink.triples should not contain Triple(
        Iri("https://example.org/some_slot"),
        Rdfs.domain,
        Iri("https://example.org/Base0"),
      )
      sink.triples should contain(
        Triple(Iri("https://example.org/some_slot"), Rdfs.domain, Iri("https://example.org/Base1")),
      )
      sink.triples should not contain Triple(
        Iri("https://example.org/some_slot"),
        Rdfs.domain,
        Iri("https://example.org/A"),
      )
      sink.triples should not contain Triple(
        Iri("https://example.org/some_slot"),
        Rdfs.domain,
        Iri("https://example.org/B"),
      )
    }

    "emit subPropertyOf for slot inheritance" in {
      val input =
        s"""$schemaShared
           |prefixes:
           |  ex: https://example.org/
           |emit_prefixes:
           |  - ex
           |default_prefix: ex
           |
           |classes:
           |  SomeClass:
           |    slots: [ some_slot ]
           |slots:
           |  some_base_slot:
           |    abstract: true
           |  some_mixin_slot:
           |    mixin: true
           |  some_slot:
           |    is_a: some_base_slot
           |    mixins: [ some_mixin_slot ]
           |    range: string
           |""".stripMargin

      val schemaView = loadWithImports(input)
      val sink = CollectingRdfSink()
      RdfsGenerator(using schemaView).generate(sink)

      sink.triples should contain(
        Triple(
          Iri("https://example.org/some_slot"),
          Rdfs.subPropertyOf,
          Iri("https://example.org/some_base_slot"),
        ),
      )
      sink.triples should contain(
        Triple(
          Iri("https://example.org/some_slot"),
          Rdfs.subPropertyOf,
          Iri("https://example.org/some_mixin_slot"),
        ),
      )
    }

    "emit rdfs:subclassOf for enum is_a/mixins inheritance" in {
      val sv = ModelCatalogue.enumInheritance.model
      val base = "https://neverblink.eu/linkml/tests/enumInheritance/"
      val sink = CollectingRdfSink()

      RdfsGenerator(using sv).generate(sink)

      sink.triples should contain allOf (
        Triple(
          Iri(base + "SomeEnum"),
          Rdfs.subClassOf,
          Iri(base + "SomeClass"),
        ),
        Triple(
          Iri(base + "SomeEnum"),
          Rdfs.subClassOf,
          Iri(base + "SomeBaseEnum"),
        ),
        Triple(
          Iri(base + "SomeOtherEnum"),
          Rdfs.subClassOf,
          Iri(base + "SomeClass"),
        ),
        Triple(
          Iri(base + "SomeOtherEnum"),
          Rdfs.subClassOf,
          Iri(base + "SomeBaseEnum"),
        ),
      )
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all do
        s"model '${entry.model.root.name}'" in {
          val sink = new CollectingRdfSink
          RdfsGenerator(using entry.model).generate(sink)
          sink.triples should not be empty
          withClue("duplicate triples in the output:") {
            sink.triples.diff(sink.triples.distinct) shouldBe empty
          }
        }
    }
  }
}
