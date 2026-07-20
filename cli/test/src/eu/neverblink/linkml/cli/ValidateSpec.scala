package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class ValidateSpec extends AnyWordSpec, Matchers {

  /** Write [[yaml]] to a temporary `.yaml` file and pass its path to [[test]]. */
  private def withSchema(yaml: String)(test: String => Unit): Unit = {
    val file = Files.createTempFile("linkml-validate", ".yaml")
    Files.writeString(file, yaml)
    try test(file.toString)
    finally Files.deleteIfExists(file)
  }

  // Loads cleanly (no fatal problems) but has one error (invalid class_uri) and one
  // warning (no tree_root). default_range is set so there's no "default_range" warning.
  private val schemaWithIssues =
    """id: https://neverblink.eu/test/
      |name: test
      |default_range: string
      |types:
      |  string:
      |classes:
      |  SomeClass:
      |    class_uri: "not a curie!"
      |""".stripMargin

  private val validSchema =
    """id: https://neverblink.eu/test/
      |name: test
      |default_range: string
      |types:
      |  string:
      |classes:
      |  Root:
      |    tree_root: true
      |""".stripMargin

  private val Esc = '\u001b'

  "the validate command" when {
    "the schema has problems" should {
      "render a colored terminal report with severities and a summary by default" in {
        withSchema(schemaWithIssues) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", path))

          out should include(Esc.toString) // colored
          out should include("ERROR")
          out should include("WARNING")
          out should include("✖")
          out should include("⚠")
          out should include("Invalid URI or CURIE 'not a curie!' in class 'SomeClass'")
          out should include("No 'tree_root' class is defined in the schema")
          // per-severity summary
          out should include("1 error, 1 warning")
        }
      }

      "render a plain, uncolored report for --format plain" in {
        withSchema(schemaWithIssues) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "plain", path))

          out should not include Esc.toString // no color codes
          out should include("ERROR: Invalid URI or CURIE 'not a curie!' in class 'SomeClass'")
          out should include("WARNING: No 'tree_root' class is defined in the schema")
          out should include("1 error, 1 warning")
        }
      }

      "not print the ugly Uri(...) wrapper for the defining schema id" in {
        withSchema(schemaWithIssues) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "plain", path))
          out should include("imported from schema 'https://neverblink.eu/test/'")
          out should not include "Uri("
        }
      }
    }

    "run against a warnings-only schema" should {
      // No default_range and no tree_root => two warnings, no errors.
      val warningsOnly =
        """id: https://neverblink.eu/test/
          |name: test
          |""".stripMargin

      "succeed (exit 0) by default" in {
        withSchema(warningsOnly) { path =>
          val (out, _, code) =
            Validate.runTestCommandWithExitCode(List("validate", "--format", "plain", path))
          out should include("WARNING:")
          code shouldBe 0
        }
      }

      "fail (exit 1) with --strict" in {
        withSchema(warningsOnly) { path =>
          val (out, _, code) =
            Validate.runTestCommandWithExitCode(
              List("validate", "--strict", "--format", "plain", path),
            )
          out should include("WARNING:")
          code shouldBe 1
        }
      }
    }

    "run against a schema with errors" should {
      "fail (exit 1) regardless of --strict" in {
        withSchema(schemaWithIssues) { path =>
          val (_, _, plain) =
            Validate.runTestCommandWithExitCode(List("validate", "--format", "plain", path))
          plain shouldBe 1
          val (_, _, strict) =
            Validate.runTestCommandWithExitCode(
              List("validate", "--strict", "--format", "plain", path),
            )
          strict shouldBe 1
        }
      }
    }

    "the schema is valid" should {
      "succeed (exit 0) even with --strict" in {
        withSchema(validSchema) { path =>
          val (out, _, code) =
            Validate.runTestCommandWithExitCode(
              List("validate", "--strict", "--format", "plain", path),
            )
          out.trim shouldBe "Schema is valid."
          code shouldBe 0
        }
      }

      "report success with a green check in the terminal format" in {
        withSchema(validSchema) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", path))
          out should include("✔")
          out should include("Schema is valid.")
          out should include(Esc.toString)
        }
      }

      "report success as plain text for --format plain" in {
        withSchema(validSchema) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "plain", path))
          out.trim shouldBe "Schema is valid."
          out should not include Esc.toString
        }
      }
    }

    "given an unknown format" should {
      "fail with a helpful message" in {
        withSchema(validSchema) { path =>
          val (_, err) = Validate.runTestCommand(List("validate", "--format", "xml", path))
          err should include("Unknown format 'xml'")
          err should include("terminal|plain")
        }
      }
    }

    "given a schema with fatal problems" should {
      "report them as fatal issues" in {
        // An unknown slot reference is a fatal problem: the SchemaView can't even be built.
        val fatalSchema =
          """id: https://neverblink.eu/test/
            |name: test
            |classes:
            |  SomeClass:
            |    slots:
            |    - nope
            |""".stripMargin
        withSchema(fatalSchema) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "plain", path))
          out should include("FATAL:")
          out should include("nope")
          out should include("1 fatal error")
        }
      }
    }
  }
}
