package eu.neverblink.linkml.generator.jsonschema

import eu.neverblink.linkml.metamodel.Codec
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml
import sttp.apispec.{Schema, SchemaType}

class JsonSchemaGeneratorSpec extends AnyWordSpec, Matchers {
  import JsonSchemaGeneratorSpec.skipModels
  def load(schemaYaml: String): SchemaView = {
    val schema = Codec.codec.decode(
      parseYaml(schemaYaml) match {
        case Left(e) => fail(e)
        case Right(value) => value
      },
    )
    SchemaView.single(schema)
  }

  "JsonSchemaGenerator" should {
    // Shared part of the schema
    val schemaShared =
      """id: https://neverblink.eu/linkml/jsonschema/test/
        |name: test
        |types:
        |  string:
        |  integer:
        |  boolean:
        |"""

    "create typed top level classes" in {
      given SchemaView = ModelCatalogue.basic.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.String))
      c.required should not contain "some_slot"

      val someOtherSlot = c.properties("some_other_slot").asInstanceOf[Schema]
      someOtherSlot.`type` shouldBe Some(List(SchemaType.Integer))
      c.required should contain("some_other_slot")
    }

    "work without tree_root set" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    required: true
           |    range: string
           |""".stripMargin

      given SchemaView = load(input)
      val schema = JsonSchemaGenerator().generate()
      schema.$defs.get.size shouldBe 1
      schema.properties.size shouldBe 0
    }

    "ignore unreachable classes in $defs" in {
      given sv: SchemaView = ModelCatalogue.basic2.model
      val schema = JsonSchemaGenerator().generate()

      sv.classes.contains("SomeOtherClass") shouldBe true
      schema.$defs.get.keys should contain theSameElementsAs Seq("SomeClass")
      val someClass = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      someClass.properties.keys should contain theSameElementsAs Seq("some_slot", "some_other_slot")

      val someSlot = someClass.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.String))
      someClass.required should not contain "some_slot"

      val someOtherSlot = someClass.properties("some_other_slot").asInstanceOf[Schema]
      someOtherSlot.`type` shouldBe Some(List(SchemaType.Integer))
      someClass.required should contain("some_other_slot")
    }

    "emit all classes in $defs if tree_root is not defined" in {
      // Same test case as previous, but without tree_root defined.
      val input =
        s"""$schemaShared
           |id: https://neverblink.eu/linkml/tests/basic2/
           |name: basic2
           |
           |imports:
           |  - linkml:types
           |
           |classes:
           |  SomeOtherClass:
           |    slots:
           |      - some_slot
           |  SomeClass:
           |    tree_root: false
           |    slots:
           |      - some_slot
           |      - some_other_slot
           |slots:
           |  some_slot:
           |    range: string
           |  some_other_slot:
           |    range: integer
           |    required: true
           |""".stripMargin
      given SchemaView = load(input)
      val schema = JsonSchemaGenerator().generate()

      schema.$defs.get.keys should contain theSameElementsAs Seq("SomeClass", "SomeOtherClass")
      val someClass = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      someClass.properties.keys should contain theSameElementsAs Seq("some_slot", "some_other_slot")

      val someSlot = someClass.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.String))
      someClass.required should not contain "some_slot"

      val someOtherSlot = someClass.properties("some_other_slot").asInstanceOf[Schema]
      someOtherSlot.`type` shouldBe Some(List(SchemaType.Integer))
      someClass.required should contain("some_other_slot")

      val someOtherClass = schema.$defs.get("SomeOtherClass").asInstanceOf[Schema]
      someOtherClass.properties.keys should contain theSameElementsAs Seq("some_slot")
      someOtherClass.required shouldBe empty
    }

    "map references to strings" in {
      given SchemaView = ModelCatalogue.reference.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.String))
    }

    "map multivalued references to arrays of strings" in {
      given SchemaView = ModelCatalogue.multivaluedReference.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      c.required shouldBe empty
      someSlot.`type` shouldBe Some(List(SchemaType.Array))
      someSlot.items.get.asInstanceOf[Schema]
        .`type` shouldBe Some(List(SchemaType.String))
    }

    "handle required references" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    attributes:
           |      id:
           |        identifier: true
           |  SomeClass:
           |    tree_root: true
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    range: SomeOtherClass
           |    required: true
           |""".stripMargin

      given SchemaView = load(input)

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      c.required should contain("some_slot")
    }

    "implicitly inline identifier-less classes" in {
      given SchemaView = ModelCatalogue.inlines.implicitInline.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.$ref shouldBe Some("#/$defs/SomeOtherClass")
      schema.$defs.get.keys.toSeq should contain("SomeOtherClass")
      schema.$defs.get("SomeOtherClass").asInstanceOf[Schema]
        .`type`.get should contain(SchemaType.Object)
    }

    "implicitly inline multivalued other classes (implicitly as compact dict)" in {
      given SchemaView = ModelCatalogue.inlines.implicitInlineAsCompactDict.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Object))
      someSlot.additionalProperties.get.asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass__identifier_optional")
      schema.$defs.get.keys.toSeq should contain("SomeOtherClass__identifier_optional")
      val keyless = schema.$defs.get("SomeOtherClass__identifier_optional").asInstanceOf[Schema]
      keyless.`type` shouldBe Some(List(SchemaType.Object))
      keyless.properties.keys should contain("id")
      keyless.required should not contain "id"
    }

    "implicitly inline multivalued other classes (implicitly as simple dict)" in {
      given SchemaView = ModelCatalogue.inlines.implicitInlineAsSimpleDict.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Object))
      someSlot.additionalProperties.get.asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass__simple_dict_value")
      schema.$defs.get.keys.toSeq should contain("SomeOtherClass__simple_dict_value")
      val value = schema.$defs.get("SomeOtherClass__simple_dict_value").asInstanceOf[Schema]
      value.`type` shouldBe Some(List(SchemaType.String))
    }

    "implicitly inline multivalued other classes (implicitly as list)" in {
      given SchemaView = ModelCatalogue.inlines.implicitInlineAsList.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Array))
      someSlot.items.get.asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass")
    }

    "explicitly inline multivalued other classes (implicitly as compact dict)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineImplicitlyAsCompactDict.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Object))
      someSlot.additionalProperties.get.asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass__identifier_optional")
      schema.$defs.get.keys.toSeq should contain("SomeOtherClass__identifier_optional")
      val keyless = schema.$defs.get("SomeOtherClass__identifier_optional").asInstanceOf[Schema]
      keyless.`type` shouldBe Some(List(SchemaType.Object))
      keyless.properties.keys should contain("id")
      keyless.required should not contain "id"
    }

    "explicitly inline multivalued other classes (implicitly as simple dict)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineImplicitlyAsSimpleDict.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Object))
      someSlot.additionalProperties.get.asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass__simple_dict_value")
      schema.$defs.get.keys.toSeq should contain("SomeOtherClass__simple_dict_value")
      val value = schema.$defs.get("SomeOtherClass__simple_dict_value").asInstanceOf[Schema]
      value.`type` shouldBe Some(List(SchemaType.String))
    }

    "explicitly inline multivalued other classes (implicitly as list)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineImplicitlyAsList.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Array))
      someSlot.items.get.asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass")
    }

    "explicitly inline multivalued other classes (explicitly as list)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineList.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Array))
      someSlot.items.get.asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass")
    }

    "alias slots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    alias: aliased_slot
           |    required: true
           |    range: string
           |""".stripMargin

      given SchemaView = load(input)

      val schema = JsonSchemaGenerator().generate()

      schema.$defs.get("SomeClass").asInstanceOf[Schema]
        .properties.keys should contain theSameElementsAs Seq("aliased_slot")
    }

    "remove spaces from slot names" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    slots:
           |    - "some slot"
           |slots:
           |  "some slot":
           |    required: true
           |    range: string
           |""".stripMargin

      given SchemaView = load(input)

      val schema = JsonSchemaGenerator().generate()

      schema.$defs.get("SomeClass").asInstanceOf[Schema]
        .properties.keys should contain theSameElementsAs Seq("some_slot")
    }

    "alias class names" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    alias: OtherAliasedClass
           |  SomeClass:
           |    tree_root: true
           |    alias: SomeAliasedClass
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    range: SomeOtherClass
           |    inlined: true
           |""".stripMargin
      given SchemaView = load(input)

      val schema = JsonSchemaGenerator().generate()

      schema.$defs.get.keys should contain theSameElementsAs Seq(
        "SomeAliasedClass",
        "OtherAliasedClass",
      )

      schema.$defs.get("SomeAliasedClass").asInstanceOf[Schema]
        .properties("some_slot").asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/OtherAliasedClass")
    }

    "pascal case class names" in {
      val input =
        s"""$schemaShared
           |classes:
           |  some_other_class:
           |  some_class:
           |    tree_root: true
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |    range: some_other_class
           |    inlined: true
           |""".stripMargin

      given SchemaView = load(input)

      val schema = JsonSchemaGenerator().generate()

      schema.$defs.get.keys should contain theSameElementsAs Seq(
        "SomeClass",
        "SomeOtherClass",
      )

      schema.$defs.get("SomeClass").asInstanceOf[Schema]
        .properties("some_slot").asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass")
    }

    "work for recursive ADTs" in {
      /*
      Validates JSON like:
      {
        "name": "root",
        "children": {
          "child1": {}, // no further children
          "child2": {
            "child2child": {}
          }
        }
      }
       */
      val input =
        s"""$schemaShared
           |classes:
           |  Node:
           |    tree_root: true
           |    description: Tree of nodes
           |    attributes:
           |      name:
           |        key: true
           |        range: string
           |      children:
           |        # SimpleDict form = { name1: Node1, name2: Node2 }
           |        range: Node
           |        multivalued: true
           |        description: Dictionary of child nodes
           |""".stripMargin

      given SchemaView = load(input)

      val schema = JsonSchemaGenerator().generate()
      val node = schema.$defs.get("Node").asInstanceOf[Schema]

      val someSlot = node.properties("children").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.Object))
      someSlot.description shouldBe Some("Dictionary of child nodes")
      val additionalProperties = someSlot.additionalProperties.get.asInstanceOf[Schema]
      additionalProperties.$ref shouldBe Some("#/$defs/Node__simple_dict_value")
      node.description shouldBe Some("Tree of nodes")
      val value = schema.$defs.get("Node__simple_dict_value").asInstanceOf[Schema]
      value.`type` shouldBe Some(List(SchemaType.Object))
      val valueAdditionalProperties = value.additionalProperties.get.asInstanceOf[Schema]
      valueAdditionalProperties.$ref shouldBe Some("#/$defs/Node__simple_dict_value")
    }

    "carry over the titles and descriptions" in {
      given SchemaView = ModelCatalogue.metadata.title.model
      val schema = JsonSchemaGenerator().generate()
      schema.title shouldBe Some("Schema for testing")
      schema.description shouldBe Some(
        "This schema is used to test the title and description metadata fields.",
      )
      val cls = schema.$defs.get("SomeClass").asInstanceOf[Schema]
      cls.title shouldBe Some("Some Class")
      cls.description shouldBe Some("This is a class for testing purposes.")
      val slot = cls.properties("some_slot").asInstanceOf[Schema]
      slot.title shouldBe Some("Some Slot")
      slot.description shouldBe Some("This is a slot for testing purposes.")
    }

    "include inlined classes in $defs" in {
      given SchemaView = ModelCatalogue.inlines.implicitInline.model
      val schema = JsonSchemaGenerator().generate()
      schema.$defs.get.keys should contain theSameElementsAs Seq("SomeClass", "SomeOtherClass")
    }

    "not include non-inlined classes in $defs" in {
      given SchemaView = ModelCatalogue.reference.model
      val schema = JsonSchemaGenerator().generate()
      schema.$defs.get.keys should contain theSameElementsAs Seq("SomeClass")
    }

    "include minimum and maximum values for numbers" in {
      given SchemaView = ModelCatalogue.minMaxValues.model

      val schema = JsonSchemaGenerator().generate()
      val typedClass = schema.$defs.get("Typed").asInstanceOf[Schema]

      typedClass.properties.keys should contain theSameElementsAs Seq("intSlot", "floatSlot")

      val intSlot = typedClass.properties("intSlot").asInstanceOf[Schema]
      intSlot.minimum shouldBe Some(BigDecimal(-1))
      intSlot.maximum shouldBe Some(BigDecimal(1))
      intSlot.`type` shouldBe Some(List(SchemaType.Integer))
      val floatSlot = typedClass.properties("floatSlot").asInstanceOf[Schema]
      floatSlot.minimum shouldBe Some(BigDecimal(-2))
      floatSlot.maximum shouldBe Some(BigDecimal(2))
      floatSlot.`type` shouldBe Some(List(SchemaType.Number))
    }

    "generate the metamodel without errors" in {
      val sv = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta")
      given SchemaView = sv
      JsonSchemaGenerator().serialize() should not be ""
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all do
        s"model '${entry.model.root.name}'" in {
          assume(!skipModels.contains(entry.model.root.name))
          JsonSchemaGenerator(using entry.model).serialize() should not be ""
        }
    }
  }
}

object JsonSchemaGeneratorSpec {
  val skipModels: Map[String, String] = Map(
    "typeDesignator" -> "Not yet implemented: LNK-101",
    "unionRange" -> "Not yet implemented: LNK-100",
    "typed" -> "Not yet implemented: LNK-33",
  )
}
