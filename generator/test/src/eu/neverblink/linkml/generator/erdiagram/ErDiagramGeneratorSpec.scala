package eu.neverblink.linkml.generator.erdiagram

import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ErDiagramGeneratorSpec extends AnyWordSpec, Matchers {

  /** Load a self-contained schema, for the escaping cases the model catalogue does not cover. */
  private def schemaOf(classes: String): SchemaView =
    SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        s"""id: https://neverblink.eu/test/
           |name: test
           |default_range: string
           |types:
           |  string:
           |classes:
           |$classes
           |""".stripMargin,
      ),
    )

  "ErDiagramGenerator" should {
    "generate entities for classes" in {
      given SchemaView = ModelCatalogue.basic2.model

      val result = ErDiagramGenerator().serialize()
      result should startWith("%% GENERATED FROM LINKML\nerDiagram\n")
      Seq(
        "  SomeClass {",
        "    string? some_slot",
        "    integer some_other_slot",
        "  SomeOtherClass {",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate cardinality and optionality" in {
      given SchemaView = ModelCatalogue.cardinality.model

      val result = ErDiagramGenerator().serialize()
      Seq(
        "string one",
        "string? atMostOne",
        "string[] atLeastOne",
        "string[]? zeroOrMore",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "omit the optional marker when asked, for renderers older than Mermaid 11.16" in {
      given SchemaView = ModelCatalogue.cardinality.model

      val result =
        ErDiagramGenerator().serialize(ErDiagramGenerator.Options(optionalMarker = false))
      Seq(
        "string one",
        "string atMostOne",
        "string[] atLeastOne",
        "string[] zeroOrMore",
      ).foreach { snippet =>
        result should include(snippet)
      }
      result should not include "?"
    }

    "order attribute rows by rank, then by name" in {
      given SchemaView = schemaOf("""  Root:
                                    |    attributes:
                                    |      zulu:
                                    |        rank: 1
                                    |      alpha:
                                    |        rank: 2
                                    |      unranked_b:
                                    |      unranked_a:
                                    |""".stripMargin)

      // Ranked slots come first, in rank order. The rest follow, by name.
      ErDiagramGenerator().serialize() should include(
        """  Root {
          |    string? zulu
          |    string? alpha
          |    string? unranked_a
          |    string? unranked_b""".stripMargin,
      )
    }

    "mark identifiers as primary keys" in {
      given SchemaView = ModelCatalogue.reference.model

      ErDiagramGenerator().serialize() should include("string id PK")
    }

    "mark keys as unique keys" in {
      given SchemaView = ModelCatalogue.inlines.implicitInlineAsCompactDict.model

      ErDiagramGenerator().serialize() should include("string id UK")
    }

    "generate references as relationship lines rather than attributes" in {
      given SchemaView = ModelCatalogue.reference.model

      val result = ErDiagramGenerator().serialize()
      // A non-identifying line: the referenced class stands on its own.
      result should include("SomeClass ||..o| SomeOtherClass : \"some_slot\"")
      // The referencing slot is the line, so it must not also be a row of the entity.
      result should not include "SomeClass {"
    }

    "generate multivalued references as relationship lines" in {
      given SchemaView = ModelCatalogue.multivaluedReference.model

      val result = ErDiagramGenerator().serialize()
      result should include("SomeClass ||..o{ SomeOtherClass : \"some_slot\"")
      result should not include "SomeClass {"
    }

    "generate required references with an exactly-one cardinality" in {
      given SchemaView = ModelCatalogue.referenceInteger.model

      val result = ErDiagramGenerator().serialize()
      result should include("SomeClass ||..|| SomeOtherClass : \"some_slot\"")
      result should include("integer id PK")
    }

    "generate inlines as identifying relationship lines" in {
      given SchemaView = ModelCatalogue.inlines.explicitInline.model

      val result = ErDiagramGenerator().serialize()
      result should include("SomeClass ||--o| SomeOtherClass : \"some_slot\"")
      result should not include "SomeClass {"
    }

    "generate multivalued inlines as identifying relationship lines" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineList.model

      val result = ErDiagramGenerator().serialize()
      result should include("SomeClass ||--o{ SomeOtherClass : \"some_slot\"")
    }

    "generate required multivalued inlines with a one-or-more cardinality" in {
      given SchemaView = schemaOf("""  Root:
                                    |    attributes:
                                    |      children:
                                    |        range: Child
                                    |        required: true
                                    |        multivalued: true
                                    |  Child:
                                    |    attributes:
                                    |      x:
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("Root ||--|{ Child : \"children\"")
    }

    "generate required multivalued references with a one-or-more cardinality" in {
      given SchemaView = schemaOf("""  Root:
                                    |    attributes:
                                    |      children:
                                    |        range: Child
                                    |        required: true
                                    |        multivalued: true
                                    |  Child:
                                    |    attributes:
                                    |      id:
                                    |        identifier: true
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("Root ||..|{ Child : \"children\"")
    }

    "generate self-references" in {
      given SchemaView = schemaOf("""  Node:
                                    |    attributes:
                                    |      id:
                                    |        identifier: true
                                    |      parent:
                                    |        range: Node
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("Node ||..o| Node : \"parent\"")
    }

    "generate enums as attribute types" in {
      given SchemaView = ModelCatalogue.`enum`.model

      val result = ErDiagramGenerator().serialize()
      result should include("SomeEnum? some_slot")
      // Mermaid has no concept of an enum, so it must not become an entity of its own.
      result should not include "SomeEnum {"
    }

    "generate types as attribute types" in {
      given SchemaView = ModelCatalogue.typed.model

      val result = ErDiagramGenerator().serialize()
      Seq(
        "string stringSlot",
        "boolean booleanSlot",
        "integer intSlot",
        "decimal floatSlot",
        "date dateSlot",
        "custom customSlot",
      ).foreach { snippet =>
        result should include(snippet)
      }
    }

    "generate Any as an attribute type" in {
      given SchemaView = ModelCatalogue.anything.model

      val result = ErDiagramGenerator().serialize()
      result should include("Any some_slot")
      result should not include "Any {"
    }

    "generate attribute-less classes without a block" in {
      given SchemaView = ModelCatalogue.emptyClass.model

      val result = ErDiagramGenerator().serialize()
      result should include("\n  SomeOtherClass\n")
      result should not include "SomeOtherClass {"
    }

    "respect aliases" in {
      given SchemaView = ModelCatalogue.aliases.model

      val result = ErDiagramGenerator().serialize()
      result should include("AliasedClass {")
      result should include("aliased_slot")
    }

    "prune in tree_root mode if requested" in {
      given SchemaView = ModelCatalogue.pruning.model

      val result =
        ErDiagramGenerator().serialize(ErDiagramGenerator.Options(PruningMode.treeRoot(None)))
      result should include("SomeClass {")
      result should not include "NotTreeRootClass"
    }

    "prune in tree_root mode with override" in {
      given SchemaView = ModelCatalogue.pruning.model

      val result =
        ErDiagramGenerator()
          .serialize(ErDiagramGenerator.Options(PruningMode.treeRoot(Some("NotTreeRootClass"))))
      result should include("NotTreeRootClass {")
      result should not include "SomeClass {"
    }

    "quote entity names that collide with Mermaid keywords" in {
      // Mermaid's lexer is case-insensitive, so PascalCase does not make `one` safe.
      given SchemaView = schemaOf("""  one:
                                    |    attributes:
                                    |      x:
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("\"One\" {")
    }

    "quote entity names that cannot stand bare" in {
      given SchemaView = schemaOf("""  Root:
                                    |    alias: My Class
                                    |    attributes:
                                    |      x:
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("\"My Class\" {")
    }

    "replace characters that Mermaid cannot represent in a quoted entity name" in {
      // There is no escape mechanism anywhere in the grammar, so `"` cannot be kept.
      given SchemaView = schemaOf("""  Root:
                                    |    alias: say "hi"
                                    |    attributes:
                                    |      x:
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("\"say 'hi'\" {")
    }

    "replace characters that Mermaid cannot represent in an attribute name" in {
      given SchemaView = schemaOf("""  Root:
                                    |    attributes:
                                    |      some slot:
                                    |        alias: has "quotes"
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("string? has__quotes_")
    }

    "quote entity names starting with a digit, which Mermaid lexes as a number" in {
      given SchemaView = schemaOf("""  1class:
                                    |    attributes:
                                    |      x:
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("\"1Class\" {")
    }

    "keep a leading digit in an attribute name, which Mermaid allows only after the first character" in {
      given SchemaView = schemaOf("""  Root:
                                    |    attributes:
                                    |      1st slot:
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("string? _1_st_slot")
    }

    "avoid attribute names that Mermaid reads as key constraints" in {
      given SchemaView = schemaOf("""  Root:
                                    |    attributes:
                                    |      pk:
                                    |""".stripMargin)

      ErDiagramGenerator().serialize() should include("string? pk_")
    }

    "defuse relationship labels that Mermaid would read as a direction statement" in {
      // `direction` followed by whitespace and a direction keyword swallows the whole line, quotes
      // and all, and Mermaid reports no error for it.
      given SchemaView = schemaOf("""  Root:
                                    |    attributes:
                                    |      link:
                                    |        alias: direction LR
                                    |        range: Other
                                    |  Other:
                                    |    attributes:
                                    |      x:
                                    |""".stripMargin)

      val result = ErDiagramGenerator().serialize()
      result should include("\"direction_LR\"")
      result should not include "direction LR"
    }
  }
}
