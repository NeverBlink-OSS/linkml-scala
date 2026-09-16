package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TypeDerivationSpec extends AnyWordSpec, Matchers {
  // Test the inheritance through SchemaView
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
  }
}
