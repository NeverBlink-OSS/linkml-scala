package eu.neverblink.linkml.generator.linkml

import eu.neverblink.linkml.generator.options.{
  JsonOutputFormat,
  LinkMlGeneratorOptions,
  PruningMode,
}
import eu.neverblink.linkml.generator.linkml.LinkMlGeneratorSpec.skipModels
import eu.neverblink.linkml.schemaview.{MapImporter, SchemaIssues}
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import eu.neverblink.linkml.tests.ModelCatalogue.lintProblemsExceptMissingRecommendedSlots
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

class LinkMlGeneratorSpec extends AnyWordSpec, Matchers {
  "LinkMlGenerator" should {
    "derive type properties even when class derivation is skipped" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString("""
          |id: https://example.org/derived-types
          |name: derived_types
          |imports:
          |  - linkml:types
          |types:
          |  Code:
          |    typeof: string
          |    repr: str
          |    pattern: '^[A-Z]{3}$'
          |    description: Parent description.
          |  ProductCode:
          |    typeof: Code
          |    description: Product code.
          |""".stripMargin))

      Seq(false, true).foreach { skipClasses =>
        withClue(s"skipClassDerivation=$skipClasses: ") {
          val options = LinkMlGeneratorOptions(skipClassDerivation = skipClasses)
          val generated = LinkMlGenerator(using sv).generate(options)
          val child = generated.types("ProductCode")

          child.base shouldBe Some("str")
          child.repr shouldBe Some("str")
          child.pattern shouldBe Some("^[A-Z]{3}$")
          child.typeUri.map(_.original) shouldBe Some("http://www.w3.org/2001/XMLSchema#string")
          child.typeof.map(_.value) shouldBe Some("Code")
          child.description shouldBe sv.types("ProductCode").inner.description

          val yaml = LinkMlGenerator(using sv).serialize(options)
          val reloaded = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))
          val reloadedChild = reloaded.types("ProductCode").inner
          reloadedChild.base shouldBe child.base
          reloadedChild.repr shouldBe child.repr
          reloadedChild.pattern shouldBe child.pattern
          reloadedChild.typeUri shouldBe child.typeUri
          reloadedChild.typeof shouldBe child.typeof
          reloadedChild.description shouldBe child.description
        }
      }
    }

    "keep functions and the classes they use when pruning from the schema root" in {
      val other =
        """id: https://example.org/other
          |name: other
          |prefixes:
          |  linkml: https://w3id.org/linkml/
          |default_range: string
          |imports:
          |  - linkml:types
          |classes:
          |  Request: {}
          |  Result: {}
          |  Failure: {}
          |  Unused: {}
          |""".stripMargin
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri(
          "root",
          MapImporter(
            "root.yaml" ->
              """id: https://example.org/root
                |name: root
                |imports:
                |  - other
                |functions:
                |  run:
                |    parameters: Request
                |    returns: Result
                |    errors:
                |      - Failure
                |""".stripMargin,
            "other.yaml" -> other,
          ),
        ),
      )
      val generated =
        LinkMlGenerator(using sv).generate(LinkMlGeneratorOptions(pruningMode = PruningMode.Schema))
      generated.functions.keys should contain only "run"
      generated.classes.keys should contain theSameElementsAs Seq("Request", "Result", "Failure")
    }

    "drop functions that the tree root does not reach" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://example.org/tree-root-functions
          |name: tree_root_functions
          |prefixes:
          |  linkml: https://w3id.org/linkml/
          |default_range: string
          |imports:
          |  - linkml:types
          |classes:
          |  Root:
          |    tree_root: true
          |    attributes:
          |      a: {}
          |  Request: {}
          |functions:
          |  run:
          |    parameters: Request
          |""".stripMargin,
        ),
      )
      val generated =
        LinkMlGenerator(using sv).generate(LinkMlGeneratorOptions(pruningMode = PruningMode.TreeRoot))
      generated.functions shouldBe empty
      generated.classes.keys should contain only "Root"
    }

    "preserve type unions and their dependencies through pruning and reload" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString("""
          |id: https://example.org/type-unions
          |name: type_unions
          |imports:
          |  - linkml:types
          |types:
          |  Text:
          |    typeof: string
          |    pattern: '^[A-Z]{3}$'
          |  Count:
          |    typeof: integer
          |    minimum_value: 0
          |    maximum_value: 10
          |  Choice:
          |    union_of:
          |      - Text
          |      - Count
          |  ChoiceChild:
          |    typeof: Choice
          |  Unused:
          |    typeof: float
          |classes:
          |  Record:
          |    tree_root: true
          |    attributes:
          |      value:
          |        range: ChoiceChild
          |""".stripMargin))
      val options = LinkMlGeneratorOptions(pruningMode = PruningMode.TreeRoot)
      val generator = LinkMlGenerator(using sv)
      val generated = generator.generate(options)
      val reloaded = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(generator.serialize(options)),
      )

      Seq(generated, reloaded.root).foreach { schema =>
        schema.types.keys should contain theSameElementsAs
          Seq("Choice", "ChoiceChild", "Text", "Count", "string", "integer")
        schema.types("Choice").unionOf.map(_.value) shouldBe Seq("Text", "Count")
        schema.types("ChoiceChild").typeof.map(_.value) shouldBe Some("Choice")
        schema.types("Text").typeof.map(_.value) shouldBe Some("string")
        schema.types("Count").typeof.map(_.value) shouldBe Some("integer")
        schema.types("Choice").base shouldBe None
        schema.types("ChoiceChild").base shouldBe None
        schema.types("Text").pattern shouldBe Some("^[A-Z]{3}$")
        schema.types("Count").minimumValue.map(_.value.trim) shouldBe Some("0")
        schema.types("Count").maximumValue.map(_.value.trim) shouldBe Some("10")
      }
      lintProblemsExceptMissingRecommendedSlots(reloaded) shouldBe empty
    }

    "inline imports into the schema" in {
      val sv = ModelCatalogue.pruning.model
      val schema =
        LinkMlGenerator(using sv).generate(
          LinkMlGeneratorOptions(pruningMode = PruningMode.Skip, skipClassDerivation = true),
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
          LinkMlGeneratorOptions(pruningMode = PruningMode.TreeRoot, skipClassDerivation = true),
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
        LinkMlGeneratorOptions(
          pruningMode = PruningMode.TreeRoot,
          treeRoot = Some("NotTreeRootClass"),
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
          LinkMlGeneratorOptions(pruningMode = PruningMode.Schema, skipClassDerivation = true),
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
      val schema =
        LinkMlGenerator(using sv).generate(LinkMlGeneratorOptions(pruningMode = PruningMode.Skip))
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
        LinkMlGenerator(using sv).generate(
          LinkMlGeneratorOptions(pruningMode = PruningMode.TreeRoot),
        )
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
          LinkMlGeneratorOptions(
            pruningMode = PruningMode.TreeRoot,
            treeRoot = Some("NotTreeRootClass"),
          ),
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
        LinkMlGenerator(using sv).generate(LinkMlGeneratorOptions(pruningMode = PruningMode.Schema))
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
        LinkMlGenerator(using sv).generate(
          LinkMlGeneratorOptions(pruningMode = PruningMode.TreeRoot),
        )
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
          LinkMlGeneratorOptions(pruningMode = PruningMode.TreeRoot, treeRoot = Some("SomeClass")),
        )
      schema.classes.keys should contain theSameElementsAs Seq(
        "SomeClass",
      )
    }

    "include all default_ranges when pruning" in {
      val sv = ModelCatalogue.pruningDefaultRange.model
      val schema =
        LinkMlGenerator(using sv).generate(
          LinkMlGeneratorOptions(pruningMode = PruningMode.TreeRoot),
        )
      schema.types.keys should contain theSameElementsAs Seq(
        "integer",
        "string",
      )
    }

    "generate the metamodel without errors" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      lintProblemsExceptMissingRecommendedSlots(
        SchemaView.single(LinkMlGenerator(using sv).generate()),
      ) shouldBe empty

      lintProblemsExceptMissingRecommendedSlots(
        SchemaView.single(
          LinkMlGenerator(using sv).generate(
            LinkMlGeneratorOptions(skipClassDerivation = true),
          ),
        ),
      ) shouldBe empty
    }

    "serialize yaml format without errors" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      LinkMlGenerator(using sv).serialize(
        LinkMlGeneratorOptions(format = JsonOutputFormat.Yaml),
      ).isEmpty shouldBe false
    }

    "serialize json format without errors" in {
      val sv = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))
      LinkMlGenerator(using sv).serialize(
        LinkMlGeneratorOptions(format = JsonOutputFormat.Json),
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
        LinkMlGeneratorOptions(
          pruningMode = PruningMode.TreeRoot,
          format = JsonOutputFormat.Json,
        ),
      ) shouldBe
        """{
        |  "id": "https://d3fend.mitre.org/ontologies/d3fend.owl",
        |  "name": "d3fend",
        |  "default_range": "string",
        |  "classes": {
        |    "ZeroClientComputer": {
        |      "class_uri": "d3f:ZeroClientComputer",
        |      "from_schema": "https://d3fend.mitre.org/ontologies/d3fend.owl",
        |      "annotations": {
        |        "kb-article": "## How it works Change the default password as soon as a new device is received. The default credentials are normally documented in an instruction manual that is either packaged with the device, published online through official means, or published online through unofficial means. ## Considerations"
        |      }
        |    }
        |  },
        |  "types": {
        |    "string": {
        |      "uri": "xsd:string",
        |      "description": "A character string",
        |      "base": "str",
        |      "exact_mappings": [
        |        "http://schema.org/Text"
        |      ],
        |      "from_schema": "https://w3id.org/linkml/types",
        |      "notes": [
        |        "In RDF serializations, a slot with range of string is treated as a literal or type xsd:string. If you are authoring schemas in LinkML YAML, the type is referenced with the lower case \"string\"."
        |      ]
        |    }
        |  }
        |}""".stripMargin
    }

    "keep the meaning of a CURIE from an import that declares the same prefix differently" in {
      val other =
        """id: https://example.org/other
          |name: other
          |prefixes:
          |  ex: https://other.org/
          |classes:
          |  Imported:
          |    class_uri: ex:Imported
          |""".stripMargin
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://example.org/root
          |name: root
          |prefixes:
          |  ex: https://root.org/
          |imports:
          |  - other
          |classes:
          |  Local:
          |    class_uri: ex:Local
          |""".stripMargin,
          MapImporter("other" -> other),
        ),
      )
      sv.classes("Imported").uriStr shouldBe "https://other.org/Imported"
      val yaml =
        LinkMlGenerator(using sv).serialize(LinkMlGeneratorOptions(pruningMode = PruningMode.Skip))
      // `ex` of the output schema is the root's, so the imported URI can't be compacted with it
      yaml should include("class_uri: ex:Local\n")
      yaml should include("class_uri: https://other.org/Imported\n")
    }

    "fail on a URI with an undeclared prefix that the output declares" in {
      val other =
        """id: https://example.org/other
          |name: other
          |classes:
          |  Imported:
          |    class_uri: ex:Imported
          |""".stripMargin
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://example.org/root
          |name: root
          |prefixes:
          |  ex: https://root.org/
          |imports:
          |  - other
          |classes:
          |  Local:
          |    class_uri: ex:Local
          |""".stripMargin,
          MapImporter("other" -> other),
        ),
      )
      sv.classes("Imported").uriStr shouldBe "ex:Imported"
      val generator = LinkMlGenerator(using sv)
      // Reading `ex:Imported` back would expand it with the root's `ex`, even with full URIs
      Seq(
        LinkMlGeneratorOptions(pruningMode = PruningMode.Skip),
        LinkMlGeneratorOptions(fullUris = true),
      )
        .foreach { options =>
          (the[IllegalArgumentException] thrownBy generator.serialize(options)).getMessage should
            include("The URI 'ex:Imported' can't be written")
        }
    }

    "write URIs in full with the fullUris option" in {
      val sv = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromString(
          """id: https://example.org/root
          |name: root
          |prefixes:
          |  ex: https://example.org/
          |classes:
          |  Local:
          |    class_uri: ex:Local
          |""".stripMargin,
        ),
      )
      val generator = LinkMlGenerator(using sv)
      generator.serialize(LinkMlGeneratorOptions()) should include("class_uri: ex:Local\n")
      val full = generator.serialize(LinkMlGeneratorOptions(fullUris = true))
      full should include("class_uri: https://example.org/Local\n")
      // The prefixes themselves are kept
      full should include("ex: https://example.org/\n")
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
        LinkMlGeneratorOptions(
          pruningMode = PruningMode.TreeRoot,
          format = JsonOutputFormat.Json,
        ),
      ) shouldBe
        """{
        |  "id": "https://example.org/numeric-strings",
        |  "name": "numeric_strings",
        |  "title": "123",
        |  "description": "true",
        |  "license": "null",
        |  "classes": {
        |    "SomeClass": {
        |      "class_uri": "https://example.org/numeric-strings/SomeClass",
        |      "description": "3.14",
        |      "from_schema": "https://example.org/numeric-strings"
        |    }
        |  }
        |}""".stripMargin

      // The same nodes feed the YAML output, which must quote them for the same reason.
      val yaml =
        LinkMlGenerator(using sv).serialize(
          LinkMlGeneratorOptions(format = JsonOutputFormat.Yaml),
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
          LinkMlGeneratorOptions(),
          LinkMlGeneratorOptions(skipClassDerivation = true),
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
            .serialize(LinkMlGeneratorOptions(skipClassDerivation = true)) should not be empty

          lintProblemsExceptMissingRecommendedSlots(
            SchemaView.single(LinkMlGenerator(using entry.model).generate()),
          ) shouldBe empty
          lintProblemsExceptMissingRecommendedSlots(
            SchemaView.single(
              LinkMlGenerator(using entry.model).generate(
                LinkMlGeneratorOptions(skipClassDerivation = true),
              ),
            ),
          ) shouldBe empty
        }
    }
  }
}

object LinkMlGeneratorSpec {
  val skipModels: Map[String, String] = Map(
    "unionRange" -> "Not yet implemented: LNK-110",
  )
}
