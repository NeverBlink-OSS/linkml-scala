package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.PruningMode.*
import eu.neverblink.linkml.generator.linkml.LinkMlGeneratorSpec.skipModels
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class LinkMlGeneratorSpec extends AnyWordSpec, Matchers {
  "LinkMlGenerator" should {
    "inline imports into the schema" in {
      val sv = ModelCatalogue.pruning.model
      val schema =
        LinkMlGenerator(using sv).generate(skipClassDerivation = true, pruningMode = skip)
      schema.classes.keys should contain theSameElementsAs Seq(
        "NotTreeRootClass",
        "SomeClass",
        "SomeOtherClass",
        "UnusedClass",
      )

      schema.types.keys should contain("uri")
    }

    "prune unused elements from the schema (tree_root)" in {
      val sv = ModelCatalogue.pruning.model
      val schema =
        LinkMlGenerator(using sv).generate(skipClassDerivation = true, pruningMode = treeRoot(None))
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
        "SomeOtherClass",
      )

      schema.types.keys should contain theSameElementsAs Seq(
        "string",
        "integer",
      )

      schema.slotDefinitions.keys should contain theSameElementsAs Seq(
        "some_slot",
        "some_other_slot",
      )
    }

    "prune unused elements from the schema (tree_root override)" in {
      val sv = ModelCatalogue.pruning.model
      val schema = LinkMlGenerator(using sv).generate(
        skipClassDerivation = true,
        pruningMode = treeRoot(Some("NotTreeRootClass")),
      )
      schema.classes.keys should contain theSameElementsAs Seq(
        "NotTreeRootClass",
      )

      schema.types.keys should contain theSameElementsAs Seq(
        "string",
      )

      schema.slotDefinitions.keys should contain theSameElementsAs Seq(
        "some_slot",
      )
    }

    "prune unused elements from the schema (root schema)" in {
      val sv = ModelCatalogue.pruning.model
      val schema =
        LinkMlGenerator(using sv).generate(skipClassDerivation = true, pruningMode = schemaRoot)
      schema.classes.keys should contain theSameElementsAs Seq(
        "NotTreeRootClass",
        "SomeClass",
        "SomeOtherClass",
      )

      schema.types.keys should contain theSameElementsAs Seq(
        "string",
        "integer",
      )
      schema.slotDefinitions.keys should contain theSameElementsAs Seq(
        "some_slot",
        "some_other_slot",
      )
    }

    "derive classes and clear relevant slots (skip pruning)" in {
      val sv = ModelCatalogue.pruning.model
      val schema = LinkMlGenerator(using sv).generate(pruningMode = skip)
      schema.classes.keys should contain theSameElementsAs Seq(
        "NotTreeRootClass",
        "SomeClass",
        "SomeOtherClass",
        "UnusedClass",
      )
      schema.slotDefinitions shouldBe empty
      val someClass = schema.classes("SomeClass")
      someClass.classUri should not be empty
      someClass.isA shouldBe empty
      someClass.slots shouldBe empty
      someClass.attributes.keys should contain theSameElementsAs Seq(
        "some_slot",
        "some_other_slot",
      )
    }

    "derive classes and prune the schema (tree_root)" in {
      val sv = ModelCatalogue.pruning.model
      val schema = LinkMlGenerator(using sv).generate(pruningMode = treeRoot(None))
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
      )
      schema.slotDefinitions shouldBe empty
      schema.types.keys should contain theSameElementsAs Seq("string", "integer")
      val someClass = schema.classes("SomeClass")
      someClass.classUri should not be empty
      someClass.isA shouldBe empty
      someClass.slots shouldBe empty
      someClass.attributes.keys should contain theSameElementsAs Seq(
        "some_slot",
        "some_other_slot",
      )
    }

    "derive classes and prune the schema (tree_root override)" in {
      val sv = ModelCatalogue.pruning.model
      val schema =
        LinkMlGenerator(using sv).generate(pruningMode = treeRoot(Some("NotTreeRootClass")))
      schema.classes.keys should contain theSameElementsAs Seq(
        "NotTreeRootClass",
      )
      schema.slotDefinitions shouldBe empty
      schema.types.keys should contain theSameElementsAs Seq("string")
      val cls = schema.classes("NotTreeRootClass")
      cls.classUri should not be empty
      cls.isA shouldBe empty
      cls.slots shouldBe empty
      cls.attributes.keys should contain theSameElementsAs Seq(
        "some_slot",
      )
    }

    "derive classes and prune the schema (schema root)" in {
      val sv = ModelCatalogue.pruning.model
      val schema =
        LinkMlGenerator(using sv).generate(pruningMode = schemaRoot)
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
        "NotTreeRootClass",
      )
      schema.slotDefinitions shouldBe empty
      schema.types.keys should contain theSameElementsAs Seq("string", "integer")
      val cls = schema.classes("NotTreeRootClass")
      cls.classUri should not be empty
      cls.isA shouldBe empty
      cls.slots shouldBe empty
      cls.attributes.keys should contain theSameElementsAs Seq(
        "some_slot",
      )
      val someClass = schema.classes("SomeClass")
      someClass.classUri should not be empty
      someClass.isA shouldBe empty
      someClass.slots shouldBe empty
      someClass.attributes.keys should contain theSameElementsAs Seq(
        "some_slot",
        "some_other_slot",
      )
    }

    "include inlines" in {
      val sv = ModelCatalogue.inlines.explicitInlineList.model
      val schema =
        LinkMlGenerator(using sv).generate()
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
        "SomeOtherClass",
      )
    }

    "include class references" in {
      val sv = ModelCatalogue.reference.model
      val schema =
        LinkMlGenerator(using sv).generate()
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
        "SomeOtherClass",
      )
    }

    "prune using schema mode if requested schema tree_root mode but no tree root" in {
      val sv = ModelCatalogue.treeRootless.model
      val schema =
        LinkMlGenerator(using sv).generate(pruningMode = treeRoot(None))
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
        "SomeOtherClass",
      )
      schema.types.keys should contain theSameElementsAs Seq(
        "string",
        "integer",
      )
    }

    "prune using tree_root override if no schema tree root" in {
      val sv = ModelCatalogue.treeRootless.model
      val schema =
        LinkMlGenerator(using sv).generate(pruningMode = treeRoot(Some("SomeClass")))
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
      )
    }

    "generate the metamodel without errors" in {
      val sv = SchemaView.loadSchemaViewFromUri("linkml:meta")
      SchemaView.single(
        LinkMlGenerator(using sv).generate(),
      ).lint() shouldBe empty

      SchemaView.single(
        LinkMlGenerator(using sv).generate(
          skipClassDerivation = true,
        ),
      ).lint() shouldBe empty
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all.filter(m => !skipModels.contains(m.model.root.name)) do
        s"model '${entry.model.root.name}'" in {
          LinkMlGenerator(using entry.model)
            .serialize() should not be empty
          LinkMlGenerator(using entry.model)
            .serialize(skipClassDerivation = true) should not be empty

          SchemaView.single(LinkMlGenerator(using entry.model).generate())
            .lint() shouldBe empty
          SchemaView.single(
            LinkMlGenerator(using entry.model).generate(skipClassDerivation = true),
          ).lint() shouldBe empty
        }
    }
  }
}

object LinkMlGeneratorSpec {
  val skipModels: Map[String, String] = Map(
    "unionRange" -> "Not yet implemented: LNK-110",
  )
}
