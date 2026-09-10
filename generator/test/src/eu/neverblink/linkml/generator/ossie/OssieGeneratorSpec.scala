package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.ossie.OssieCases.*
import eu.neverblink.linkml.generator.util.PruningMode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

/** Feature-level tests for the LinkML -> Ossie mapping, one behavior at a time.
  *
  * The schemas live in [[OssieCases]], which `OssieImporterSpec` reads back in the other direction.
  */
class OssieGeneratorSpec extends AnyWordSpec, Matchers, OssieFixtures {

  "concepts" should {
    "make an EntityType out of a class" in {
      val c = concept(ontologyOf(classWithDescription), "Book")
      c.conceptType shouldBe ConceptType.EntityType
      c.description shouldBe Some("A book.")
    }

    "make a ValueType out of an enum, constrained to its permissible values" in {
      val c = concept(ontologyOf(enumeration), "Status")
      c.conceptType shouldBe ConceptType.ValueType
      c.extendsConcepts shouldBe Seq(BuiltInConcept.string)
      c.requires shouldBe Seq("Status IN ('ALIVE', 'DEAD')")
    }

    "quote an enum value that contains an apostrophe" in {
      concept(ontologyOf(enumValueWithApostrophe), "Kind").requires shouldBe
        Seq("Kind IN ('it''s')")
    }

    "make a ValueType out of a named type, extending the built-in for its base" in {
      concept(ontologyOf(namedType), "SmallInt").extendsConcepts shouldBe Seq(
        BuiltInConcept.integer,
      )
    }

    "follow a typeof chain to find the base of a named type" in {
      val o = ontologyOf(typeofChain)
      concept(o, "PositiveInt").extendsConcepts shouldBe Seq(BuiltInConcept.integer)
      role(o, "Thing", "n").concept shouldBe "PositiveInt"
    }

    "PascalCase a concept name" in {
      concept(ontologyOf(snakeCasedClassName), "OrderLine").concept shouldBe "OrderLine"
    }

    "ignore a class' alias" in {
      val o = ontologyOf(aliasedClass)
      o.ontology.map(_.concept) shouldBe Seq("HttpThing")
      relationship(o, "HttpThing", "x").verbalizes shouldBe Seq("{HttpThing} x {String}")
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
      val o = ontologyOf(primitiveRanges)
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
      role(ontologyOf(anyRange), "Thing", "whatever").concept shouldBe BuiltInConcept.any
    }

    "fall back to Any for a named type whose base is not one we know" in {
      val o = ontologyOf(unknownBase)
      role(o, "Thing", "x").concept shouldBe BuiltInConcept.any
      o.ontology.map(_.concept) should not contain "Weird"
    }

    "point a class-ranged slot at the target concept, inlined or not" in {
      val o = ontologyOf(classRanges)
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
      relationship(ontologyOf(multiWordSlot), "Person", "full_name").verbalizes shouldBe
        Seq("{Person} full name {String}")
    }

    "use a slot's alias as the name but still space-case the verbalization" in {
      val r = relationship(ontologyOf(aliasedSlot), "Person", "fullName")
      r.name shouldBe "fullName"
      r.verbalizes shouldBe Seq("{Person} full name {String}")
    }

    "name the role and disambiguate the verbalization when a slot points at its own class" in {
      val r = relationship(ontologyOf(selfReference), "Person", "parent_of")
      r.roles shouldBe Seq(Role("Person", Some("parent_of")))
      r.verbalizes shouldBe Seq("{Person} parent of {Person:parent_of}")
    }

    "leave multiplicity off a multivalued slot and set ManyToOne on a single-valued one" in {
      val o = ontologyOf(multivalued)
      relationship(o, "Person", "one").multiplicity shouldBe Some(Multiplicity.ManyToOne)
      relationship(o, "Person", "many").multiplicity shouldBe None
    }

    "list required slots as concept-level requires" in {
      concept(ontologyOf(requiredSlot), "Person").requires shouldBe Seq("Person.name")
    }
  }

