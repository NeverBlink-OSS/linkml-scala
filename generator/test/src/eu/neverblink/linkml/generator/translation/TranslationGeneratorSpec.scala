package eu.neverblink.linkml.generator.translation

import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator.Options
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
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

      "target is Ossie" in {
        val snippets =
          """"łączony (class)": "CzonyClass"
            |"łączony <typ>": "CzonyTyp"
            |"łączony 'enum'": "CzonyEnum"
            |"łączony [slot]": "czony_slot"
            |"inny łączony \"slot\"": "inny_czony_slot"
            |"łączony {value}": "łączony {value}"
            |"inny łączony \\value//": "inny łączony \\value//"
            |""".stripMargin.strip()
            .linesIterator.toSeq

        val result = gen.serialize(Options("ossie"))

        snippets.foreach { snippet =>
          result should include(snippet)
        }
      }
    }

    "translate ER diagram names without aliases" in {
      val result = TranslationGenerator(using ModelCatalogue.aliases.model)
        .generate(Options("erdiagram"))

      result.classes("SomeOtherClass") shouldBe "SomeOtherClass"
      result.classAttributes("SomeOtherClass") shouldBe Map(
        "some_slot" -> "some_slot",
        "some_other_slot" -> "some_other_slot",
      )
    }

    "list a class's own attributes by default, and every slot it has with derivedAttributes" in {
      val gen = TranslationGenerator(using slotSources)
      gen.generate(Options("graphql")).classAttributes("Child") shouldBe Map(
        "own value" -> "own_value",
      )
      gen.generate(Options("graphql", derivedAttributes = true)).classAttributes("Child") shouldBe
        Map(
          "own value" -> "own_value",
          "shared-slot" -> "shared_slot",
          "2nd value" -> "_2_nd_value",
          "baseValue" -> "base_value",
          "mixedIn" -> "mixed_in",
        )
    }

    "name class attributes the way the GraphQL generator names the fields" in {
      val sdl = GraphQlGenerator(using slotSources).serialize()
      // A field name is the text before `:` or `(` on a line of the schema. Compare whole names,
      // because `2_nd_value` is a substring of `_2_nd_value`.
      val sdlFields = sdl.linesIterator
        .map(line => line.takeWhile(c => c != ':' && c != '(').trim)
        .toSet
      val translation =
        TranslationGenerator(using slotSources).generate(
          Options("graphql", derivedAttributes = true),
        )
      for (cls, fields) <- translation.classAttributes; (slot, field) <- fields do
        withClue(s"$cls.$slot -> $field:\n$sdl") {
          sdlFields should contain(field)
        }
      translation.classAttributes("Base")("2nd value") shouldBe "_2_nd_value"
    }

    "name class attributes the way the Frictionless generator names the fields" in {
      val sv = slotSources
      val translation =
        TranslationGenerator(using sv).generate(Options("frictionless", derivedAttributes = true))
      for cls <- Seq("Base", "Child") do
        val table = FrictionlessGenerator(using sv)
          .tableSchema(sv.classes(cls))(using FrictionlessGenerator.Options())
        translation.classAttributes(cls).values.toSet shouldBe table.fields.map(_.name).toSet
      translation.classAttributes("Child")("shared-slot") shouldBe "shared-slot"
      translation.classAttributes("Child")("baseValue") shouldBe "base"
    }
  }

  "name every slot of a class as its renamer names the class's own view of the slot" when {
    val models: Seq[(String, () => SchemaView)] =
      ModelCatalogue.all.map(entry => entry.model.root.name -> (() => entry.model)) :+
        ("slotSources" -> (() => slotSources))
    for (modelName, model) <- models do
      s"model is '$modelName'" in {
        given sv: SchemaView = model()
        for
          target <- TranslationGenerator.targets
          renamer = TranslationGenerator.resolveRenames(target)
          cls <- sv.classes.values
          (name, slot) <- cls.derivedAttributes
        do
          withClue(s"$target, ${cls.name}.$name: ") {
            renamer.classAttributeName(cls, slot.slot) shouldBe renamer.slotName(slot)
          }
      }
  }

  "generate all catalogue models without errors" when {
    for entry <- ModelCatalogue.all do
      s"model is '${entry.model.root.name}'" when {
        for target <- TranslationGenerator.targets do
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

  /** A class that gets slots from its own attributes, its `slots` list, a parent class and a mixin.
    * The slot names are ones the renamers change.
    */
  private lazy val slotSources: SchemaView = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromString(
      """id: https://example.org/slotSources
        |name: slotSources
        |prefixes:
        |  linkml: https://w3id.org/linkml/
        |imports:
        |  - linkml:types
        |default_range: string
        |
        |slots:
        |  shared-slot:
        |
        |classes:
        |  Base:
        |    attributes:
        |      baseValue:
        |        alias: base
        |      2nd value:
        |  Mixin:
        |    mixin: true
        |    attributes:
        |      mixedIn:
        |  Child:
        |    is_a: Base
        |    mixins: [Mixin]
        |    slots: [shared-slot]
        |    attributes:
        |      own value:
        |""".stripMargin,
    ),
  )
}
