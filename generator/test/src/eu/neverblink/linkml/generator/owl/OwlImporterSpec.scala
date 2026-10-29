package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.RdfGeneratorBase.RdfFormat
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, JsonUtil}

import eu.neverblink.linkml.schemaview.{SchemaCodec, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class OwlImporterSpec extends AnyWordSpec, Matchers, OwlFixtures {
  import OwlImporterSpec.*

  "round-tripping" should {
    "give the same ontology again (linkml -> owl -> linkml -> owl)" when {
      for entry <- ModelCatalogue.all; format <- RdfFormat.values do
        s"model '${entry.name}' goes through $format" in {
          skipModels.get(entry.name).foreach(cancel(_))
          val first = OwlGenerator(using entry.model).ontology()
          val (sv, warnings) = reimport(entry.model, format)
          val second = OwlGenerator(using sv).ontology()
          withClue(s"Warnings:\n${warnings.mkString("\n")}\n") {
            difference(first, second) shouldBe empty
          }
        }
    }
  }

  "reading a document" should {
    "read Turtle, taking its prefix names" in {
      val ttl =
        """@prefix bk: <https://example.org/library/> .
          |@prefix owl: <http://www.w3.org/2002/07/owl#> .
          |@prefix : <https://example.org/other/> .
          |bk:Book a owl:Class ; owl:equivalentClass :Volume .
          |:Volume a owl:Class .
          |""".stripMargin
      val schema = OwlImporter().importSchema(stream(ttl))
      schema.defaultPrefix shouldBe Some("bk")
      schema.classes.keySet shouldBe Set("Book", "Volume")
      // LinkML doesn't allow an empty prefix.
      schema.prefixes.keySet should not contain ""
    }

    "read N-Triples with either parser" in {
      val nt = "<https://example.org/library/Book> " +
        "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .\n"
      for format <- RdfFormat.values do
        OwlImporter().importSchema(stream(nt), OwlImporter.Options(inputFormat = format))
          .classes.keySet shouldBe Set("Book")
    }
  }

  "importing a class axiom" should {
    "make the named conjunct of a defining intersection the parent" in {
      val result = importing(
        """:Manager a owl:Class ;
          |    owl:equivalentClass [ a owl:Class ; owl:intersectionOf (
          |        :Staff [ a owl:Restriction ;
          |                 owl:onProperty :manages ; owl:someValuesFrom :Team ] ) ] .
          |:Staff a owl:Class .
          |:Team a owl:Class .
          |:manages a owl:ObjectProperty .""",
      )
      result.schema.classes("Manager").isA.map(_.value) shouldBe Some("Staff")
      // LinkML cannot say that the conditions are also sufficient, so that half is reported.
      result.warnings should have size 1
    }

    "make the members of a defining union its children" in {
      val result = importing(
        """:Vehicle a owl:Class ;
          |    owl:equivalentClass [ a owl:Class ; owl:unionOf ( :Car :Truck ) ] .
          |:Car a owl:Class .
          |:Truck a owl:Class .""",
      )
      val classes = result.schema.classes
      classes("Vehicle").`abstract` shouldBe true
      classes("Car").isA.map(_.value) shouldBe Some("Vehicle")
      classes("Truck").isA.map(_.value) shouldBe Some("Vehicle")
      // `abstract` and the two children together say the whole axiom, so nothing is left over.
      result.warnings shouldBe empty
    }

    "make the members of a disjoint union its disjoint children" in {
      val result = importing(
        """:Colour a owl:Class ; owl:disjointUnionOf ( :Red :Green ) .
          |:Red a owl:Class .
          |:Green a owl:Class .""",
      )
      val classes = result.schema.classes
      classes("Colour").`abstract` shouldBe true
      classes("Colour").childrenAreMutuallyDisjoint shouldBe true
      classes("Red").isA.map(_.value) shouldBe Some("Colour")
      classes("Green").isA.map(_.value) shouldBe Some("Colour")
      result.warnings shouldBe empty
    }
  }

  "importing a datatype" should {
    "turn a list of literal values into a pattern" in {
      val result = importing(
        """:StatusCode a rdfs:Datatype ;
          |    owl:equivalentClass [ a rdfs:Datatype ; owl:oneOf ( "Open" "Closed" ) ] .""",
      )
      val statusCode = result.schema.types("StatusCode")
      statusCode.typeof.map(_.value) shouldBe Some("string")
      statusCode.pattern shouldBe Some("^(Open|Closed)$")
    }

    "escape regular expression characters in the values" in {
      val result = importing(
        """:Code a rdfs:Datatype ;
          |    owl:equivalentClass [ a rdfs:Datatype ; owl:oneOf ( "a.b" "c+d" ) ] .""",
      )
      result.schema.types("Code").pattern shouldBe Some("^(a\\.b|c\\+d)$")
    }

    "read length facets" in {
      val result = importing(
        """:Code a rdfs:Datatype ;
          |    owl:equivalentClass [ a rdfs:Datatype ; owl:onDatatype xsd:string ;
          |        owl:withRestrictions ( [ xsd:minLength 2 ] [ xsd:maxLength 5 ] ) ] .""",
      )
      val code = result.schema.types("Code")
      (code.minimumLength, code.maximumLength) shouldBe (Some(2), Some(5))
      result.warnings shouldBe empty
    }

    "report a facet it cannot express" in {
      val result = importing(
        """:ShortString a rdfs:Datatype ;
          |    owl:equivalentClass [ a rdfs:Datatype ; owl:onDatatype xsd:string ;
          |        owl:withRestrictions ( [ xsd:length 5 ] ) ] .""",
      )
      result.schema.types("ShortString").typeof.map(_.value) shouldBe Some("string")
      result.warnings.mkString should include("ShortString: xsd:length")
    }

    "not turn a datatype that is a subclass of rdfs:Literal into a class" in {
      // As in the RDF vocabulary, for `rdf:HTML`.
      val result = importing(
        """:Html a rdfs:Datatype ; rdfs:subClassOf rdfs:Literal ; rdfs:label "HTML" .""",
      )
      result.schema.types("Html").typeof.map(_.value) shouldBe Some("string")
      result.schema.classes shouldBe empty
      result.warnings shouldBe empty
    }

    "use a parent datatype as typeof" in {
      val result = importing(""":Code a rdfs:Datatype ; rdfs:subClassOf xsd:integer .""")
      result.schema.types("Code").typeof.map(_.value) shouldBe Some("integer")
      result.schema.classes shouldBe empty
      result.warnings shouldBe empty
    }

    "leave out RDF's built-in datatypes" in {
      val result = importing(
        """<http://www.w3.org/1999/02/22-rdf-syntax-ns#langString> a rdfs:Datatype ;
          |    rdfs:subClassOf rdfs:Literal .""",
      )
      result.schema.classes shouldBe empty
      result.schema.types.keySet should not contain "langString"
      result.warnings shouldBe empty
    }
  }

  "importing IRIs" should {
    "fail on an IRI that isn't a valid URI" in {
      (the[IllegalArgumentException] thrownBy importing(
        """:Person a owl:Class ; owl:equivalentClass <http://other.org/q[2]> .""",
      )).getMessage shouldBe
        "The IRI <http://other.org/q[2]> can't be imported, as it isn't a valid URI"
    }

    "fail on an IRI that would be read back as a CURIE" in {
      (the[IllegalArgumentException] thrownBy importing(
        """:Person a owl:Class .
          |:name a owl:DatatypeProperty ; rdfs:domain :Person ; rdfs:range <xsd:string> .""",
      )).getMessage shouldBe
        "The IRI <xsd:string> can't be imported, as 'xsd' is a prefix in the schema, so the IRI " +
        "would be read back as a CURIE with another meaning"
    }

    "fail on a schema id that isn't a valid URI" in {
      (the[IllegalArgumentException] thrownBy OwlImporter().importWithWarnings(
        stream(
          """@prefix owl: <http://www.w3.org/2002/07/owl#> .
            |<https://example.org/test[1]> a owl:Ontology .
            |<https://example.org/test/Person> a owl:Class .
            |""".stripMargin,
        ),
      )).getMessage should include("The schema id <https://example.org/test[1]> isn't a valid URI")
    }

    "keep a reference that can't be a URI as an annotation" in {
      val result = importing(
        """:Person a owl:Class ;
          |    rdfs:seeAlso <https://example.org/docs>, <https://example.org/a[1]>,
          |        "https://example.org/b c" .""",
      )
      val person = result.schema.classes("Person")
      person.seeAlso.map(_.original) shouldBe Seq("https://example.org/docs")
      person.annotations.values.map(_.extensionValue.toString).mkString should include(
        "https://example.org/a[1]",
      )
    }
  }
}

object OwlImporterSpec {

  private val slotUsageWithoutRange =
    "BUG: The OWL of a class with a `slot_usage` that sets neither `range` nor `any_of` gets an " +
      "extra `owl:allValuesFrom` restriction after it is imported"

  private val slotUsageUri =
    "LNK-236: OWL: a `slot_uri` or range set by a `slot_usage` is lost"

  /** Models that do not give the same ontology again, with the reason. */
  private val skipModels: Map[String, String] = Map(
    "allOfInheritance" -> slotUsageWithoutRange,
    "exactlyOneOfInheritance" -> slotUsageWithoutRange,
    "noneOfInheritance" -> slotUsageWithoutRange,
    "exactlyOneOfWidening" -> slotUsageWithoutRange,
    "allOfNoneOfChain" -> slotUsageWithoutRange,
    "requiredOverride" -> slotUsageWithoutRange,
    "importedOverride" -> slotUsageUri,
    "mixinFacets" -> slotUsageUri,
  )

  private def stream(text: String) = ByteArrayInputStream(text.getBytes(UTF_8))

  /** Imports `body` as Turtle, with the usual prefixes and an ontology header in front. */
  def importing(body: String): OwlImporter.Result =
    OwlImporter().importWithWarnings(
      stream(
        """@prefix : <https://example.org/test/> .
        |@prefix owl: <http://www.w3.org/2002/07/owl#> .
        |@prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#> .
        |@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        |<https://example.org/test> a owl:Ontology .
        |""".stripMargin + body.stripMargin + "\n",
      ),
    )

  /** Generates OWL, imports it back and loads the schema. Also returns the import warnings. */
  def reimport(sv: SchemaView, format: RdfFormat = RdfFormat.ttl): (SchemaView, Seq[String]) = {
    val text = OwlGenerator(using sv).serialize(OwlGenerator.Options(format = format))
    val result =
      OwlImporter().importWithWarnings(stream(text), OwlImporter.Options(inputFormat = format))
    val yaml = JsonUtil.write(SchemaCodec.encode(result.schema), JsonOutputFormat.yaml)
    val loaded = SchemaView.loadSchemaViewFromString(yaml) match {
      case Right(view) => view
      case Left(issues) => throw AssertionError(s"$issues\n$yaml")
    }
    (loaded, result.warnings)
  }

  /** One line per difference between the two ontologies, ignoring prefixes. */
  def difference(a: Ontology, b: Ontology): Seq[String] = {
    val header = Seq(
      Option.when(a.iri != b.iri)(s"iri: ${a.iri} vs ${b.iri}"),
      Option.when(a.versionIri != b.versionIri)(s"version: ${a.versionIri} vs ${b.versionIri}"),
      Option.when(a.imports.toSet != b.imports.toSet)(s"imports: ${a.imports} vs ${b.imports}"),
    ).flatten
    val annotations = (a.annotations.toSet diff b.annotations.toSet).map(x => s"- header $x") ++
      (b.annotations.toSet diff a.annotations.toSet).map(x => s"+ header $x")
    val axioms = (a.axioms.toSet diff b.axioms.toSet).map(x => s"- $x") ++
      (b.axioms.toSet diff a.axioms.toSet).map(x => s"+ $x")
    header ++ annotations.toSeq.sorted ++ axioms.toSeq.sorted
  }
}