  "constraints" should {
    "put a slot's own bounds on the relationship, as expressions over the role" in {
      relationship(ontologyOf(numericBounds), "Thing", "n").requires shouldBe
        Seq("Integer >= -1", "Integer <= 10")
    }

    "turn a pattern into a REGEXP_LIKE call over the role" in {
      relationship(ontologyOf(pattern), "Thing", "code").requires shouldBe
        Seq("REGEXP_LIKE(String, '^[A-Z]{3}$')")
    }

    "double an apostrophe inside a pattern rather than ending the literal early" in {
      relationship(ontologyOf(patternWithApostrophe), "Thing", "code").requires shouldBe
        Seq("REGEXP_LIKE(String, 'it''s')")
    }

    "quote a bound that is not a number, and leave a number bare" in {
      val o = ontologyOf(nonNumericBound)
      relationship(o, "Thing", "when").requires shouldBe Seq("Date >= '2020-01-01'")
      relationship(o, "Thing", "n").requires shouldBe Seq("Integer >= 5")
    }

    "see through the quotes in the original YAML, so a quoted number still goes in bare" in {
      relationship(ontologyOf(quotedNumberBound), "Thing", "n").requires shouldBe
        Seq("Integer >= 5")
    }

    "keep a named type's own constraints on the value type, not on every relationship" in {
      val o = ontologyOf(typeConstraints)
      concept(o, "SmallInt").requires shouldBe Seq("SmallInt >= 1")
      relationship(o, "Thing", "n").requires shouldBe empty
    }
  }

  "inheritance" should {
    "extend is_a and mixins alike" in {
      concept(ontologyOf(inheritance), "Sub").extendsConcepts should contain theSameElementsAs
        Seq("Mixed", "Base")
    }

    "leave a relationship off a subtype that inherits it unchanged" in {
      names(ontologyOf(inheritedUnchanged), "Sub") shouldBe Seq("b")
    }

    "keep a relationship the subtype narrows" in {
      val o = ontologyOf(narrowedRange)
      role(o, "Sub", "pet").concept shouldBe "Cat"
      role(o, "Base", "pet").concept shouldBe "String"
    }

    "keep a relationship the subtype only makes required, so the requires survives" in {
      val o = ontologyOf(narrowedRequired)
      names(o, "Sub") shouldBe Seq("name")
      concept(o, "Sub").requires shouldBe Seq("Sub.name")
      concept(o, "Base").requires shouldBe empty
    }
  }

  "identifiers" should {
    "identify by an identifier slot, one-to-one" in {
      val o = ontologyOf(identifierSlot)
      concept(o, "Person").identifyBy shouldBe Seq("id")
      relationship(o, "Person", "id").multiplicity shouldBe Some(Multiplicity.OneToOne)
    }

    "identify by a key slot too" in {
      val o = ontologyOf(keySlot)
      concept(o, "Person").identifyBy shouldBe Seq("code")
      relationship(o, "Person", "code").multiplicity shouldBe Some(Multiplicity.OneToOne)
    }

    "identify by a compound unique_key, but leave its slots many-to-one" in {
      val o = ontologyOf(compoundKey)
      concept(o, "OrderLine").identifyBy shouldBe Seq("order", "nr")
      // Only the tuple is unique - neither slot identifies a line on its own.
      relationship(o, "OrderLine", "order").multiplicity shouldBe Some(Multiplicity.ManyToOne)
      relationship(o, "OrderLine", "nr").multiplicity shouldBe Some(Multiplicity.ManyToOne)
    }
  }

  "the document" should {
    "include the schema name, description and the pinned spec version" in {
      val o = ontologyOf(describedSchema)
      o.version shouldBe OssieOntology.specVersion
      o.name shouldBe "spec"
      o.description shouldBe Some("A demo schema.")
    }

    "take descriptions in the language the caller asked for" in {
      val sv = schemaOf(multilingualDescriptions)
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
      yamlOf(aiContextObject) should include("instructions: Prefer the full name.")
    }

    "leave ai_context out when the schema declares no such extension" in {
      ontologyOf(plain).aiContext shouldBe None
    }

    "drop the classes that pruning puts out of reach" in {
      val sv = schemaOf(treeRoot)
      def concepts(mode: PruningMode): Seq[String] =
        OssieGenerator(using sv).generate(OssieGenerator.Options(pruningMode = mode))
          .ontology.map(_.concept)

      concepts(PruningMode.skip) should contain("Orphan")
      concepts(PruningMode.treeRoot(None)) should not contain "Orphan"
    }

    "read back into the model it was written from" in {
      val generator = OssieGenerator(using schemaOf(severalKinds))
      val written = generator.serialize()
      OssieOntology.codec.decode(parseYaml(written).toOption.get) shouldBe generator.generate()
    }
  }
}
