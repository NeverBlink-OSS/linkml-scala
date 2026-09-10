package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.util.PruningMode.*
import eu.neverblink.linkml.generator.util.JsonOutputFormat
import eu.neverblink.linkml.generator.linkml.LinkMlGeneratorSpec.skipModels
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import eu.neverblink.linkml.validation.NonStandardSeparatorImpl
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class LinkMlGeneratorSpec extends AnyWordSpec, Matchers {
  "LinkMlGenerator" should {
    "inline imports into the schema" in {
      val sv = ModelCatalogue.pruning.model
      val schema =
        LinkMlGenerator(using sv).generate(
          LinkMlGenerator.Options(pruningMode = skip, skipClassDerivation = true),
        )
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
        LinkMlGenerator(using sv).generate(
          LinkMlGenerator.Options(pruningMode = treeRoot(None), skipClassDerivation = true),
        )
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
        LinkMlGenerator.Options(
          pruningMode = treeRoot(Some("NotTreeRootClass")),
          skipClassDerivation = true,
        ),
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
        LinkMlGenerator(using sv).generate(
          LinkMlGenerator.Options(pruningMode = schemaRoot, skipClassDerivation = true),
        )
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
      val schema = LinkMlGenerator(using sv).generate(LinkMlGenerator.Options(pruningMode = skip))
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
      val schema =
        LinkMlGenerator(using sv).generate(LinkMlGenerator.Options(pruningMode = treeRoot(None)))
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
        LinkMlGenerator(using sv).generate(
          LinkMlGenerator.Options(pruningMode = treeRoot(Some("NotTreeRootClass"))),
        )
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
        LinkMlGenerator(using sv).generate(LinkMlGenerator.Options(pruningMode = schemaRoot))
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

    "prune using schema mode if requested tree_root mode but no tree root" in {
      val sv = ModelCatalogue.treeRootless.model
      val schema =
        LinkMlGenerator(using sv).generate(LinkMlGenerator.Options(pruningMode = treeRoot(None)))
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
        LinkMlGenerator(using sv).generate(
          LinkMlGenerator.Options(pruningMode = treeRoot(Some("SomeClass"))),
        )
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
      )
    }

    "include all default_ranges when pruning" in {
      val sv = ModelCatalogue.pruningDefaultRange.model
      val schema =
        LinkMlGenerator(using sv).generate(
          LinkMlGenerator.Options(pruningMode = treeRoot(None)),
        )
      schema.types.keys should contain theSameElementsAs Seq(
        "integer",
        "string",
      )
    }

    "generate the metamodel without errors" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      SchemaView.single(
        LinkMlGenerator(using sv).generate(),
        // TODO LNK-207: fix the metamodel
      ).lintProblems.filter(!_.isInstanceOf[NonStandardSeparatorImpl]) shouldBe empty

      SchemaView.single(
        LinkMlGenerator(using sv).generate(
          LinkMlGenerator.Options(skipClassDerivation = true),
        ),
        // TODO LNK-207: fix the metamodel
      ).lintProblems.filter(!_.isInstanceOf[NonStandardSeparatorImpl]) shouldBe empty
    }

    "serialize yaml format without errors" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      LinkMlGenerator(using sv).serialize(
        LinkMlGenerator.Options(outputFormat = JsonOutputFormat.yaml),
      ).isEmpty shouldBe false
    }

    "serialize json format without errors" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      LinkMlGenerator(using sv).serialize(
        LinkMlGenerator.Options(outputFormat = JsonOutputFormat.json),
      ).isEmpty shouldBe false
    }

    "serialize strings that require double quoting in yaml using the json format without conversion them to null values" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString("""name: d3fend
          |id: https://d3fend.mitre.org/ontologies/d3fend.owl
          |imports:
          |  linkml:types
          |default_range: string
          |classes:
          |  ZeroClientComputer:
          |    class_uri: d3f:ZeroClientComputer
          |    annotations:
          |      kb-article: "## How it works Change the default password as soon as a new device is received. The default credentials are normally documented in an instruction manual that is either packaged with the device, published online through official means, or published online through unofficial means. ## Considerations"
          |""".stripMargin))
      LinkMlGenerator(using sv).serialize(
        LinkMlGenerator.Options(
          pruningMode = treeRoot(None),
          outputFormat = JsonOutputFormat.json,
        ),
      ) shouldBe
        """{
        |  "id": "https://d3fend.mitre.org/ontologies/d3fend.owl",
        |  "name": "d3fend",
        |  "classes": {
        |    "ZeroClientComputer": {
        |      "class_uri": "d3f:ZeroClientComputer",
        |      "annotations": {
        |        "kb-article": "## How it works Change the default password as soon as a new device is received. The default credentials are normally documented in an instruction manual that is either packaged with the device, published online through official means, or published online through unofficial means. ## Considerations"
        |      },
        |      "from_schema": "https://d3fend.mitre.org/ontologies/d3fend.owl"
        |    }
        |  },
        |  "types": {
        |    "string": {
        |      "uri": "xsd:string",
        |      "description": "A character string",
        |      "base": "str",
        |      "exact_mappings": [
        |        "schema:Text"
        |      ],
        |      "from_schema": "https://w3id.org/linkml/types",
        |      "notes": [
        |        "In RDF serializations, a slot with range of string is treated as a literal or type xsd:string. If you are authoring schemas in LinkML YAML, the type is referenced with the lower case \"string\"."
        |      ]
        |    }
        |  },
        |  "default_range": "string"
        |}""".stripMargin
    }

    "serialize string values that look like numbers, booleans or nulls as strings" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString("""name: numeric_strings
          |id: https://example.org/numeric-strings
          |title: "123"
          |description: "true"
          |license: "null"
          |classes:
          |  SomeClass:
          |    description: "3.14"
          |""".stripMargin))

      LinkMlGenerator(using sv).serialize(
        LinkMlGenerator.Options(
          pruningMode = treeRoot(None),
          outputFormat = JsonOutputFormat.json,
        ),
      ) shouldBe
        """{
        |  "id": "https://example.org/numeric-strings",
        |  "name": "numeric_strings",
        |  "classes": {
        |    "SomeClass": {
        |      "class_uri": "https://example.org/numeric-strings/SomeClass",
        |      "description": "3.14",
        |      "from_schema": "https://example.org/numeric-strings"
        |    }
        |  },
        |  "title": "123",
        |  "description": "true",
        |  "license": "null"
        |}""".stripMargin

      // The same nodes feed the YAML output, which must quote them for the same reason.
      val yaml =
        LinkMlGenerator(using sv).serialize(
          LinkMlGenerator.Options(outputFormat = JsonOutputFormat.yaml),
        )
      yaml should include("""title: "123"""")
      yaml should include("""description: "true"""")
      yaml should include("""license: "null"""")
      yaml should include("""description: "3.14"""")
    }

    "round-trip unique_keys" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString("""name: unique_keys
          |id: https://example.org/unique-keys
          |types:
          |  string:
          |    base: str
          |classes:
          |  SomeClass:
          |    slots:
          |      - a
          |      - b
          |    unique_keys:
          |      compound:
          |        unique_key_slots:
          |          - a
          |          - b
          |      collapsed_list:
          |        - a
          |      collapsed_scalar: b
          |slots:
          |  a:
          |    range: string
          |  b:
          |    range: string
          |""".stripMargin))

      // Derivation turns the slots into attributes.
      // The macro validator must be aware of the class context (attributes) to load such
      // a schema, so we test it here.
      for options <- Seq(
          LinkMlGenerator.Options(),
          LinkMlGenerator.Options(skipClassDerivation = true),
        )
      do {
        val yaml = LinkMlGenerator(using sv).serialize(options)
        // The two collapsed input forms normalize to the full form as well
        yaml should include("""    unique_keys:
            |      compound:
            |        unique_key_slots:
            |          - a
            |          - b
            |      collapsed_list:
            |        unique_key_slots:
            |          - a
            |      collapsed_scalar:
            |        unique_key_slots:
            |          - b
            |""".stripMargin)

        val reloaded = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))
        LinkMlGenerator(using reloaded).serialize(options) shouldBe yaml
      }
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all.filter(m => !skipModels.contains(m.model.root.name)) do
        s"model '${entry.model.root.name}'" in {
          LinkMlGenerator(using entry.model)
            .serialize() should not be empty
          LinkMlGenerator(using entry.model)
            .serialize(LinkMlGenerator.Options(skipClassDerivation = true)) should not be empty

          SchemaView.single(LinkMlGenerator(using entry.model).generate())
            .lint() shouldBe empty
          SchemaView.single(
            LinkMlGenerator(using entry.model).generate(
              LinkMlGenerator.Options(skipClassDerivation = true),
            ),
          ).lint() shouldBe empty
        }
    }
  }
}

object LinkMlGeneratorSpec {
  val skipModels: Map[String, String] = Map(
    "unionRange" -> "Not yet implemented: LNK-110",
    "equals_expression" -> "Warning in lint - non-standard separator for PV: LNK-208",
  )
}
