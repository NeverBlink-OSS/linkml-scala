package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

/** Feature-level tests for the LinkML -> Ossie mapping, one behavior at a time. */
class OssieGeneratorSpec extends AnyWordSpec, Matchers {
  import OssieGeneratorSpec.*

  "concepts" should {
    "make an EntityType out of a class" in {
      val c = concept(
        ontologyOf("""
          |classes:
          |  Book:
          |    description: A book.
          |    attributes:
          |      title: {}
          """),
        "Book",
      )
      c.conceptType shouldBe ConceptType.EntityType
      c.description shouldBe Some("A book.")
    }

    "make a ValueType out of an enum, constrained to its permissible values" in {
      val c = concept(
        ontologyOf("""
          |enums:
          |  Status:
          |    permissible_values:
          |      ALIVE: {}
          |      DEAD: {}
          |classes:
          |  Person:
          |    attributes:
          |      status:
          |        range: Status
          """),
        "Status",
      )
      c.conceptType shouldBe ConceptType.ValueType
      c.extendsConcepts shouldBe Seq(BuiltInConcept.string)
      c.requires shouldBe Seq("Status IN ('ALIVE', 'DEAD')")
    }

    "quote an enum value that contains an apostrophe" in {
      concept(
        ontologyOf("""
          |enums:
          |  Kind:
          |    permissible_values:
          |      "it's": {}
          |classes:
          |  Thing:
          |    attributes:
          |      kind:
          |        range: Kind
          """),
        "Kind",
      ).requires shouldBe Seq("Kind IN ('it''s')")
    }

    "make a ValueType out of a named type, extending the built-in for its base" in {
      concept(
        ontologyOf("""
          |types:
          |  SmallInt:
          |    base: int
          |    uri: xsd:integer
          |classes:
          |  Thing:
          |    attributes:
          |      n:
          |        range: SmallInt
          """),
        "SmallInt",
      ).extendsConcepts shouldBe Seq(BuiltInConcept.integer)
    }

    "follow a typeof chain to find the base of a named type" in {
      val o = ontologyOf("""
        |types:
        |  PositiveInt:
        |    typeof: integer
        |    minimum_value: 1
        |classes:
        |  Thing:
        |    attributes:
        |      n:
        |        range: PositiveInt
        """)
      concept(o, "PositiveInt").extendsConcepts shouldBe Seq(BuiltInConcept.integer)
      role(o, "Thing", "n").concept shouldBe "PositiveInt"
    }

    "PascalCase a concept name" in {
      concept(
        ontologyOf("""
        |classes:
        |  order_line:
        |    attributes:
        |      x: {}
        """),
        "OrderLine",
      ).concept shouldBe "OrderLine"
    }

    "refuse to emit an ontology with no concepts at all" in {
      val thrown = intercept[RuntimeException](ontologyOf("""
        |slots:
        |  name: {}
        """))
      thrown.getMessage should include("at least one")
    }
  }

  "ranges" should {
    "map the primitive types onto Ossie's built-in concepts" in {
      val o = ontologyOf("""
        |classes:
        |  Thing:
        |    attributes:
        |      s: { range: string }
        |      i: { range: integer }
        |      f: { range: float }
        |      d: { range: double }
        |      dec: { range: decimal }
        |      b: { range: boolean }
        |      dt: { range: date }
        |      dtt: { range: datetime }
        |      t: { range: time }
        |      u: { range: uri }
        |      c: { range: curie }
        """)
      def rangeOf(slot: String): String = role(o, "Thing", slot).concept
      rangeOf("s") shouldBe "String"
      rangeOf("i") shouldBe "Integer"
      rangeOf("f") shouldBe "Float"
      rangeOf("d") shouldBe "Float"
      rangeOf("dec") shouldBe "Decimal"
      rangeOf("b") shouldBe "Boolean"
      rangeOf("dt") shouldBe "Date"
      rangeOf("dtt") shouldBe "DateTime"
      rangeOf("t") shouldBe "String"
      rangeOf("u") shouldBe "String"
      rangeOf("c") shouldBe "String"
    }

    "map linkml:Any onto the Any entity type" in {
      role(
        ontologyOf("""
          |classes:
          |  Any:
          |    class_uri: linkml:Any
          |  Thing:
          |    attributes:
          |      whatever:
          |        range: Any
          """),
        "Thing",
        "whatever",
      ).concept shouldBe BuiltInConcept.any
    }

    "fall back to Any for a named type whose base is not one we know" in {
      val o = ontologyOf("""
        |types:
        |  Weird:
        |    base: NotABaseWeKnow
        |    uri: xsd:string
        |classes:
        |  Thing:
        |    attributes:
        |      x:
        |        range: Weird
        """)
      role(o, "Thing", "x").concept shouldBe BuiltInConcept.any
      o.ontology.map(_.concept) should not contain "Weird"
    }

    "point a class-ranged slot at the target concept, inlined or not" in {
      val o = ontologyOf("""
        |classes:
        |  Author:
        |    attributes:
        |      id:
        |        identifier: true
        |  Book:
        |    attributes:
        |      by:
        |        range: Author
        |      inline_by:
        |        range: Author
        |        inlined: true
        """)
      role(o, "Book", "by").concept shouldBe "Author"
      role(o, "Book", "inline_by").concept shouldBe "Author"
    }
  }

