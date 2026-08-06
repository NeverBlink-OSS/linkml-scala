package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

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

    "type inlined ranges of abstract classes and mixins as the interface" in {
      // Abstract classes and mixins get no `...Impl` case class, so an inlined range pointing at
      // one has to be typed as the interface, or the generated code does not compile.
      given SchemaView = ModelCatalogue.inlines.inlineAbstract.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("Container.scala")
      Seq(
        "toAbstract: Option[AbstractRange] = None",
        "toMixin: Option[MixinRange] = None",
        "manyAbstract: Seq[AbstractRange] = Seq()",
        // A concrete range still gets the implementation type
        "toConcrete: Option[ConcreteRangeImpl] = None",
      ).foreach { snippet =>
        code should include(snippet)
      }

      Seq(
        "AbstractRangeImpl",
        "MixinRangeImpl",
      ).foreach { snippet =>
        code should not include snippet
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
      files("MyAny.scala") should include("type MyAny = LinkmlAny")
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
          |  *
          |  * @see
          |  *   From schema: https://neverblink.eu/linkml/scala/test
          |  */
          |sealed abstract class SomeEnum derives Stringify
          |
          |object SomeEnum {
          |  /** Value 1.
          |    *
          |    * @see
          |    *   From schema: https://neverblink.eu/linkml/scala/test
          |    */
          |  @named("value1") case object Value1 extends SomeEnum
          |  /** Value 2.
          |    *
          |    * @see
          |    *   From schema: https://neverblink.eu/linkml/scala/test
          |    */
          |  @named("value2") case object Value2 extends SomeEnum
          |  /** Value 3.
          |    *
          |    * @see
          |    *   From schema: https://neverblink.eu/linkml/scala/test
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

    "generate an infer() method from equals_expression" in {
      val code = ScalaGenerator(using ModelCatalogue.equalsExpression.model)
        .generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        // Declared on the interface, narrowed to the implementation type in the impl
        "def infer(): SomeClass\n",
        "def infer(): SomeClassImpl",
        // Optional target is filled in, the Option-ranged reference is unwrapped
        """optionalMessage = inferOptional("optional_message", optionalMessage, """ +
          """"Unknown reference to element '" + inferenceInput("reference_value", """ +
          """referenceValue) + "'")""",
        // Required target is checked only
        """requiredMessage = inferRequired("required_message", requiredMessage, """ +
          """"ref is " + inferenceInput("reference_value", referenceValue))""",
        // A required reference needs no unwrapping
        """fromRequired = inferOptional("from_required", fromRequired, """ +
          """requiredSource + " / " + requiredSource)""",
        // A literal-only expression is emitted as a plain string
        """literalOnly = inferOptional("literal_only", literalOnly, "no substitutions here")""",
        // `{{`/`}}` are unescaped, and quotes are escaped for the Scala literal
        """withEscapes = inferOptional("with_escapes", withEscapes, """ +
          """"braces {like this} and a \"quote\"")""",
      ).foreach { snippet =>
        code should include(snippet)
      }

      Seq(
        // Slots without an expression are not inferred
        "noExpression = infer",
        // Out of scope: only single-valued string slots are inferred
        "multivaluedIgnored = infer",
        "integerIgnored = infer",
      ).foreach { snippet =>
        code should not include snippet
      }
    }

    "generate an infer() method reaching into inlined classes" in {
      val code = ScalaGenerator(using ModelCatalogue.equalsExpression.model)
        .generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        // Every optional link along the path is unwrapped, the outermost one last
        """fromOptionalNested = inferOptional("from_optional_nested", fromOptionalNested, """ +
          """inferenceInput("optional_nested.leaf", """ +
          """inferenceInput("optional_nested", optionalNested).leaf))""",
        // A required link needs no unwrapping, an optional one further down still does
        """fromRequiredNested = inferOptional("from_required_nested", fromRequiredNested, """ +
          """requiredNested.requiredLeaf + " at " + """ +
          """inferenceInput("required_nested.deeper", requiredNested.deeper).bottom)""",
      ).foreach { snippet =>
        code should include(snippet)
      }
    }

    "generate an infer() method that stringifies non-string ranges" in {
      val code = ScalaGenerator(using ModelCatalogue.equalsExpression.model)
        .generate(testPkg).toMap.apply("SomeClass.scala")
      Seq(
        """fromNumbers = inferOptional("from_numbers", fromNumbers, """ +
          """stringify(inferenceInput("count", count)) + " items, ratio " + """ +
          """stringify(inferenceInput("ratio", ratio)) + ", flag " + stringify(flag) + """ +
          """", on " + stringify(inferenceInput("created", created)))""",
        // A required URI needs no unwrapping, optional CURIEs still do
        """fromUris = inferOptional("from_uris", fromUris, stringify(homepage) + " | " + """ +
          """stringify(inferenceInput("term", term)) + " | " + """ +
          """stringify(inferenceInput("either", either)))""",
        // Multivalued slots are never Option-wrapped, so they go straight to `stringify`
        """fromMultivalued = inferOptional("from_multivalued", fromMultivalued, """ +
          """"tags: [" + stringify(tags) + "], codes: [" + stringify(codes) + "]")""",
        // References stringify to the identifier they hold
        """fromReferences = inferOptional("from_references", fromReferences, """ +
          """stringify(inferenceInput("target", target)) + " <- " + stringify(targets))""",
      ).foreach { snippet =>
        code should include(snippet)
      }

      // A plain string is already a string, so it is not wrapped
      code should include(""""ref is " + inferenceInput("reference_value", referenceValue)""")
    }

    "refuse to substitute an inlined class into an expression" in {
      val input =
        s"""$schemaShared
           |classes:
           |  Inner:
           |    attributes:
           |      a:
           |        range: string
           |  SomeClass:
           |    attributes:
           |      inner:
           |        range: Inner
           |        inlined: true
           |      message:
           |        range: string
           |        equals_expression: "{inner}"
           |""".stripMargin
      val error = intercept[RuntimeException] {
        ScalaGenerator(using decode(input)).generate(testPkg).toMap
      }
      error.getMessage should include("SomeClass.message")
      error.getMessage should include("inner")
      error.getMessage should include("inlined class")
    }

    "substitute a static enum, which stringifies to its LinkML text" in {
      val code = ScalaGenerator(using ModelCatalogue.equalsExpression.model)
        .generate(testPkg).toMap
      // The instance is derived from the sealed hierarchy, reading the `@named` text
      code("StatusEnum.scala") should include("sealed abstract class StatusEnum derives Stringify")
      code("StatusEnum.scala") should include("""@named("in progress") case object InProgress""")
      code("SomeClass.scala") should include(
        """fromEnum = inferOptional("from_enum", fromEnum, """ +
          """stringify(inferenceInput("status", status)) + " of (" + stringify(statuses) + ")")""",
      )
    }

    "not derive Stringify for a non-sealed enum" in {
      val input =
        s"""$schemaShared
           |enums:
           |  SomeEnum:
           |    abstract: true
           |    permissible_values:
           |      a:
           |""".stripMargin
      val code = ScalaGenerator(using decode(input)).generate(testPkg).toMap
        .apply("SomeEnum.scala")
      code should include("abstract class SomeEnum")
      code should not include "derives Stringify"
    }

    "substitute a dynamic enum, which is generated as a plain string" in {
      val input =
        s"""$schemaShared
           |enums:
           |  SomeEnum: {}
           |classes:
           |  SomeClass:
           |    attributes:
           |      choice:
           |        range: SomeEnum
           |      message:
           |        range: string
           |        equals_expression: "{choice}"
           |""".stripMargin
      val code = ScalaGenerator(using decode(input)).generate(testPkg).toMap
        .apply("SomeClass.scala")
      code should include("choice: Option[String]")
      code should include("""inferenceInput("choice", choice)""")
    }

    "generate an infer() method that does nothing when there are no expressions" in {
      val code = ScalaGenerator(using ModelCatalogue.basic.model)
        .generate(testPkg).toMap.apply("SomeClass.scala")
      code should include("def infer(): SomeClassImpl")
      code should include("def infer(): SomeClass\n")
      code should not include "inferOptional"
    }

    "generate ifabsent default values for enum-ranged slots" in {
      val files = ScalaGenerator(using ModelCatalogue.ifabsent.enums.model).generate(testPkg).toMap
      val code = files("SomeClass.scala")
      Seq(
        "someSlot: Option[SomeEnum] = Some(SomeEnum.SomeOption)",
        "someOtherSlot: Option[SomeEnum] = Some(SomeEnum.SomeOtherOption)",
        "yetAnotherSlot: Option[SomeEnum] = Some(SomeEnum.YetAnotherOption)",
        // Permissible values that aren't valid Scala identifiers are re-cased in the default, too
        "withSpaces: Option[SomeEnum] = Some(SomeEnum.OptionWithSpaces)",
        // No ifabsent metaslot -> no default value
        "noIfabsent: Option[SomeEnum] = None",
      ).foreach { snippet =>
        code should include(snippet)
      }

      Seq(
        // The raw permissible value text must not leak into the default value
        "SomeEnum.SOME_OPTION",
        "option with spaces",
      ).foreach { snippet =>
        code should not include snippet
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

    "generate Linkml Date and/or Time for dates" in {
      given SchemaView = ModelCatalogue.typed.model

      val code = ScalaGenerator().generate(testPkg).toMap.apply("Typed.scala")
      code should include("dateSlot: LinkmlDate")
    }

    "generate type aliases" in {
      given SchemaView = ModelCatalogue.typed.model

      val files = ScalaGenerator().generate(testPkg).toMap
      files("Typed.scala") should include("customSlot: Custom")
      files("Custom.scala") should include("type Custom = String")
    }

    "not generate aliases for primitive types" in {
      given SchemaView = ModelCatalogue.basic.model

      val files = ScalaGenerator().generate(testPkg).toMap
      files.keys should contain theSameElementsAs Seq(
        "SomeClass.scala",
      )
    }

    "generate an external type reference if unknown base is used" in {
      given SchemaView = ModelCatalogue.externalType.model

      val files = ScalaGenerator().generate(testPkg).toMap
      files.keys should contain theSameElementsAs Seq(
        "SomeClass.scala",
        "ExtType.scala",
        "UnknownType.scala",
      )
      files("SomeClass.scala") should include("someSlot: ExtType")
      files("SomeClass.scala") should include("someOtherSlot: UnknownType")
      files("ExtType.scala") should include("type ExtType = SomeExternalType")
      files("UnknownType.scala") should include("type UnknownType = Unknown")
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
