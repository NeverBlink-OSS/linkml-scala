package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.ossie.OssieCases.*
import eu.neverblink.linkml.generator.ossie.expression.{Expression, Literal}
import eu.neverblink.linkml.generator.util.JsonOutputFormat
import eu.neverblink.linkml.metamodel.{SchemaDefinitionImpl, SlotDefinitionImpl}
import eu.neverblink.linkml.runtime.{PlainText, Reference}
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets

/** Tests for the Ossie -> LinkML mapping, and for the two directions agreeing with each other. */
class OssieImporterSpec extends AnyWordSpec, Matchers, OssieFixtures {

  import OssieImporterSpec.*

  "round-tripping" should {
    "preserve the original Ossie ontology (ossie->linkml->ossie)" when {
      for c <- OssieCases.all do
        s"Ossie test case: ${c.label}" in {
          val original = yamlOf(c)
          OssieGenerator(using reimport(original)).serialize() shouldBe original
        }

      for entry <- ModelCatalogue.all do
        s"Model catalogue: ${entry.name}" in {
          val original = OssieGenerator(using entry.model)
            .serialize(OssieGenerator.Options(outputFormat = JsonOutputFormat.json))
          OssieGenerator(using reimport(original))
            .serialize(
              OssieGenerator.Options(outputFormat = JsonOutputFormat.json),
            ) shouldBe original
        }
    }

    "be able to parse expressions written by the Ossie generator" when {
      for c <- OssieCases.all do
        c.label in {
          val o = ontologyOf(c)
          val requires = o.ontology.flatMap(concept =>
            concept.requires ++ concept.relationships.flatMap(_.requires),
          )
          requires.filter(Expression.parse(_).isEmpty) shouldBe empty
        }
    }

    "survive being written and read as JSON" in {
      val original = OssieGenerator(using schemaOf(severalKinds))
        .serialize(OssieGenerator.Options(outputFormat = JsonOutputFormat.json))
      OssieGenerator(using reimport(original))
        .serialize(OssieGenerator.Options(outputFormat = JsonOutputFormat.json)) shouldBe original
    }
  }