  "relationships" should {
    "use the slot's title for the verbalization if it has one" in {
      relationship(
        ontologyOf("""
          |classes:
          |  Person:
          |    attributes:
          |      full_name:
          |        title: has official name
          """),
        "Person",
        "full_name",
      ).verbalizes shouldBe Seq("{Person} has official name {String}")
    }

    "verbalize as the space-cased name between the two concepts" in {
      relationship(
        ontologyOf("""
          |classes:
          |  Person:
          |    attributes:
          |      full_name: {}
          """),
        "Person",
        "full_name",
      ).verbalizes shouldBe Seq("{Person} full name {String}")
    }

    "use a slot's alias as the name but still space-case the verbalization" in {
      val r = relationship(
        ontologyOf("""
          |classes:
          |  Person:
          |    attributes:
          |      full_name:
          |        alias: fullName
          """),
        "Person",
        "fullName",
      )
      r.name shouldBe "fullName"
      r.verbalizes shouldBe Seq("{Person} full name {String}")
    }

    "name the role and disambiguate the verbalization when a slot points at its own class" in {
      val r = relationship(
        ontologyOf("""
          |classes:
          |  Person:
          |    attributes:
          |      id:
          |        identifier: true
          |      parent_of:
          |        range: Person
          |        multivalued: true
          """),
        "Person",
        "parent_of",
      )
      r.roles shouldBe Seq(Role("Person", Some("parent_of")))
      r.verbalizes shouldBe Seq("{Person} parent of {Person:parent_of}")
    }

    "leave multiplicity off a multivalued slot and set ManyToOne on a single-valued one" in {
      val o = ontologyOf("""
        |classes:
        |  Person:
        |    attributes:
        |      one: {}
        |      many:
        |        multivalued: true
        """)
      relationship(o, "Person", "one").multiplicity shouldBe Some(Multiplicity.ManyToOne)
      relationship(o, "Person", "many").multiplicity shouldBe None
    }

    "list required slots as concept-level requires" in {
      concept(
        ontologyOf("""
          |classes:
          |  Person:
          |    attributes:
          |      name:
          |        required: true
          |      nickname: {}
          """),
        "Person",
      ).requires shouldBe Seq("Person.name")
    }
  }

  "constraints" should {
    "put a slot's own bounds on the relationship, as expressions over the role" in {
      relationship(
        ontologyOf("""
          |classes:
          |  Thing:
          |    attributes:
          |      n:
          |        range: integer
          |        minimum_value: -1
          |        maximum_value: 10
          """),
        "Thing",
        "n",
      ).requires shouldBe Seq("Integer >= -1", "Integer <= 10")
    }

    "turn a pattern into a REGEXP_LIKE call over the role" in {
      relationship(
        ontologyOf("""
          |classes:
          |  Thing:
          |    attributes:
          |      code:
          |        pattern: '^[A-Z]{3}$'
          """),
        "Thing",
        "code",
      ).requires shouldBe Seq("REGEXP_LIKE(String, '^[A-Z]{3}$')")
    }

    "double an apostrophe inside a pattern rather than ending the literal early" in {
      relationship(
        ontologyOf("""
          |classes:
          |  Thing:
          |    attributes:
          |      code:
          |        pattern: "it's"
          """),
        "Thing",
        "code",
      ).requires shouldBe Seq("REGEXP_LIKE(String, 'it''s')")
    }

    "quote a bound that is not a number, and leave a number bare" in {
      val o = ontologyOf("""
        |classes:
        |  Thing:
        |    attributes:
        |      when:
        |        range: date
        |        minimum_value: 2020-01-01
        |      n:
        |        range: integer
        |        minimum_value: 5
        """)
      relationship(o, "Thing", "when").requires shouldBe Seq("Date >= '2020-01-01'")
      relationship(o, "Thing", "n").requires shouldBe Seq("Integer >= 5")
    }

    "see through the in the original YAML, so a quoted number still goes in bare" in {
      relationship(
        ontologyOf("""
          |classes:
          |  Thing:
          |    attributes:
          |      n:
          |        range: integer
          |        minimum_value: "5"
          """),
        "Thing",
        "n",
      ).requires shouldBe Seq("Integer >= 5")
    }

    "keep a named type's own constraints on the value type, not on every relationship" in {
      val o = ontologyOf("""
        |types:
        |  SmallInt:
        |    base: int
        |    uri: xsd:integer
        |    minimum_value: 1
        |classes:
        |  Thing:
        |    attributes:
        |      n:
        |        range: SmallInt
        """)
      concept(o, "SmallInt").requires shouldBe Seq("SmallInt >= 1")
      relationship(o, "Thing", "n").requires shouldBe empty
    }
  }

