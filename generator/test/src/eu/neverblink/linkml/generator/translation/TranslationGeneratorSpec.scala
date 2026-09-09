package eu.neverblink.linkml.generator.translation

import eu.neverblink.linkml.generator.translation.TranslationGenerator.Options
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TranslationGeneratorSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  "TranslationGenerator" should {
    "translate syntheticUris" when {
      val sv = ModelCatalogue.syntheticUris.model
      val gen = TranslationGenerator(using sv)
      "target is base" in {
        val snippets =
          """"łączony (class)": "czony_class"
            |"łączony <typ>": "czony_typ"
            |"łączony 'enum'": "czony_enum"
            |"łączony [slot]": "czony_slot"
            |"inny łączony \"slot\"": "inny_czony_slot"
            |"łączony {value}": "czony_value"
            |"inny łączony \\value//": "inny_czony_value"
            |""".stripMargin.strip()
            .linesIterator.toSeq

        val result = gen.serialize(Options("base"))

        snippets.foreach { snippet =>
          result should include(snippet)
        }
      }

      "target is URI" in {
        val snippets =
          """"łączony (class)": "https://neverblink.eu/linkml/tests/syntheticUris/CzonyClass"
            |"łączony <typ>": "https://neverblink.eu/linkml/tests/syntheticUris/czony_typ"
            |"łączony 'enum'": "https://neverblink.eu/linkml/tests/syntheticUris/CzonyEnum"
            |"łączony [slot]": "https://neverblink.eu/linkml/tests/syntheticUris/czony_slot"
            |"inny łączony \"slot\"": "https://neverblink.eu/linkml/tests/syntheticUris/inny_czony_slot"
            |"łączony {value}": "https://neverblink.eu/linkml/tests/syntheticUris/CzonyEnum.CZONY_VALUE"
            |"inny łączony \\value//": "https://neverblink.eu/linkml/tests/syntheticUris/CzonyEnum.INNY_CZONY_VALUE"
            |""".stripMargin.strip()
            .linesIterator.toSeq

        val result = gen.serialize(Options("URI"))

        snippets.foreach { snippet =>
          result should include(snippet)
        }
      }

      "target is Scala" in {
        val snippets =
          """"łączony (class)": "CzonyClass"
            |"łączony <typ>": "CzonyTyp"
            |"łączony 'enum'": "CzonyEnum"
            |"łączony [slot]": "czonySlot"
            |"inny łączony \"slot\"": "innyCzonySlot"
            |"łączony {value}": "CzonyValue"
            |"inny łączony \\value//": "InnyCzonyValue"
            |""".stripMargin.strip()
            .linesIterator.toSeq

        val result = gen.serialize(Options("scala"))

        snippets.foreach { snippet =>
          result should include(snippet)
        }
      }

      "target is GraphQL" in {
        val snippets =
          """"łączony (class)": "CzonyClass"
            |"łączony <typ>": "CzonyTyp"
            |"łączony 'enum'": "CzonyEnum"
            |"łączony [slot]": "czony_slot"
            |"inny łączony \"slot\"": "inny_czony_slot"
            |"łączony {value}": "CZONY_VALUE"
            |"inny łączony \\value//": "INNY_CZONY_VALUE"
            |""".stripMargin.strip()
            .linesIterator.toSeq

        val result = gen.serialize(Options("graphql"))

        snippets.foreach { snippet =>
          result should include(snippet)
        }
      }
    }
  }

  "generate all catalogue models without errors" when {
    for entry <- ModelCatalogue.all do
      s"model is '${entry.model.root.name}'" when {
        for target <- Seq("base", "URI", "Scala", "GraphQL") do
          s"target is $target" in {
            val result = TranslationGenerator(using entry.model).generate(
              TranslationGenerator.Options(target),
            )
            if entry.model.classes.nonEmpty then result.classes should not be empty
            if entry.model.types.nonEmpty then result.types should not be empty
            if entry.model.enums.nonEmpty then result.enums should not be empty
            if entry.model.slotDefinitions.nonEmpty then result.slots should not be empty
          }
      }
  }
}
