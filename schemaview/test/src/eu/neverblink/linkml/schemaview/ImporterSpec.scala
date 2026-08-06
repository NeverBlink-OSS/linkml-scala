package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.validation.{SchemaImportError, SchemaParseError}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ImporterSpec extends AnyWordSpec, Matchers, Inside {

  private val validSchema =
    """id: https://neverblink.eu/linkml/importer/test/
      |name: test
      |""".stripMargin

  "Importer.parseSchema" should {
    "return the schema for parseable YAML" in {
      FileSystemImporter.parseSchema(validSchema).map(_.name) shouldBe Right("test")
    }

    "return a SchemaParseError for unparseable YAML" in {
      val broken =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes:
          |  SomeClass:
          |    slots: [a, b
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(broken, "some/uri.yaml")) { case Left(issue) =>
        issue shouldBe a[SchemaParseError]
        issue.sourceUri shouldBe "some/uri.yaml"
        issue.parserMessage should not be empty
        issue.message shouldBe None
        SchemaIssues.description(issue.infer()) should startWith("Cannot parse schema:")
        SchemaIssues.verbose(issue.infer()) should include("some/uri.yaml")
      }
    }

    "pin a YAML syntax error to its 1-based position" in {
      // The unclosed flow sequence is on line 5, so the parser fails at the line below it.
      val broken =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes:
          |  SomeClass:
          |    slots: [a, b
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(broken)) { case Left(issue) =>
        inside(issue.location.codeRegion) { case Some(region) =>
          region.startLine shouldBe 6
          region.startColumn shouldBe 1
          // The offending token is a zero-width block end, so the parser reports no end position.
          region.endLine shouldBe None
          region.endColumn shouldBe None
        }
      }
    }

    "pin a decoding error to the position of the offending node" in {
      // `classes` must be a mapping, and the bad value sits on line 3, column 10.
      val notASchema =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes: not-a-mapping
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(notASchema)) { case Left(issue) =>
        inside(issue.location.codeRegion) { case Some(region) =>
          region.startLine shouldBe 3
          region.startColumn shouldBe 10
          // `end_column` is exclusive, so a node filling the rest of line 3 ends at line 4 column 1.
          region.endLine shouldBe Some(4)
          region.endColumn shouldBe Some(1)
        }
      }
    }

    "leave the code region empty when the parser reports no position" in {
      inside(FileSystemImporter.parseSchema("foo: *undefined_anchor")) { case Left(issue) =>
        issue.location.codeRegion shouldBe None
      }
    }

    "return a SchemaParseError when the YAML parses but does not decode" in {
      val notASchema =
        """id: https://neverblink.eu/linkml/importer/test/
          |name: test
          |classes: not-a-mapping
          |""".stripMargin

      inside(FileSystemImporter.parseSchema(notASchema)) { case Left(issue) =>
        issue shouldBe a[SchemaParseError]
        issue.parserMessage should not be empty
      }
    }
  }

  "Importer.readSchema" should {
    "return a SchemaImportError when the schema text cannot be read" in {
      inside(FileSystemImporter.readSchema("/does/not/exist.yaml")) { case Left(issue) =>
        issue shouldBe a[SchemaImportError]
        SchemaIssues.description(issue.asInstanceOf[SchemaImportError].infer()) should
          include("/does/not/exist.yaml")
      }
    }

    "return a SchemaParseError when the text is readable but unparseable" in {
      val importer = MapImporter("broken.yaml" -> "classes: [a, b")
      inside(importer.readSchema("broken.yaml")) { case Left(issue) =>
        issue shouldBe a[SchemaParseError]
      }
    }

    "return the schema when the text is readable and parseable" in {
      val importer = MapImporter("ok.yaml" -> validSchema)
      importer.readSchema("ok.yaml").map(_.name) shouldBe Right("test")
    }
  }

  "SchemaView loading" should {
    "raise a FatalSchemaException carrying the parse issue" in {
      val error = intercept[SchemaIssues.FatalSchemaException] {
        SchemaView.loadSchemaViewFromString("classes: [a, b")
      }
      error.problems should have size 1
      error.problems.head shouldBe a[SchemaParseError]
      error.getMessage should startWith("Fatal validation problems:")
    }

    "raise a FatalSchemaException carrying the import issue" in {
      val importer = MapImporter(
        "main.yaml" ->
          """id: https://neverblink.eu/linkml/importer/test/
            |name: test
            |imports:
            |  - i_do_not_exist
            |""".stripMargin,
      )
      val error = intercept[SchemaIssues.FatalSchemaException] {
        SchemaView.loadSchemaViewFromUri("main.yaml", importer = importer)
      }
      error.problems.head shouldBe a[SchemaImportError]
      error.getMessage should include("i_do_not_exist")
    }
  }
}