  "inheritance" should {
    "extend is_a and mixins alike" in {
      concept(
        ontologyOf("""
          |classes:
          |  Base:
          |    attributes:
          |      a: {}
          |  Mixed:
          |    mixin: true
          |    attributes:
          |      b: {}
          |  Sub:
          |    is_a: Base
          |    mixins: [Mixed]
          |    attributes:
          |      c: {}
          """),
        "Sub",
      ).extendsConcepts should contain theSameElementsAs Seq("Mixed", "Base")
    }

    "leave a relationship off a subtype that inherits it unchanged" in {
      names(
        ontologyOf("""
          |classes:
          |  Base:
          |    attributes:
          |      a: {}
          |  Sub:
          |    is_a: Base
          |    attributes:
          |      b: {}
          """),
        "Sub",
      ) shouldBe Seq("b")
    }

    "keep a relationship the subtype narrows" in {
      val o = ontologyOf("""
        |classes:
        |  Cat:
        |    attributes:
        |      id:
        |        identifier: true
        |  Base:
        |    attributes:
        |      pet: {}
        |  Sub:
        |    is_a: Base
        |    slot_usage:
        |      pet:
        |        range: Cat
        """)
      role(o, "Sub", "pet").concept shouldBe "Cat"
      role(o, "Base", "pet").concept shouldBe "String"
    }

    "keep a relationship the subtype only makes required, so the requires survives" in {
      val o = ontologyOf("""
        |classes:
        |  Base:
        |    attributes:
        |      name: {}
        |  Sub:
        |    is_a: Base
        |    slot_usage:
        |      name:
        |        required: true
        """)
      names(o, "Sub") shouldBe Seq("name")
      concept(o, "Sub").requires shouldBe Seq("Sub.name")
      concept(o, "Base").requires shouldBe empty
    }
  }

  "identifiers" should {
    "identify by an identifier slot, one-to-one" in {
      val o = ontologyOf("""
        |classes:
        |  Person:
        |    attributes:
        |      id:
        |        identifier: true
        """)
      concept(o, "Person").identifyBy shouldBe Seq("id")
      relationship(o, "Person", "id").multiplicity shouldBe Some(Multiplicity.OneToOne)
    }

    "identify by a key slot too" in {
      val o = ontologyOf("""
        |classes:
        |  Person:
        |    attributes:
        |      code:
        |        key: true
        """)
      concept(o, "Person").identifyBy shouldBe Seq("code")
      relationship(o, "Person", "code").multiplicity shouldBe Some(Multiplicity.OneToOne)
    }

    "identify by a compound unique_key, but leave its slots many-to-one" in {
      val o = ontologyOf("""
        |slots:
        |  order: {}
        |  nr:
        |    range: integer
        |classes:
        |  OrderLine:
        |    slots: [order, nr]
        |    unique_keys:
        |      line:
        |        unique_key_slots: [order, nr]
        """)
      concept(o, "OrderLine").identifyBy shouldBe Seq("order", "nr")
      // Only the tuple is unique - neither slot identifies a line on its own.
      relationship(o, "OrderLine", "order").multiplicity shouldBe Some(Multiplicity.ManyToOne)
      relationship(o, "OrderLine", "nr").multiplicity shouldBe Some(Multiplicity.ManyToOne)
    }
  }

