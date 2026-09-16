package eu.neverblink.linkml.generator.erdiagram

import eu.neverblink.linkml.schemaview.{Case, SchemaIssues, SchemaView}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ErDiagramRenamerSpec extends AnyWordSpec, Matchers {

  private val schema = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromString(
      """id: https://example.org/er-renamer/
        |name: er_renamer
        |default_range: string
        |types:
        |  string:
        |classes:
        |  Person:
        |    alias: Human
        |    attributes:
        |      full_name:
        |        alias: display_name
        |  1class:
        |  42:
        |  4.2:
        |""".stripMargin,
    ),
  )

  "ErDiagramRenamer" should {
    "ignore class and slot aliases" in {
      val person = schema.classes("Person")
      val slot = person.derivedAttributes("full_name")

      ErDiagramRenamer.className(person) shouldBe "Person"
      ErDiagramRenamer.slotName(slot) shouldBe "full_name"
      ErDiagramRenamer.classAttributeName(person, slot.slot) shouldBe "full_name"
    }

    "preserve distinct leading-digit class names" in {
      for (sourceName, expectedName) <- Seq(
          "1class" -> "\"1Class\"",
          "42" -> "\"42\"",
          "4.2" -> "\"4_2\"",
        )
      do {
        val cls = schema.classes(sourceName)
        val actual = ErDiagramRenamer.className(cls)

        actual shouldBe expectedName
        Case.base(actual) shouldBe cls.baseName
      }
    }
  }
}
