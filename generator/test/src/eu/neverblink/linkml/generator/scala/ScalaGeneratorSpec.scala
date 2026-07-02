package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.metamodel.Codec
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

class ScalaGeneratorSpec extends AnyWordSpec, Matchers {
  def decode(schemaYaml: String): SchemaView =
    SchemaView.loadSchemaViewFromString(schemaYaml)

  "Scala generator" should {
    // Shared part of the schema
    val schemaShared =
      """id: https://neverblink.eu/linkml/scala/test
        |name: test
        |imports:
        | - linkml:types
        |"""

    val testPkg = "eu.neverblink.linkml.generator.scala.test"

    "generate abstract interfaces and implementations for plain classes" in {
      given SchemaView = ModelCatalogue.basic.model
      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        s"package $testPkg",
        "case class SomeClassImpl",
        "extends SomeClass",
        "abstract class SomeClass",
        "someOtherSlot: Int",
        "someSlot: Option[String] = None",
      ).foreach { snippet =>
        code should include(snippet)
      }

      Seq(
        "Option[Int]",
        "Int =",
        "trait",
      ).foreach { snippet =>
        code should not include snippet
      }
    }

    "not generate implementations for abstract classes" in {
      given SchemaView = ModelCatalogue.`abstract`.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "abstract class SomeClass",
        "def someOtherSlot: Int",
        "def someSlot: Option[String]",
      ).foreach { snippet =>
        code should include(snippet)
      }

