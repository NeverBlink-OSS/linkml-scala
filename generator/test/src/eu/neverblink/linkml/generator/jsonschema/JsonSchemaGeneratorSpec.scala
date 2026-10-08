package eu.neverblink.linkml.generator.jsonschema

import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import sttp.apispec.circe.encoderSchema
import sttp.apispec.{AnySchema, ExampleSingleValue, Pattern, Schema, SchemaLike, SchemaType}

class JsonSchemaGeneratorSpec extends AnyWordSpec, Matchers {
  import JsonSchemaGeneratorSpec.skipModels
  def load(schemaYaml: String): SchemaView = {
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schemaYaml))
  }

  /** Assert that one entry of a dict inlined in the SimpleDict form accepts both the bare primary
    * value and the full object with an optional key.
    */
  def shouldBeSimpleDictEntryOf(entry: SchemaLike, cls: String): Unit =
    entry.asInstanceOf[Schema].oneOf.map(_.asInstanceOf[Schema].$ref) shouldBe List(
      Some(s"#/$$defs/${cls}__simple_dict_value"),
      Some(s"#/$$defs/${cls}__identifier_optional"),
    )

  "JsonSchemaGenerator" should {
    // Shared part of the schema
    val schemaShared =
      """id: https://neverblink.eu/linkml/jsonschema/test/
        |name: test
        |types:
        |  string:
        |    base: str
        |  integer:
        |    base: int
        |  boolean:
        |    base: Bool
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

    "generate anyOf alternatives for a type with union_of" in {
      given SchemaView = ModelCatalogue.unionOf.model

      val container = JsonSchemaGenerator().generate().$defs.get("Container").asInstanceOf[Schema]
      val value = container.properties("value").asInstanceOf[Schema]
      value.`type` shouldBe None
      value.anyOf.map(_.asInstanceOf[Schema].`type`) shouldBe List(
        Some(List(SchemaType.String)),
        Some(List(SchemaType.Integer)),
      )
      // Each member keeps the constraints of its own type.
      val choice = container.properties("choice").asInstanceOf[Schema].anyOf
        .map(_.asInstanceOf[Schema])
      choice.map(_.`type`) shouldBe List(
        Some(List(SchemaType.String)),
        Some(List(SchemaType.Integer)),
      )
      choice.map(_.pattern) shouldBe List(Some(Pattern("^[A-Z]+$")), None)
      choice.map(_.minimum) shouldBe List(None, Some(BigDecimal(0)))
    }

    "keep the declared order of union members, with nested unions in place" in {
      // Adds to the `types` of the shared part.
      given SchemaView = load(s"""$schemaShared
           |  StringOrInteger:
           |    union_of: [string, integer]
           |  Choice:
           |    union_of: [boolean, StringOrInteger, string]
           |classes:
           |  C:
           |    attributes:
           |      choice:
           |        range: Choice
           |""".stripMargin)

      val c = JsonSchemaGenerator().generate().$defs.get("C").asInstanceOf[Schema]
      // The repeated `string` is kept only at its first place, inside the nested union.
      c.properties("choice").asInstanceOf[Schema].anyOf
        .map(_.asInstanceOf[Schema].`type`) shouldBe List(
        Some(List(SchemaType.Boolean)),
        Some(List(SchemaType.String)),
        Some(List(SchemaType.Integer)),
      )
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
      shouldBeSimpleDictEntryOf(someSlot.additionalProperties.get, "SomeOtherClass")
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
      shouldBeSimpleDictEntryOf(someSlot.additionalProperties.get, "SomeOtherClass")
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

    "allow additional properties for classes whose extra_slots allows them" in {
      val input =
        s"""$schemaShared
           |classes:
           |  Open:
           |    extra_slots:
           |      allowed: true
           |    attributes:
           |      a: {}
           |  Typed:
           |    extra_slots:
           |      range_expression:
           |        range: string
           |    attributes:
           |      a: {}
           |  ClosedTyped:
           |    extra_slots:
           |      allowed: false
           |      range_expression:
           |        range: string
           |    attributes:
           |      a: {}
           |  OpenChild:
           |    is_a: Open
           |  Plain:
           |    attributes:
           |      a: {}
           |""".stripMargin

      given SchemaView = load(input)

      val defs = JsonSchemaGenerator().generate().$defs.get
      def additionalProperties(cls: String): SchemaLike =
        defs(cls).asInstanceOf[Schema].additionalProperties.get

      additionalProperties("Open") shouldBe AnySchema.Anything
      // Data matching `range_expression` is allowed, but not checked against it
      additionalProperties("Typed") shouldBe AnySchema.Anything
      additionalProperties("ClosedTyped") shouldBe AnySchema.Nothing
      additionalProperties("OpenChild") shouldBe AnySchema.Nothing
      additionalProperties("Plain") shouldBe AnySchema.Nothing
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

    "not remove spaces from slot names" in {
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
        .properties.keys should contain theSameElementsAs Seq("some slot")
    }

    "not alias class names" in {
      // Not present in the data = no aliasing
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
        "SomeClass",
        "SomeOtherClass",
      )

      schema.$defs.get("SomeClass").asInstanceOf[Schema]
        .properties("some_slot").asInstanceOf[Schema]
        .$ref shouldBe Some("#/$defs/SomeOtherClass")
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
      shouldBeSimpleDictEntryOf(someSlot.additionalProperties.get, "Node")
      node.description shouldBe Some("Tree of nodes")
      val value = schema.$defs.get("Node__simple_dict_value").asInstanceOf[Schema]
      value.`type` shouldBe Some(List(SchemaType.Object))
      shouldBeSimpleDictEntryOf(value.additionalProperties.get, "Node")
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

    "include constraints for numeric and string values" in {
      given SchemaView = ModelCatalogue.constraints.model

      val schema = JsonSchemaGenerator().generate()
      val typedClass = schema.$defs.get("Typed").asInstanceOf[Schema]

      typedClass.properties.keys should contain theSameElementsAs Seq(
        "int_slot",
        "float_slot",
        "string_slot",
      )

      val intSlot = typedClass.properties("int_slot").asInstanceOf[Schema]
      intSlot.`type` shouldBe Some(List(SchemaType.Integer))
      intSlot.minimum shouldBe Some(BigDecimal(-1))
      intSlot.maximum shouldBe Some(BigDecimal(1))
      val floatSlot = typedClass.properties("float_slot").asInstanceOf[Schema]
      floatSlot.`type` shouldBe Some(List(SchemaType.Number))
      floatSlot.minimum shouldBe Some(BigDecimal(-2))
      floatSlot.maximum shouldBe Some(BigDecimal(2))
      val stringSlot = typedClass.properties("string_slot").asInstanceOf[Schema]
      stringSlot.`type` shouldBe Some(List(SchemaType.String))
      stringSlot.pattern shouldBe Some(Pattern("""^([0-9]{3})?[0-9]{3}-[0-9]{4}$"""))
    }

    "add format for URIs" in {
      given SchemaView = ModelCatalogue.uri.model
      val json = JsonSchemaGenerator().generate()
      val someClass = json.$defs.get("SomeClass").asInstanceOf[Schema]
      someClass.properties("some_slot").asInstanceOf[Schema].format shouldBe Some("uri")
    }

    "add format for CURIEs" in {
      given SchemaView = ModelCatalogue.curie.model
      val json = JsonSchemaGenerator().generate()
      val someClass = json.$defs.get("SomeClass").asInstanceOf[Schema]
      someClass.properties("some_slot").asInstanceOf[Schema].format shouldBe Some("curie")
    }

    "add format for URIs or CURIEs" in {
      given SchemaView = ModelCatalogue.uriOrCurie.model

      val json = JsonSchemaGenerator().generate()
      val someClass = json.$defs.get("SomeClass").asInstanceOf[Schema]
      someClass.properties("some_slot").asInstanceOf[Schema]
        .anyOf.collect { case schema: Schema =>
          schema.format
        } should contain theSameElementsAs Seq(Some("uri"), Some("curie"))
    }

    "add format for dates" in {
      given SchemaView = ModelCatalogue.typed.model

      val json = JsonSchemaGenerator().generate()
      val someClass = json.$defs.get("Typed").asInstanceOf[Schema]
      val dateSlot = someClass.properties("date_slot").asInstanceOf[Schema]
      dateSlot.`type` shouldBe Some(List(SchemaType.String))
      dateSlot.format shouldBe Some("date")
    }

    "use base type" in {
      given SchemaView = ModelCatalogue.typed.model

      val json = JsonSchemaGenerator().generate()
      val someClass = json.$defs.get("Typed").asInstanceOf[Schema]
      val customSlot = someClass.properties("custom_slot").asInstanceOf[Schema]
      customSlot.`type` shouldBe Some(List(SchemaType.String))
    }

    "emit enums" in {
      given SchemaView = ModelCatalogue.`enum`.model

      val schema = JsonSchemaGenerator().generate()
      schema.$ref shouldBe Some("#/$defs/SomeClass")
      val c = schema.$defs.get("SomeClass").asInstanceOf[Schema]

      val someSlot = c.properties("some_slot").asInstanceOf[Schema]
      someSlot.$ref shouldBe Some("#/$defs/SomeEnum")
      schema.$defs.get.keys.toSeq should contain("SomeEnum")
      val someEnum = schema.$defs.get("SomeEnum").asInstanceOf[Schema]
      someEnum.`type`.get should contain(SchemaType.String)
      someEnum.`enum`.get should contain(ExampleSingleValue("SOME_OPTION"))
      someEnum.`enum`.get should contain(ExampleSingleValue("SOME_OTHER_OPTION"))
      someEnum.`enum`.get should contain(ExampleSingleValue("YET_ANOTHER_OPTION"))
    }

    "emit minItems and maxItems for explicit cardinality" in {
      given SchemaView = ModelCatalogue.cardinalityExplicit.model

      val schema = JsonSchemaGenerator().generate()
      val c = schema.$defs.get("Cardinal").asInstanceOf[Schema]

      val exactlyTwo = c.properties("exactly_two").asInstanceOf[Schema]
      exactlyTwo.`type` shouldBe Some(List(SchemaType.Array))
      exactlyTwo.minItems shouldBe Some(2)
      exactlyTwo.maxItems shouldBe Some(2)

      val oneToThree = c.properties("one_to_three").asInstanceOf[Schema]
      oneToThree.`type` shouldBe Some(List(SchemaType.Array))
      oneToThree.minItems shouldBe Some(1)
      oneToThree.maxItems shouldBe Some(3)
    }

    "emit minProperties and maxProperties for explicit cardinality on inlined dicts" in {
      val input =
        s"""$schemaShared
           |classes:
           |  Root:
           |    tree_root: true
           |    attributes:
           |      people:
           |        range: Person
           |        multivalued: true
           |        inlined: true
           |        minimum_cardinality: 1
           |        maximum_cardinality: 5
           |  Person:
           |    attributes:
           |      id:
           |        identifier: true
           |        range: string
           |      name:
           |        range: string
           |      age:
           |        range: integer
           |""".stripMargin

      given SchemaView = load(input)
      val schema = JsonSchemaGenerator().generate()
      val people = schema.$defs.get("Root").asInstanceOf[Schema]
        .properties("people").asInstanceOf[Schema]
      people.`type` shouldBe Some(List(SchemaType.Object))
      people.minProperties shouldBe Some(1)
      people.maxProperties shouldBe Some(5)
    }

    "ignore explicit cardinality on single-valued slots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    tree_root: true
           |    attributes:
           |      some_slot:
           |        range: string
           |        exact_cardinality: 3
           |""".stripMargin

      given SchemaView = load(input)
      val schema = JsonSchemaGenerator().generate()
      // There is no collection to size here, so the metaslots do not apply.
      val someSlot = schema.$defs.get("SomeClass").asInstanceOf[Schema]
        .properties("some_slot").asInstanceOf[Schema]
      someSlot.`type` shouldBe Some(List(SchemaType.String))
      someSlot.minItems shouldBe None
      someSlot.maxItems shouldBe None
      someSlot.minProperties shouldBe None
      someSlot.maxProperties shouldBe None
    }

    "look at the identifier type when generating references" in {
      given SchemaView = ModelCatalogue.referenceInteger.model

      val schema = JsonSchemaGenerator().generate()

      schema.$defs.get("SomeClass")
        .asInstanceOf[Schema]
        .properties("some_slot")
        .asInstanceOf[Schema]
        .`type` shouldBe Some(List(SchemaType.Integer))
    }

    "apply constraints on types" in {
      given SchemaView = ModelCatalogue.constraintsOnTypes.model

      val schema = JsonSchemaGenerator().generate()

      val typed = schema.$defs.get("Typed")
        .asInstanceOf[Schema]

      val int = typed
        .properties("int_slot")
        .asInstanceOf[Schema]
      int.`type` shouldBe Some(List(SchemaType.Integer))
      int.minimum shouldBe Some(-1)
      int.maximum shouldBe Some(1)

      val float = typed
        .properties("float_slot")
        .asInstanceOf[Schema]
      float.`type` shouldBe Some(List(SchemaType.Number))
      float.minimum shouldBe Some(-2.0)
      float.maximum shouldBe Some(2.0)

      val string = typed
        .properties("string_slot")
        .asInstanceOf[Schema]
      string.`type` shouldBe Some(List(SchemaType.String))
      string.pattern shouldBe Some(Pattern("^([0-9]{3})?[0-9]{3}-[0-9]{4}$"))
    }

    "handle the tree_root_as extension" when {

      def treeRootAs(mode: String): String =
        s"""$schemaShared
           |
           |classes:
           |  C1:
           |    tree_root: true
           |    extensions:
           |      tree_root_as: $mode
           |    attributes:
           |      id: 
           |        key: true
           |        range: string
           |      value:
           |        range: string
           |    
           |""".stripMargin

      "extension is plain" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("plain"),
          ),
        )

        JsonSchemaGenerator().generate().$ref shouldBe Some("#/$defs/C1")
      }

      "extension is optional" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("optional"),
          ),
        )

        val schema = JsonSchemaGenerator().generate()

        schema.oneOf.map(
          _.asInstanceOf[Schema].$ref,
        ) should contain(Some("#/$defs/C1"))

        schema.oneOf.map(
          _.asInstanceOf[Schema].`type`,
        ) should contain(Some(List(SchemaType.Null)))
      }

      "extension is list" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("list"),
          ),
        )

        val schema = JsonSchemaGenerator().generate()

        schema.`type` shouldBe Some(List(SchemaType.Array))

        schema.items.map(
          _.asInstanceOf[Schema].$ref,
        ) should contain(Some("#/$defs/C1"))
      }

      "extension is compact_dict" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("compact_dict"),
          ),
        )

        val schema = JsonSchemaGenerator().generate()
        schema.additionalProperties.get.asInstanceOf[Schema]
          .$ref shouldBe Some("#/$defs/C1__identifier_optional")

        schema.$defs.get.keys should contain("C1__identifier_optional")
      }

      "extension is simple_dict" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("simple_dict"),
          ),
        )

        val schema = JsonSchemaGenerator().generate()
        shouldBeSimpleDictEntryOf(schema.additionalProperties.get, "C1")

        schema.$defs.get.keys should contain("C1__simple_dict_value")
      }

      "override to plain" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("list"),
          ),
        )

        JsonSchemaGenerator().generate(
          JsonSchemaGenerator.Options(treeRootInlineType = Some("plain")),
        )
          .$ref shouldBe Some("#/$defs/C1")
      }

      "override to optional" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("plain"),
          ),
        )

        val schema = JsonSchemaGenerator().generate(
          JsonSchemaGenerator.Options(treeRootInlineType = Some("optional")),
        )

        schema.oneOf.map(
          _.asInstanceOf[Schema].$ref,
        ) should contain(Some("#/$defs/C1"))

        schema.oneOf.map(
          _.asInstanceOf[Schema].`type`,
        ) should contain(Some(List(SchemaType.Null)))
      }

      "override to list" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("plain"),
          ),
        )

        val schema = JsonSchemaGenerator().generate(
          JsonSchemaGenerator.Options(treeRootInlineType = Some("list")),
        )

        schema.`type` shouldBe Some(List(SchemaType.Array))

        schema.items.map(
          _.asInstanceOf[Schema].$ref,
        ) should contain(Some("#/$defs/C1"))
      }

      "override to compact_dict" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("plain"),
          ),
        )

        val schema =
          JsonSchemaGenerator().generate(
            JsonSchemaGenerator.Options(treeRootInlineType = Some("compact_dict")),
          )

        schema.additionalProperties.get.asInstanceOf[Schema]
          .$ref shouldBe Some("#/$defs/C1__identifier_optional")

        schema.$defs.get.keys should contain("C1__identifier_optional")
      }

      "override to simple_dict" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            treeRootAs("plain"),
          ),
        )

        val schema =
          JsonSchemaGenerator().generate(
            JsonSchemaGenerator.Options(treeRootInlineType = Some("simple_dict")),
          )

        shouldBeSimpleDictEntryOf(schema.additionalProperties.get, "C1")

        schema.$defs.get.keys should contain("C1__simple_dict_value")
      }

      "fail if tree_root can't be a compact dict" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            s"""$schemaShared
             |classes:
             |  C1:
             |    tree_root: true
             |    extensions:
             |      tree_root_as: compact_dict
             |    attributes:
             |      s1:
             |        range: string
             |      s2:
             |        range: string
             |""".stripMargin,
          ),
        )

        val ex = intercept[IllegalArgumentException] {
          JsonSchemaGenerator().generate()
        }.getMessage
        ex should include("C1")
        ex should include("tree_root_as")
        ex should include("compact_dict")
      }

      "fail if tree_root can't be a simple dict" in {
        given SchemaView = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromString(
            s"""$schemaShared
             |classes:
             |  C1:
             |    tree_root: true
             |    extensions:
             |      tree_root_as: simple_dict
             |    attributes:
             |      s1:
             |        range: string
             |      s2:
             |        range: string
             |""".stripMargin,
          ),
        )

        val ex = intercept[IllegalArgumentException] {
          JsonSchemaGenerator().generate()
        }.getMessage
        ex should include("C1")
        ex should include("tree_root_as")
        ex should include("simple_dict")
      }
    }

    "type designators" should {
      // With `extraSlots`, `Base` has two required non-key slots, so it is inlined as a CompactDict
      // instead of a SimpleDict
      def designatorSchema(extraSlots: Boolean): String =
        """id: https://example.org/designators/
          |name: designators
          |prefixes:
          |  ex: https://example.org/
          |  linkml: https://w3id.org/linkml/
          |imports:
          |  - linkml:types
          |default_prefix: ex
          |classes:
          |  Container:
          |    tree_root: true
          |    attributes:
          |      items:
          |        range: Base
          |        multivalued: true
          |        inlined: true
          |        inlined_as_list: false
          |  Base:
          |    abstract: true
          |    attributes:
          |      id:
          |        identifier: true
          |        range: string
          |      kind:
          |        designates_type: true
          |        range: uriorcurie
          |        required: true
          |""".stripMargin + (if (extraSlots)
                                "      label:\n        range: string\n        required: true\n"
                              else "") +
          """  A:
          |    is_a: Base
          |    class_uri: ex:A
          |  B:
          |    is_a: Base
          |    class_uri: ex:B
          |""".stripMargin

      "restrict the designator of a concrete class to the URI and CURIE of the class" in {
        given SchemaView = load(designatorSchema(false))
        val defs = JsonSchemaGenerator().generate().$defs.get

        def kind(cls: String): Schema =
          defs(cls).asInstanceOf[Schema].properties("kind").asInstanceOf[Schema]

        kind("A").`enum` shouldBe Some(
          List(ExampleSingleValue("https://example.org/A"), ExampleSingleValue("ex:A")),
        )
        kind("B").`enum` shouldBe Some(
          List(ExampleSingleValue("https://example.org/B"), ExampleSingleValue("ex:B")),
        )
        kind("Base").`enum` shouldBe None
      }

      def refs(schema: SchemaLike): List[Option[String]] =
        schema.asInstanceOf[Schema].anyOf.map(_.asInstanceOf[Schema].$ref)

      def itemsEntry(defs: collection.Map[String, SchemaLike]): Schema =
        defs("Container").asInstanceOf[Schema].properties("items").asInstanceOf[Schema]
          .additionalProperties.get.asInstanceOf[Schema]

      "accept any concrete subclass in a simple dict, and keep the subclasses when pruning" in {
        given SchemaView = load(designatorSchema(false))
        val defs = JsonSchemaGenerator().generate().$defs.get

        defs.keys should contain allOf (
          "A",
          "B",
          "A__identifier_optional",
          "B__identifier_optional",
        )
        defs("A__identifier_optional").asInstanceOf[Schema].required shouldBe List("kind")
        val entry = itemsEntry(defs)
        entry.oneOf.head.asInstanceOf[Schema].$ref shouldBe Some("#/$defs/Base__simple_dict_value")
        refs(entry.oneOf(1)) shouldBe List(
          Some("#/$defs/A__identifier_optional"),
          Some("#/$defs/B__identifier_optional"),
        )
      }

      "accept any concrete subclass in a compact dict" in {
        given SchemaView = load(designatorSchema(true))
        val defs = JsonSchemaGenerator().generate().$defs.get

        refs(itemsEntry(defs)) shouldBe List(
          Some("#/$defs/A__identifier_optional"),
          Some("#/$defs/B__identifier_optional"),
        )
      }

      "accept a concrete tree root and any of its concrete subclasses" in {
        given SchemaView = ModelCatalogue.typeDesignator.model
        JsonSchemaGenerator().generate().anyOf.map(_.asInstanceOf[Schema].$ref) shouldBe List(
          Some("#/$defs/IntThing"),
          Some("#/$defs/StringThing"),
          Some("#/$defs/Thing"),
        )
      }
    }

    "generate the metamodel without errors" in {
      val sv =
        SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta"))
      given SchemaView = sv
      val generator = JsonSchemaGenerator()
      val expected = encoderSchema(generator.generate(JsonSchemaGenerator.Options())).noSpaces
      val result = generator.serialize(JsonSchemaGenerator.Options(indentationStep = 0))
      result shouldBe expected
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all do
        s"model '${entry.model.root.name}'" in {
          assume(!skipModels.contains(entry.model.root.name))
          val generator = JsonSchemaGenerator(using entry.model)
          val expected = encoderSchema(generator.generate(JsonSchemaGenerator.Options())).noSpaces
          val result = generator.serialize(JsonSchemaGenerator.Options(indentationStep = 0))
          result shouldBe expected
        }
    }
  }
}

object JsonSchemaGeneratorSpec {
  val skipModels: Map[String, String] = Map(
    "unionRange" -> "Not yet implemented: LNK-100",
  )
}