  "the document" should {
    "include the schema name, description and the pinned spec version" in {
      val o = ontologyOf("""
        |description: A demo schema.
        |classes:
        |  Thing:
        |    attributes:
        |      x: {}
        """)
      o.version shouldBe OssieOntology.specVersion
      o.name shouldBe "spec"
      o.description shouldBe Some("A demo schema.")
    }

    "take descriptions in the language the caller asked for" in {
      val sv = schemaOf("""
        |classes:
        |  Book:
        |    description:
        |      en: A book.
        |      pl: Książka.
        |    attributes:
        |      title:
        |        description:
        |          en: Its title.
        |          pl: Jej tytuł.
        """)
      def descriptions(language: String): (Option[String], Option[String]) = {
        val o = OssieGenerator(using sv).generate(
          OssieGenerator.Options(metadataLanguage = language),
        )
        val c = concept(o, "Book")
        (c.description, c.relationships.head.description)
      }
      descriptions("en") shouldBe (Some("A book."), Some("Its title."))
      descriptions("pl") shouldBe (Some("Książka."), Some("Jej tytuł."))
      // A language the schema does not carry leaves the description out rather than guessing.
      descriptions("de") shouldBe (None, None)
    }

    "pass an object-valued ai_context extension through" in {
      yamlOf("""
        |extensions:
        |  ai_context:
        |    value:
        |      instructions: Prefer the full name.
        |classes:
        |  Thing:
        |    attributes:
        |      x: {}
        """) should include("instructions: Prefer the full name.")
    }

    "leave ai_context out when the schema declares no such extension" in {
      ontologyOf("""
        |classes:
        |  Thing:
        |    attributes:
        |      x: {}
        """).aiContext shouldBe None
    }

    "drop the classes that pruning puts out of reach" in {
      val sv = schemaOf("""
        |classes:
        |  Root:
        |    tree_root: true
        |    attributes:
        |      x: {}
        |  Orphan:
        |    attributes:
        |      y: {}
        """)
      def concepts(mode: PruningMode): Seq[String] =
        OssieGenerator(using sv).generate(OssieGenerator.Options(pruningMode = mode))
          .ontology.map(_.concept)

      concepts(PruningMode.skip) should contain("Orphan")
      concepts(PruningMode.treeRoot(None)) should not contain "Orphan"
    }

    "read back into the model it was written from" in {
      val sv = schemaOf("""
        |enums:
        |  Status:
        |    permissible_values:
        |      OK: {}
        |classes:
        |  Person:
        |    attributes:
        |      id:
        |        identifier: true
        |      status:
        |        range: Status
        |      friend:
        |        range: Person
        |        multivalued: true
        """)
      val generator = OssieGenerator(using sv)
      val written = generator.serialize()
      OssieOntology.codec.decode(parseYaml(written).toOption.get) shouldBe generator.generate()
    }
  }
}

object OssieGeneratorSpec {

  /** A SchemaView over a minimal schema with [[body]] spliced in. */
  private def schemaOf(body: String): SchemaView = {
    val schema =
      s"""id: https://example.org/spec
         |name: spec
         |prefixes:
         |  linkml: https://w3id.org/linkml/
         |  xsd: http://www.w3.org/2001/XMLSchema#
         |  ex: https://example.org/
         |default_prefix: ex
         |default_range: string
         |imports:
         |  - linkml:types
         |${body.stripMargin}
         |""".stripMargin
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schema))
  }

  private def ontologyOf(body: String): OssieOntology =
    OssieGenerator(using schemaOf(body)).generate()

  private def yamlOf(body: String): String =
    OssieGenerator(using schemaOf(body)).serialize()

  private def concept(o: OssieOntology, name: String): Concept =
    o.ontology.find(_.concept == name).getOrElse(
      throw AssertionError(s"no concept '$name' in ${o.ontology.map(_.concept)}"),
    )

  private def relationship(o: OssieOntology, conceptName: String, name: String): Relationship = {
    val c = concept(o, conceptName)
    c.relationships.find(_.name == name).getOrElse(
      throw AssertionError(s"no relationship '$name' on '$conceptName' in ${names(o, conceptName)}"),
    )
  }

  /** The single role of a relationship, which is the only shape this generator emits. */
  private def role(o: OssieOntology, conceptName: String, name: String): Role =
    relationship(o, conceptName, name).roles match {
      case Seq(only) => only
      case other => throw AssertionError(s"expected exactly one role, got $other")
    }

  private def names(o: OssieOntology, conceptName: String): Seq[String] =
    concept(o, conceptName).relationships.map(_.name)
}