      Seq(
        "Option[Int]",
        "Int =",
        "trait",
        "case class",
      ).foreach { snippet =>
        code should not include snippet
      }
    }

    "generate trait interfaces for mixin classes" in {
      given SchemaView = ModelCatalogue.mixin.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeOtherClass.scala")
      Seq(
        "trait SomeOtherClass",
        "def someOtherSlot: Int",
      ).foreach { snippet =>
        code should include(snippet)
      }

      Seq(
        "Option[Int]",
        "Int =",
        "abstract class",
        "case class",
      ).foreach { snippet =>
        code should not include snippet
      }
    }

    "generate abstract interfaces with inheritance" in {
      given SchemaView = ModelCatalogue.inheritance.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("ChildClass.scala")
      Seq(
        "abstract class ChildClass extends BaseClass",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "reference other classes" in {
      given SchemaView = ModelCatalogue.reference.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "Option[Reference[SomeOtherClass]]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "reference multivalued other classes as a list of references" in {
      given SchemaView = ModelCatalogue.multivaluedReference.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "Seq[Reference[SomeOtherClass]]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "implicitly inline identifier-less classes" in {
      given SchemaView = ModelCatalogue.inlines.implicitInline.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "Option[SomeOtherClassImpl]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "implicitly inline multivalued other classes (implicitly as compact dict)" in {
      given SchemaView = ModelCatalogue.inlines.implicitInlineAsCompactDict.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "@compactDict",
        "Map[String, SomeOtherClassImpl]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "implicitly inline multivalued other classes (implicitly as list)" in {
      given SchemaView = ModelCatalogue.inlines.implicitInlineAsList.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "Seq[SomeOtherClassImpl]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "explicitly inline multivalued other classes (implicitly as compact dict)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineImplicitlyAsCompactDict.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "@compactDict",
        "Map[String, SomeOtherClassImpl]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "explicitly inline multivalued other classes (implicitly as simple dict)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineImplicitlyAsSimpleDict.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "@simpleDict",
        "Map[String, SomeOtherClassImpl]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "explicitly inline multivalued other classes (implicitly as list)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineImplicitlyAsList.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "Seq[SomeOtherClassImpl]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "explicitly inline multivalued other classes (explicitly as list)" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineList.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        "Seq[SomeOtherClassImpl]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "provide annotations for the 'alias' slot" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    attributes:
           |      some_slot:
           |        alias: serialized_name
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include("@named(\"serialized_name\")")
      files("SomeClass.scala") should include("someSlot: Option[String]")
    }

    "provide annotations inlining the class compact dict style" in {
      given SchemaView = ModelCatalogue.inlines.explicitInlineImplicitlyAsCompactDict.model

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeOtherClass.scala") should include regex raw"@id\s*id: String"
    }

    "provide annotations inlining the class simple dict style" in {
      given SchemaView = ModelCatalogue.inlines.selfSimple2.model

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include regex """@id\s*(@named\(".*"\))?\s*id: String"""
      files(
        "SomeClass.scala",
      ) should include regex """@value\s*(@named\(".*"\))?\s*someSlot: Option\[String\]"""
    }

    "provide annotations inlining the class simple dict style (2 required fields case)" in {
      given SchemaView = ModelCatalogue.inlines.selfSimple2Required.model

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include regex """@id\s*(@named\(".*"\))?\s*id: String"""
      files(
        "SomeClass.scala",
      ) should include regex """@value\s*(@named\(".*"\))?\s*someSlot: String"""
    }

    "only provide annotations for inlining the class as a compact dict (2> slots)" in {
      given SchemaView = ModelCatalogue.inlines.selfCompact3.model

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include regex raw"@id\s*id: String"
      files("SomeClass.scala") shouldNot include("@value")
    }

    "only provide annotations for inlining the class as a compact dict (2> required slots)" in {
      given SchemaView = ModelCatalogue.inlines.selfCompact3Required.model

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include regex raw"@id\s*id: String"
      files("SomeClass.scala") shouldNot include("@value")
    }

    "generate is_a and mixin inheritance" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeMixin:
           |    mixin: true
           |    slots:
           |    - some_mixin_slot
           |  SomeOtherMixin:
           |    mixin: true
           |    slots:
           |    - some_other_mixin_slot
           |  SomeOtherClass:
           |    abstract: true
           |    attributes:
           |      some_other_slot:
           |  SomeClass:
           |    is_a: SomeOtherClass
           |    mixins:
           |    - SomeMixin
           |    - SomeOtherMixin
           |    slots:
           |    - some_slot
           |slots:
           |  some_slot:
           |  some_mixin_slot:
           |  some_other_mixin_slot:
           |""".stripMargin

      given SchemaView = decode(input)
      val files = ScalaGenerator().generate(testPkg).toMap
      val code = files("SomeClass.scala")
      Seq(
        "extends SomeOtherClass, SomeMixin, SomeOtherMixin",
        "someSlot: Option[String]",
        "someOtherSlot: Option[String]",
        "someMixinSlot: Option[String]",
        "someOtherMixinSlot: Option[String]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate inheritance trees" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherOtherClass:
           |    abstract: true
           |    attributes:
           |      some_other_other_slot:
           |  SomeOtherClass:
           |    is_a: SomeOtherOtherClass
           |    abstract: true
           |    attributes:
           |      some_other_slot:
           |  SomeClass:
           |    is_a: SomeOtherClass
           |    attributes:
           |      some_slot:
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include(
        "SomeClass extends SomeOtherClass",
      )
      files("SomeClass.scala") should not include
        "SomeClass extends SomeOtherClass, SomeOtherOtherClass"

      files("SomeOtherClass.scala") should include(
        "SomeOtherClass extends SomeOtherOtherClass",
      )

    }

    "generate only changed fields in interfaces" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    abstract: true
           |    attributes:
           |      some_slot:
           |        range: integer
           |        required: true
           |      some_other_slot:
           |  SomeClass:
           |    is_a: SomeOtherClass
           |    slot_usage:
           |      some_slot:
           |        description: Some slot description
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include("def someSlot: Int")
      files("SomeClass.scala") should include("someSlot: Int,")
      files("SomeClass.scala") should not include
        "def someOtherSlot:"
    }

    "generate default values for boolean slots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    attributes:
           |      some_slot:
           |        range: boolean
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap
      files("SomeClass.scala") should include("someSlot: Boolean = false,")
    }

    "generate None default values for non-required slots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    attributes:
           |      some_slot:
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap
      files("SomeClass.scala") should include("someSlot: Option[String] = None,")
    }

    "generate default values for dict-inline slots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    attributes:
           |      id:
           |        identifier: true
           |  SomeClass:
           |    attributes:
           |      some_slot:
           |        range: SomeOtherClass
           |        inlined: true
           |        multivalued: true
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap
      files("SomeClass.scala") should include("someSlot: Map[String, SomeOtherClassImpl] = Map(),")
    }

    "generate default values for seq-inline slots" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeOtherClass:
           |    attributes:
           |      id:
           |        identifier: true
           |  SomeClass:
           |    attributes:
           |      some_slot:
           |        range: SomeOtherClass
           |        multivalued: true
           |        inlined: true
           |        inlined_as_list: true
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap
      files("SomeClass.scala") should include("someSlot: Seq[SomeOtherClassImpl] = Seq(),")
    }

    "alias the runtime Anything for linkml:Any" in {
      val input =
        s"""$schemaShared
           |classes:
           |  MyAny:
           |    class_uri: https://w3id.org/linkml/Any
           |  SomeClass:
           |    attributes:
           |      some_slot:
           |        range: MyAny
           |        required: true
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include("def someSlot: MyAny")
      files("SomeClass.scala") should include("someSlot: MyAny,")
      files("MyAny.scala") should include("type MyAny = LinkMlAny")
    }

    "generate a slot combining function for linkml:SlotDefinition" in {
      val input =
        s"""$schemaShared
           |classes:
           |  MyAny:
           |    class_uri: https://w3id.org/linkml/Any
           |  MyElement:
           |    attributes:
           |      id:
           |        identifier: true
           |  MySlotDef:
           |    class_uri: https://w3id.org/linkml/SlotDefinition
           |    attributes:
           |      inherited_slot:
           |        required: true
           |        inherited: true
           |      fallback_slot:
           |        required: true
           |      option_slot:
           |      bool_slot:
           |        range: boolean
           |      seq_slot:
           |        multivalued: true
           |      map_slot:
           |        range: MyElement
           |        inlined: true
           |      range:
           |        range: MyElement
           |        slot_uri: "https://w3id.org/linkml/range"
           |      pattern:
           |        slot_uri: "https://w3id.org/linkml/pattern"
           |      minimum_value:
           |        range: MyAny
           |        slot_uri: "https://w3id.org/linkml/minimum_value"
           |      maximum_value:
           |        range: MyAny
           |        slot_uri: "https://w3id.org/linkml/maximum_value"
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap

      val code = files("MySlotDef.scala")
      Seq(
        "def combineWith(",
        "boolSlot = combineBoolean(this.boolSlot, other.boolSlot)",
        "pattern = combineOption(this.pattern, other.pattern, combinePattern)",
        "seqSlot = combineSeq(this.seqSlot, other.seqSlot)",
        "mapSlot = combineOption(this.mapSlot, other.mapSlot, combineFallback)",
        "maximumValue = combineOption(this.maximumValue, other.maximumValue, combineMax)",
        "range = combineOption(this.range, other.range, combineRange)",
        "inheritedSlot = combineFallback(this.inheritedSlot, other.inheritedSlot)",
        "optionSlot = combineOption(this.optionSlot, other.optionSlot, combineFallback)",
        "fallbackSlot = combineFallback(this.fallbackSlot, other.fallbackSlot)",
        "minimumValue = combineOption(this.minimumValue, other.minimumValue, combineMin)",
        "def combineInherited(other: MySlotDefImpl, combineRange: (Reference[Element], Reference[Element]) => Reference[Element]): MySlotDefImpl =\n    copy(\n      inheritedSlot = combineFallback(this.inheritedSlot, other.inheritedSlot)\n    )",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate in the correct package" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap

      files("SomeClass.scala") should include(testPkg)
    }

    "generate docs" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    description: class description
           |    from_schema: https://neverblink.eu/
           |    see_also:
           |    - http://www.w3.org/1999/02/22-rdf-syntax-ns#
           |    - http://www.w3.org/2000/01/rdf-schema#
           |    aliases:
           |    - alias 1
           |    - alias 2
           |    notes:
           |    - note 1
           |    - note 2
           |    comments:
           |    - comment 1
           |    - comment 2
           |    todos:
           |    - todo 1
           |    - todo 2
           |    examples:
           |    - value: ex1
           |      description: example 1 description
           |    - value: ex2
           |      description: example 2 description
           |""".stripMargin

      given SchemaView = decode(input)

      val files = ScalaGenerator().generate(testPkg).toMap

      val code = files("SomeClass.scala")
      Seq(
        "/** Class description",
        "@see\n  *   http://www.w3.org/1999/02/22-rdf-syntax-ns#",
        "@see\n  *   http://www.w3.org/2000/01/rdf-schema#",
        "@see\n  *   Aliases: alias 1, alias 2",
        "@see\n  *   From schema: https://neverblink.eu/",
        "@note\n  *   Note 1",
        "@note\n  *   Note 2",
        "@note\n  *   Comment 1",
        "@note\n  *   Comment 2",
        "@todo\n  *   Todo 1",
        "@todo\n  *   Todo 2",
        "@example\n  *   `ex1`: example 1 description",
        "@example\n  *   `ex2`: example 2 description",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate types (lax on 'inlined: true')" in {
      val input =
        s"""$schemaShared
           |classes:
           |  SomeClass:
           |    slots:
           |    - some_slot
           |    - some_other_slot
           |slots:
           |  some_slot:
           |    inlined: true
           |    required: true
           |    range: string
           |  some_other_slot:
           |    multivalued: true
           |    inlined: true
           |    range: integer
           |    required: true
           |""".stripMargin

      given SchemaView = decode(input)

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        ": String",
        ": Seq[Int]",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate enums as sealed abstract classes" in {
      val input =
        s"""$schemaShared
           |enums:
           |  SomeEnum:
           |    description: Enum description.
           |    permissible_values:
           |      value1:
           |        description: Value 1.
           |      value2:
           |        description: Value 2.
           |      value3:
           |        description: Value 3.
           |""".stripMargin

      given SchemaView = decode(input)

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeEnum.scala")
      code shouldBe
        """package eu.neverblink.linkml.generator.scala.test
          |
          |// GENERATED FROM LINKML
          |
          |import eu.neverblink.linkml.runtime.*
          |
          |/** Enum description.
          |  */
          |sealed abstract class SomeEnum
          |
          |object SomeEnum {
          |  /** Value 1.
          |    */
          |  @named("value1") case object Value1 extends SomeEnum
          |  /** Value 2.
          |    */
          |  @named("value2") case object Value2 extends SomeEnum
          |  /** Value 3.
          |    */
          |  @named("value3") case object Value3 extends SomeEnum
          |}
          |""".stripMargin
    }

    "generate enums with mixin flag as sealed traits" in {
      val input =
        s"""$schemaShared
           |enums:
           |  SomeEnum:
           |    mixin: true
           |    permissible_values:
           |      value1:
           |      value2:
           |      value3:
           |""".stripMargin

      given SchemaView = decode(input)

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeEnum.scala")
      Seq(
        "sealed trait SomeEnum",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate enums with mixin and abstract flags as regular traits" in {
      val input =
        s"""$schemaShared
           |enums:
           |  SomeEnum:
           |    mixin: true
           |    abstract: true
           |    permissible_values:
           |      value1:
           |      value2:
           |      value3:
           |""".stripMargin

      given SchemaView = decode(input)

      val code = ScalaGenerator().generate(testPkg).toMap.apply("SomeEnum.scala")
      Seq(
        "trait SomeEnum",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate an emit_prefixes object" in {
      val files = ScalaGenerator(using ModelCatalogue.emitPrefixes.model)
        .generate(testPkg).toMap
      files.keys should contain theSameElementsAs Seq(
        "SomeClass.scala",
        "Prefixes.scala",
      )
      val code = files("Prefixes.scala")
      Seq(
        "\"linkml\" -> \"https://w3id.org/linkml/\",",
        "\"ex\" -> \"http://example.org/\",",
        "\"nb\" -> \"https://neverblink.eu/example#\",",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate the metamodel" in {
      val sv = SchemaView.loadSchemaViewFromUri("https://w3id.org/linkml/meta")
      given SchemaView = sv

      ScalaGenerator().generate("eu.neverblink.linkml.metamodel")
    }

    "generate all catalogue models without errors" when {
      for entry <- ModelCatalogue.all do
        s"model '${entry.model.root.name}'" in {
          val files = ScalaGenerator(using entry.model).generate("eu.neverblink.linkml.scala.test")
          files should not be empty
          for (_, content) <- files do content should not be ""
        }
    }
  }
}
