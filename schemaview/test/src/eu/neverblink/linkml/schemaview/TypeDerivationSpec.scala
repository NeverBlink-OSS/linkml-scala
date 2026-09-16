package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TypeDerivationSpec extends AnyWordSpec, Matchers {
  private def load(yaml: String): SchemaView =
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))

  "Type derivation" should {
    val sv = SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        """id: https://example.org/type-derivation
          |name: type_derivation
          |imports:
          |  - linkml:types
          |prefixes:
          |  xsd: http://www.w3.org/2001/XMLSchema#
          |types:
          |  base64Binary:
          |    typeof: string
          |    uri: xsd:base64Binary
          |  TextChild:
          |    typeof: base64Binary
          |  TextGrandchild:
          |    typeof: TextChild
          |  IntegerOverride:
          |    typeof: TextGrandchild
          |    base: int
          |  InheritedInteger:
          |    typeof: IntegerOverride
          |  ExternalOverride:
          |    typeof: TextGrandchild
          |    base: SomeExternalType
          |  InheritedExternal:
          |    typeof: ExternalOverride
          |""".stripMargin,
      ),
    )

    "inherit the runtime type through typeof while preserving an explicit URI" in {
      val parent = sv.types("string")
      val child = sv.types("base64Binary")

      parent.runtimeType shouldBe StringType
      child.uriStr shouldBe "http://www.w3.org/2001/XMLSchema#base64Binary"
      child.runtimeType shouldBe StringType
      child.derivedType.base shouldBe Some("str")
      child.inner.base shouldBe None
    }

    "inherit a base through multiple typeof levels" in {
      val child = sv.types("TextGrandchild")

      child.derivedType.base shouldBe Some("str")
      child.runtimeType shouldBe StringType
      child.inner.base shouldBe None
    }

    "prefer the nearest explicit base over more distant ancestors" in {
      Seq("IntegerOverride", "InheritedInteger").foreach { name =>
        withClue(s"$name: ") {
          val child = sv.types(name)

          child.derivedType.base shouldBe Some("int")
          child.runtimeType shouldBe IntegerType
        }
      }
    }
    "preserve an explicit external base through inheritance" in {
      Seq("ExternalOverride", "InheritedExternal").foreach { name =>
        withClue(s"$name: ") {
          val child = sv.types(name)

          child.derivedType.base shouldBe Some("SomeExternalType")
          child.runtimeType shouldBe UnknownType
        }
      }
    }
    "inherit an explicitly declared URI through typeof" in {
      val expectedUri = "http://www.w3.org/2001/XMLSchema#base64Binary"

      Seq("TextChild", "TextGrandchild").foreach { name =>
        withClue(s"$name: ") {
          val child = sv.types(name)

          child.uriStr shouldBe expectedUri
          child.derivedType.typeUri
            .map(_.uri(using child.definingPrefixResolver)) shouldBe Some(expectedUri)
          child.inner.typeUri shouldBe None
        }
      }
    }
    "resolve inherited URIs using the declaring schema's prefixes" in {
      val importer = MapImporter(
        "main.yaml" ->
          """id: https://example.org/child-schema
            |name: child_schema
            |imports:
            |  - parent
            |prefixes:
            |  ex: https://example.org/child/
            |types:
            |  Child:
            |    typeof: Parent
            |  Grandchild:
            |    typeof: Child
            |  LocalOverride:
            |    typeof: Parent
            |    uri: ex:Code
            |""".stripMargin,
        "parent.yaml" ->
          """id: https://example.org/parent-schema
            |name: parent_schema
            |imports:
            |  - linkml:types
            |prefixes:
            |  ex: https://example.org/parent/
            |types:
            |  Parent:
            |    typeof: string
            |    uri: ex:Code
            |""".stripMargin,
      )

      val importedView = SchemaIssues.orThrow(
        SchemaView.loadSchemaViewFromUri("main.yaml", importer),
      )
      val inheritedUri = "https://example.org/parent/Code"

      importedView.types("Parent").uriStr shouldBe inheritedUri

      Seq("Child", "Grandchild").foreach { name =>
        withClue(s"$name: ") {
          val child = importedView.types(name)

          child.uriStr shouldBe inheritedUri
          child.derivedType.typeUri
            .map(_.uri(using child.definingPrefixResolver)) shouldBe Some(inheritedUri)
          child.inner.typeUri shouldBe None
        }
      }

      importedView.types("LocalOverride").uriStr shouldBe
        "https://example.org/child/Code"
    }

    "recognize double using the effective expanded datatype URI" in {
      val view = load(
        """id: https://example.org/numeric-types
          |name: numeric_types
          |imports: [linkml:types]
          |prefixes:
          |  xs: http://www.w3.org/2001/XMLSchema#
          |types:
          |  DoubleChild:
          |    typeof: double
          |  FloatChild:
          |    typeof: float
          |  AliasedDouble:
          |    base: float
          |    uri: xs:double
          |  FullDouble:
          |    base: float
          |    uri: http://www.w3.org/2001/XMLSchema#double
          |""".stripMargin,
      )
      Seq("double", "DoubleChild", "AliasedDouble", "FullDouble").foreach { name =>
        withClue(s"$name: ")(view.types(name).runtimeType shouldBe DoubleType)
      }
      view.types("FloatChild").runtimeType shouldBe FloatType
    }

    "keep the child's synthetic URI when no ancestor declares a URI" in {
      val view = load(
        """id: https://example.org/types/
          |name: types
          |types:
          |  parent:
          |    base: str
          |  child:
          |    typeof: parent
          |""".stripMargin,
      )
      view.types("child").derivedType.typeUri shouldBe None
      view.types("child").uriStr shouldBe "https://example.org/types/child"
    }

    for (parent, expectedPath) <- Seq(
        "A" -> "A -> A",
        "B" -> "A -> B -> A",
      )
    do
      s"reject a typeof cycle through $expectedPath" in {
        val view = load(
          s"""id: https://example.org/cyclic-types
             |name: cyclic_types
             |types:
             |  A:
             |    typeof: $parent
             |  B:
             |    typeof: A
             |""".stripMargin,
        )
        intercept[IllegalArgumentException] {
          view.types("A").derivedType
        }.getMessage shouldBe s"Cyclic typeof inheritance: $expectedPath"
      }

    "report an unknown typeof parent at its declaration" in {
      val error = intercept[SchemaIssues.FatalSchemaException] {
        load(
          """id: https://example.org/missing-parent
            |name: missing_parent
            |types:
            |  Child:
            |    typeof: Missing
            |""".stripMargin,
        )
      }
      error.getMessage should include("Unknown reference 'Missing' at /types/Child/typeof/")
    }
  }

  "Type unions" should {
    "resolve members independently without treating them as typeof parents" in {
      val view = load(
        """id: https://example.org/type-unions
          |name: type_unions
          |imports: [linkml:types]
          |types:
          |  Text:
          |    typeof: string
          |    pattern: '^[A-Z]+$'
          |  Count:
          |    typeof: integer
          |    minimum_value: 0
          |  Choice:
          |    union_of: [Count, Text]
          |  ChoiceChild:
          |    typeof: Choice
          |""".stripMargin,
      )
      val choice = view.types("Choice")
      choice.unionMembers.map(_.name) shouldBe Seq("Count", "Text")
      choice.unionMembers.map(_.runtimeType) shouldBe Seq(IntegerType, StringType)
      choice.unionMembers.head.derivedType.minimumValue.map(_.value.trim) shouldBe Some("0")
      choice.unionMembers(1).derivedType.pattern shouldBe Some("^[A-Z]+$")
      choice.parents shouldBe empty
      choice.derivedType.unionOf.map(_.value) shouldBe Seq("Count", "Text")
      choice.derivedType.base shouldBe None
      choice.derivedType.typeUri shouldBe None
      choice.derivedType.pattern shouldBe None
      choice.derivedType.minimumValue shouldBe None
      choice.runtimeType shouldBe UnknownType

      val child = view.types("ChoiceChild")
      child.parents.map(_.name) shouldBe Seq("Choice")
      child.unionMembers shouldBe empty
      child.derivedType.unionOf shouldBe empty
      child.derivedType.typeof.map(_.value) shouldBe Some("Choice")
      child.runtimeType shouldBe UnknownType
    }

    "report an unknown union member at its declaration" in {
      val error = intercept[SchemaIssues.FatalSchemaException] {
        load(
          """id: https://example.org/missing-member
            |name: missing_member
            |types:
            |  Choice:
            |    union_of: [Missing]
            |""".stripMargin,
        )
      }
      error.getMessage should include("Unknown reference 'Missing' at /types/Choice/union_of/0/")
    }
  }

  "Inherited type properties" should {
    val view = load(
      """id: https://example.org/type-constraints
        |name: type_constraints
        |imports: [linkml:types]
        |prefixes:
        |  ex: https://example.org/
        |types:
        |  Text:
        |    typeof: string
        |    repr: str
        |    pattern: '^[A-Z]+$'
        |    structured_pattern:
        |      syntax: '[A-Z]+'
        |    equals_string: ACTIVE
        |    equals_string_in: [ACTIVE, INACTIVE]
        |    implicit_prefix: ex
        |    description: Parent metadata
        |  TextChild:
        |    typeof: Text
        |  TextOverride:
        |    typeof: TextChild
        |    repr: CustomText
        |    pattern: '^NEW$'
        |    structured_pattern:
        |      syntax: NEW
        |    equals_string: NEW
        |    equals_string_in: [NEW]
        |  Number:
        |    typeof: integer
        |    equals_number: 7
        |    minimum_value: -10
        |    maximum_value: 100
        |  NumberChild:
        |    typeof: Number
        |  Zero:
        |    typeof: NumberChild
        |    equals_number: 0
        |    minimum_value: 0
        |    maximum_value: 0
        |  ZeroChild:
        |    typeof: Zero
        |classes:
        |  Record:
        |    attributes:
        |      text:
        |        range: TextChild
        |      number:
        |        range: NumberChild
        |""".stripMargin,
    )

    "inherit representation and constraints without inheriting unrelated metadata" in {
      val text = view.types("TextChild").derivedType
      text.repr shouldBe Some("str")
      text.pattern shouldBe Some("^[A-Z]+$")
      text.structuredPattern.flatMap(_.syntax) shouldBe Some("[A-Z]+")
      text.equalsString shouldBe Some("ACTIVE")
      text.equalsStringIn shouldBe Seq("ACTIVE", "INACTIVE")
      text.description shouldBe None
      text.implicitPrefix shouldBe None
      view.types("TextChild").inner.pattern shouldBe None

      val number = view.types("NumberChild").derivedType
      number.equalsNumber shouldBe Some(7)
      number.minimumValue.map(_.value.trim) shouldBe Some("-10")
      number.maximumValue.map(_.value.trim) shouldBe Some("100")
    }

    "preserve explicit overrides including zero and replacement value lists" in {
      val text = view.types("TextOverride").derivedType
      text.repr shouldBe Some("CustomText")
      text.pattern shouldBe Some("^NEW$")
      text.structuredPattern.flatMap(_.syntax) shouldBe Some("NEW")
      text.equalsString shouldBe Some("NEW")
      text.equalsStringIn shouldBe Seq("NEW")

      val number = view.types("ZeroChild").derivedType
      number.equalsNumber shouldBe Some(0)
      number.minimumValue.map(_.value.trim) shouldBe Some("0")
      number.maximumValue.map(_.value.trim) shouldBe Some("0")
    }

    "expose inherited constraints to slots using a derived type" in {
      val attributes = view.classes("Record").attributeViews
      val text = attributes("text").asInstanceOf[TypeAttributeView]
      text.pattern shouldBe Some("^[A-Z]+$")
      text.structuredPattern.flatMap(_.syntax) shouldBe Some("[A-Z]+")
      text.equalsString shouldBe Some("ACTIVE")
      text.equalsStringIn shouldBe Seq("ACTIVE", "INACTIVE")

      val number = attributes("number").asInstanceOf[TypeAttributeView]
      number.equalsNumber shouldBe Some(7)
      number.minimumValue.map(_.value.trim) shouldBe Some("-10")
      number.maximumValue.map(_.value.trim) shouldBe Some("100")
    }
  }
}