  "the document" should {
    "carry over the name and description" in {
      val schema = importOf(ossie("""
        |description: A demo ontology.
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """))
      schema.name shouldBe "spec"
      schema.description shouldBe Some(PlainText("A demo ontology."))
    }

    "invent a schema id, since an Ossie ontology has none" in {
      importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """)).id.original shouldBe "https://example.org/spec"
    }

    "use the schema id the caller asked for instead" in {
      val schema = OssieImporter().importSchemaFromString(
        ossie("""
          |ontology:
          |  - concept: Thing
          |    type: EntityType
          """),
        OssieImporter.Options(schemaId = Some("https://example.com/mine")),
      )
      schema.id.original shouldBe "https://example.com/mine"
    }

    "import the LinkML types, so that the ranges it writes resolve" in {
      val schema = importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """))
      schema.imports.map(_.original) shouldBe Seq("linkml:types")
      schema.prefixes.keys should contain("linkml")
    }

    "put ai_context back where the generator reads it from" in {
      val schema = importOf(ossie("""
        |ai_context:
        |  instructions: Prefer the full name.
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """))
      val extension = schema.extensions("ai_context")
      extension.extensionTag.original shouldBe "ai_context"
      extension.extensionValue.value should include("instructions: Prefer the full name.")
    }

    "serialize as JSON when asked to" in {
      val json = OssieImporter().serialize(
        stream(ossie("""
          |ontology:
          |  - concept: Thing
          |    type: EntityType
          """)),
        OssieImporter.Options(outputFormat = JsonOutputFormat.json),
      )
      json should startWith("{")
      json should include("\"name\": \"spec\"")
    }

    "produce a schema that has nothing wrong with it" in {
      reimport(yamlOf(severalKinds)).validationProblems shouldBe empty
    }
  }

  "reading from a stream" should {
    "decode UTF-8 across the read buffer" in {
      val long = "łąka🎸" * 2000
      val schema = OssieImporter().importSchema(stream(ossie(s"""
        |description: "$long"
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """)))
      schema.description shouldBe Some(PlainText(long))
    }

    "not close the stream it was handed" in {
      var closed = false
      val document = ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """)
      val in = new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)) {
        override def close(): Unit = closed = true
      }
      OssieImporter().importSchema(in)
      closed shouldBe false
    }
  }

  "concepts" should {
    "make a class out of an EntityType" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: Book
        |    type: EntityType
        |    description: A book.
        """)).classes("Book")
      cls.description shouldBe Some(PlainText("A book."))
    }

    "make an enum out of a ValueType that lists the values it allows" in {
      val e = importOf(ossie("""
        |ontology:
        |  - concept: Status
        |    type: ValueType
        |    extends: [String]
        |    requires:
        |      - Status IN ('ALIVE', 'DEAD')
        """)).enums("Status")
      e.permissibleValues.keys.toSeq shouldBe Seq("ALIVE", "DEAD")
      e.permissibleValues("ALIVE").text shouldBe "ALIVE"
    }

    "unescape a doubled apostrophe in a permissible value" in {
      importOf(ossie("""
        |ontology:
        |  - concept: Kind
        |    type: ValueType
        |    extends: [String]
        |    requires:
        |      - Kind IN ('it''s')
        """)).enums("Kind").permissibleValues.keys.toSeq shouldBe Seq("it's")
    }

    "make a named type out of a ValueType that does not" in {
      val t = importOf(ossie("""
        |ontology:
        |  - concept: SmallInt
        |    type: ValueType
        |    extends: [Integer]
        |    requires:
        |      - SmallInt >= 1
        """)).types("SmallInt")
      t.typeof shouldBe Some(Reference("integer"))
      t.minimumValue.map(_.value) shouldBe Some("1")
    }

    "base a value type on text when it extends nothing LinkML knows" in {
      importOf(ossie("""
        |ontology:
        |  - concept: Mystery
        |    type: ValueType
        """)).types("Mystery").typeof shouldBe Some(Reference("string"))
    }

    "read the last of the extends as is_a and the rest as mixins" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: Mixed
        |    type: EntityType
        |  - concept: Base
        |    type: EntityType
        |  - concept: Sub
        |    type: EntityType
        |    extends: [Mixed, Base]
        """)).classes("Sub")
      cls.isA shouldBe Some(Reference("Base"))
      cls.mixins shouldBe Seq(Reference("Mixed"))
    }

    "normalize a concept name, keeping the original spelling as the class' alias" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: HTTPThing
        |    type: EntityType
        """)).classes("HttpThing")
      cls.name shouldBe "HttpThing"
      cls.alias shouldBe Some("HTTPThing")
    }

    "leave the alias off a concept name that normalizing did not change" in {
      importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """)).classes("Thing").alias shouldBe None
    }

    "not give back a concept name that normalizing changed" in {
      val ontology = ossie("""
        |ontology:
        |  - concept: HTTPThing
        |    type: EntityType
        |  - concept: HTTPStatus
        |    type: ValueType
        |    extends: [String]
        |    requires:
        |      - HTTPStatus IN ('OK')
        """)
      importOf(ontology).enums.keys.toSeq shouldBe Seq("HttpStatus")
      val out = OssieGenerator(using reimport(ontology)).serialize()
      out should include("concept: HttpThing")
      out should include("concept: HttpStatus")
    }

    "refuse two concepts that differ only in naming style" in {
      val thrown = intercept[RuntimeException](importOf(ossie("""
        |ontology:
        |  - concept: HTTPThing
        |    type: EntityType
        |  - concept: HttpThing
        |    type: EntityType
        """)))
      thrown.getMessage should include("HttpThing")
    }
  }

  "relationships" should {
    "become attributes, with the built-in ranges mapped back" in {
      val attributes = importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        |    relationships:
        |      - name: s
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} s {String}']
        |      - name: n
        |        roles: [{concept: Integer}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} n {Integer}']
        |      - name: when
        |        roles: [{concept: DateTime}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} when {DateTime}']
        """)).classes("Thing").attributes
      attributes("s").range shouldBe Some(Reference("string"))
      attributes("n").range shouldBe Some(Reference("integer"))
      attributes("when").range shouldBe Some(Reference("datetime"))
    }

    "snake_case the slot name and keep the original as an alias" in {
      val slot = attributeOf(
        """
        |      - name: fullName
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} full name {String}']
        """,
        "full_name",
      )
      slot.alias shouldBe Some("fullName")
    }

    "leave the alias off when the name needs no changing" in {
      attributeOf(
        """
        |      - name: full_name
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} full name {String}']
        """,
        "full_name",
      ).alias shouldBe None
    }

    "rank the attributes in the order the relationships were written" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        |    relationships:
        |      - name: zzz
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} zzz {String}']
        |      - name: aaa
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} aaa {String}']
        """)).classes("Thing")
      cls.attributes.values.map(s => s.name -> s.rank).toSeq shouldBe
        Seq("zzz" -> Some(1), "aaa" -> Some(2))
    }

    "read the verbalization's phrase as the title" in {
      attributeOf(
        """
        |      - name: full_name
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} has official name {String}']
        """,
        "full_name",
      ).title shouldBe Some(PlainText("has official name"))
    }

    "leave the title off when the phrase is just the name" in {
      attributeOf(
        """
        |      - name: full_name
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} full name {String}']
        """,
        "full_name",
      ).title shouldBe None
    }

    "read the phrase of a self-referencing relationship, past the role name" in {
      attributeOf(
        """
        |      - name: parent_of
        |        roles: [{concept: Thing, name: parent_of}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} is the parent of {Thing:parent_of}']
        """,
        "parent_of",
      ).title shouldBe Some(PlainText("is the parent of"))
    }

    "read a missing multiplicity as multivalued" in {
      attributeOf(
        """
        |      - name: many
        |        roles: [{concept: String}]
        |        verbalizes: ['{Thing} many {String}']
        """,
        "many",
      ).multivalued shouldBe true
    }

    "read the concept's requires as the slots it makes mandatory" in {
      val attributes = importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        |    requires:
        |      - Thing.name
        |    relationships:
        |      - name: name
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} name {String}']
        |      - name: nickname
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} nickname {String}']
        """)).classes("Thing").attributes
      attributes("name").required shouldBe true
      attributes("nickname").required shouldBe false
    }

    "read the bounds and the pattern off a relationship's requires" in {
      val slot = attributeOf(
        """
        |      - name: n
        |        roles: [{concept: Integer}]
        |        multiplicity: ManyToOne
        |        requires:
        |          - Integer >= -1
        |          - Integer <= 10
        |          - REGEXP_LIKE(Integer, '^[0-9]+$')
        |        verbalizes: ['{Thing} n {Integer}']
        """,
        "n",
      )
      slot.minimumValue.map(_.value) shouldBe Some("-1")
      slot.maximumValue.map(_.value) shouldBe Some("10")
      slot.pattern shouldBe Some("^[0-9]+$")
    }

    "keep a bound that is not a number as the string it was" in {
      val slot = attributeOf(
        """
        |      - name: when
        |        roles: [{concept: Date}]
        |        multiplicity: ManyToOne
        |        requires:
        |          - Date >= '2020-01-01'
        |        verbalizes: ['{Thing} when {Date}']
        """,
        "when",
      )
      slot.minimumValue.flatMap(Literal.fromLinkml) shouldBe Some(Literal.Text("2020-01-01"))
    }

    "ignore an expression in a shape it did not write" in {
      val slot = attributeOf(
        """
        |      - name: n
        |        roles: [{concept: Integer}]
        |        multiplicity: ManyToOne
        |        requires:
        |          - Integer <> 3
        |          - IntegerX >= 5
        |          - COUNT(Integer) > 1
        |        verbalizes: ['{Thing} n {Integer}']
        """,
        "n",
      )
      slot.minimumValue shouldBe None
      slot.maximumValue shouldBe None
      slot.pattern shouldBe None
    }

    "read the constraints over a role's name when it has one" in {
      val slot = attributeOf(
        """
        |      - name: parent_of
        |        roles: [{concept: Thing, name: parent_of}]
        |        multiplicity: ManyToOne
        |        requires:
        |          - REGEXP_LIKE(parent_of, '^x')
        |        verbalizes: ['{Thing} parent of {Thing:parent_of}']
        """,
        "parent_of",
      )
      slot.pattern shouldBe Some("^x")
    }

    "point at the first role of a relationship Ossie allows but LinkML has no shape for" in {
      attributeOf(
        """
        |      - name: between
        |        roles: [{concept: String}, {concept: Integer}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} between {String} and {Integer}']
        """,
        "between",
      ).range shouldBe Some(Reference("string"))
    }

    "leave the range off a relationship with no roles at all" in {
      attributeOf(
        """
        |      - name: nothing
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} nothing']
        """,
        "nothing",
      ).range shouldBe None
    }

    "pass an unknown concept through, so that schema validation reports it" in {
      attributeOf(
        """
        |      - name: x
        |        roles: [{concept: NeverDeclared}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} x {NeverDeclared}']
        """,
        "x",
      ).range shouldBe Some(Reference("NeverDeclared"))
    }
  }

  "the Any concept" should {
    "become a linkml:Any class when something refers to it" in {
      val schema = importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        |    relationships:
        |      - name: whatever
        |        roles: [{concept: Any}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{Thing} whatever {Any}']
        """))
      schema.classes("Any").classUri.map(_.original) shouldBe Some("linkml:Any")
      schema.classes("Thing").attributes("whatever").range shouldBe Some(Reference("Any"))
    }

    "stay out of the schema when nothing refers to it" in {
      importOf(ossie("""
        |ontology:
        |  - concept: Thing
        |    type: EntityType
        """)).classes.keys should not contain "Any"
    }
  }

  "identifiers" should {
    "make a single one-to-one identify_by into an identifier slot" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: Person
        |    type: EntityType
        |    identify_by: [id]
        |    relationships:
        |      - name: id
        |        roles: [{concept: String}]
        |        multiplicity: OneToOne
        |        verbalizes: ['{Person} id {String}']
        """)).classes("Person")
      cls.attributes("id").identifier shouldBe true
      cls.uniqueKeys shouldBe empty
    }

    "make a compound identify_by into a unique key" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: OrderLine
        |    type: EntityType
        |    identify_by: [order, nr]
        |    relationships:
        |      - name: order
        |        roles: [{concept: String}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{OrderLine} order {String}']
        |      - name: nr
        |        roles: [{concept: Integer}]
        |        multiplicity: ManyToOne
        |        verbalizes: ['{OrderLine} nr {Integer}']
        """)).classes("OrderLine")
      cls.attributes.values.map(_.identifier) should contain only false
      cls.uniqueKeys.values.head.uniqueKeySlots shouldBe Seq(Reference("order"), Reference("nr"))
    }

    "fall back to a unique key when the identifying role is not a value" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: Passport
        |    type: EntityType
        |  - concept: Person
        |    type: EntityType
        |    identify_by: [passport]
        |    relationships:
        |      - name: passport
        |        roles: [{concept: Passport}]
        |        multiplicity: OneToOne
        |        verbalizes: ['{Person} passport {Passport}']
        """)).classes("Person")
      cls.attributes("passport").identifier shouldBe false
      cls.uniqueKeys.values.head.uniqueKeySlots shouldBe Seq(Reference("passport"))
    }

    "leave an inherited identifier to the supertype that declares it" in {
      val cls = importOf(ossie("""
        |ontology:
        |  - concept: Base
        |    type: EntityType
        |    identify_by: [id]
        |    relationships:
        |      - name: id
        |        roles: [{concept: String}]
        |        multiplicity: OneToOne
        |        verbalizes: ['{Base} id {String}']
        |  - concept: Sub
        |    type: EntityType
        |    extends: [Base]
        |    identify_by: [id]
        """)).classes("Sub")
      cls.attributes shouldBe empty
      cls.uniqueKeys shouldBe empty
    }
  }

  "an unreadable document" should {
    "say so rather than producing a broken schema" in {
      val thrown = intercept[RuntimeException](importOf("name: [unclosed"))
      thrown.getMessage should include("Ossie ontology")
    }
  }
}

object OssieImporterSpec {

  private def stream(ontology: String): InputStream =
    ByteArrayInputStream(ontology.getBytes(StandardCharsets.UTF_8))

  private def importOf(ontology: String): SchemaDefinitionImpl =
    OssieImporter().importSchemaFromString(ontology)

  /** A one-off Ossie document, with [[body]] spliced in after the pinned version and a name. */
  private def ossie(body: String): String =
    s"""version: ${OssieOntology.specVersion}
       |name: spec
       |${body.stripMargin}
       |""".stripMargin

  /** The attribute a single relationship on a lone concept turns into. */
  private def attributeOf(relationship: String, name: String): SlotDefinitionImpl =
    importOf(ossie(s"""
      |ontology:
      |  - concept: Thing
      |    type: EntityType
      |    relationships:${relationship.stripMargin}
      """)).classes("Thing").attributes(name)

  /** The SchemaView an ontology imports to, by way of the LinkML the importer serializes.
    *
    * We include LinkML ser/des on purpose, because Ossie uses the LinkML metamodel in weird ways
    * that the codec might not support.
    */
  private def reimport(ontology: String): SchemaView =
    SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(OssieImporter().serialize(stream(ontology))),
    )
}
