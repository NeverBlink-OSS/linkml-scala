package eu.neverblink.linkml.generator.stats

import eu.neverblink.linkml.generator.stats.model.*
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, PruningMode as Pruning}
import eu.neverblink.linkml.schemaview.{MapImporter, SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StatsGeneratorSpec extends AnyWordSpec, Matchers {
  private val other =
    """id: https://example.org/other
      |name: other
      |imports:
      |  - linkml:types
      |classes:
      |  Imported:
      |    description: Imported.
      |  NotUsed:
      |    description: Not used.
      |enums:
      |  OtherEnum:
      |    permissible_values:
      |      A:
      |""".stripMargin

  private val root =
    """id: https://example.org/root
      |name: root
      |title:
      |  en: Root
      |  pl: Korzeń
      |license: https://creativecommons.org/publicdomain/zero/1.0/
      |version: 1.0.0
      |prefixes:
      |  linkml: https://w3id.org/linkml/
      |  ex: https://example.org/
      |  schema: http://schema.org/
      |default_prefix: ex
      |imports:
      |  - linkml:types
      |  - other
      |subsets:
      |  Core:
      |    description: Core elements.
      |  Spare:
      |classes:
      |  Book:
      |    description: A book.
      |    exact_mappings: [schema:Book]
      |    in_subset: [Core]
      |    slots: [name, status]
      |    attributes:
      |      pages:
      |        range: integer
      |        deprecated: Use page_count.
      |  Chapter:
      |    tree_root: true
      |    is_a: Imported
      |    notes:
      |      - A note.
      |slots:
      |  name:
      |    title: Name
      |    related_mappings: [schema:name]
      |  status:
      |    range: Status
      |  unused_slot:
      |    description: Nobody uses this.
      |enums:
      |  Status:
      |    permissible_values:
      |      DRAFT:
      |        meaning: ex:Draft
      |        description:
      |          en: Draft
      |          de: Entwurf
      |      DONE:
      |        mappings: [ex:Done]
      |functions:
      |  write:
      |    description: Writes a book.
      |    parameters: Book
      |    returns: Chapter
      |  tidy:
      |""".stripMargin

  private given SchemaView =
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(root, MapImporter("other" -> other)))

  private def stats(mode: Pruning = Pruning.schemaRoot): SchemaStats =
    StatsGenerator().generate(StatsGenerator.Options(pruningMode = mode))

  "StatsGenerator" should {
    "count what the root schema defines and what it uses, by default" in {
      val e = stats().elements
      e.classes.count shouldBe 3 // Book, Chapter and its parent Imported
      e.slots.count shouldBe 3 // name, status, and unused_slot as the root schema defines it
      e.attributes.count shouldBe 1
      e.enums.count shouldBe 1
      e.permissibleValues.count shouldBe 2
      e.types.count shouldBe 2 // integer and the default range, string
      e.subsets.count shouldBe 2
      e.functions.count shouldBe 2
    }

    "count the functions of every catalogue model" in {
      ModelCatalogue.all.foreach { entry =>
        withClue(s"${entry.name}: ") {
          val counted = StatsGenerator(using entry.model).generate().elements.functions
          counted.count shouldBe entry.model.root.functions.size
          counted.withDescription shouldBe
            entry.model.root.functions.values.count(_.description.isDefined)
        }
      }
    }

    "count every element without pruning" in {
      val e = stats(Pruning.skip).elements
      e.classes.count shouldBe 4
      e.slots.count shouldBe 3
      e.enums.count shouldBe 2
      e.permissibleValues.count shouldBe 3
      e.types.count should be > 2
      e.functions.count shouldBe 2
    }

    "count the elements the tree root uses" in {
      val s = stats(Pruning.treeRoot(None))
      s.pruningMode shouldBe PruningMode.TreeRoot
      s.elements.classes.count shouldBe 2
      s.elements.slots.count shouldBe 0
      // Nothing reaches a function from a tree root class
      s.elements.functions.count shouldBe 0
    }

    "count documentation, deprecation and mappings" in {
      val e = stats().elements
      e.classes.withDescription shouldBe 2
      e.classes.withMappings shouldBe 1
      e.classes.mappings.exact shouldBe 1
      e.slots.withTitle shouldBe 1
      e.slots.mappings.related shouldBe 1
      e.attributes.deprecated shouldBe 1
      e.permissibleValues.withMeaning shouldBe Some(1)
      e.permissibleValues.withMappings shouldBe 1
      e.permissibleValues.mappings.generic shouldBe 1
      e.classes.withMeaning shouldBe None
      e.functions.withDescription shouldBe 1
    }

    "count the strings in each language of the elements in scope" in {
      def counts(s: SchemaStats) = s.languages.view.mapValues(_.count).toSeq
      // 8 in the schemas here, and a description and a note each on `string` and `integer`
      counts(stats()) shouldBe Seq("" -> 12, "de" -> 1, "en" -> 2, "pl" -> 1)
      val all = counts(stats(Pruning.skip))
      all.tail shouldBe Seq("de" -> 1, "en" -> 2, "pl" -> 1)
      all.head._2 should be > 13 // also `NotUsed` and every type
    }

    "count each imported schema once, and describe the root schema" in {
      val s = stats()
      s.imports shouldBe ImportStats(direct = 2, transitive = 2)
      s.metadata shouldBe MetadataStats(
        hasDescription = false,
        hasLicense = true,
        hasTitle = true,
        hasVersion = true,
      )
      s.schemaName shouldBe "root"
      s.pruningMode shouldBe PruningMode.Schema
    }

    "write the languages as a map from tag to count" in {
      val generator = StatsGenerator()
      val yaml = generator.serialize()
      yaml should include("languages:\n  \"\": 12\n  de: 1\n")
      val json = generator.serialize(StatsGenerator.Options(), JsonOutputFormat.json)
      json should include(""""languages": {
                             |    "": 12,""".stripMargin)
    }
  }
}
