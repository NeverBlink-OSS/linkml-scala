package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class ValidateSpec extends AnyWordSpec, Matchers {

  /** Write [[yaml]] to a temporary `.yaml` file and pass its path to test. */
  private def withSchema(yaml: String)(test: String => Unit): Unit =
    withSchemas(yaml)(paths => test(paths.head))

  /** Write each of yamls to its own temporary `.yaml` file, in order, and pass the paths. */
  private def withSchemas(yamls: String*)(test: Seq[String] => Unit): Unit = {
    val files = yamls.map { yaml =>
      val file = Files.createTempFile("linkml-validate", ".yaml")
      Files.writeString(file, yaml)
      file
    }
    try test(files.map(_.toString))
    finally files.foreach(Files.deleteIfExists)
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

    "given several input files" should {
      "check every one of them, not just the first" in {
        withSchemas(validSchema, schemaWithIssues) { paths =>
          val (out, _, code) =
            Validate.runTestCommandWithExitCode(List("validate", "--format", "plain") ++ paths)

          // The problems live in the *second* file: finding them proves it wasn't skipped.
          out should include("ERROR: Invalid URI or CURIE 'not a curie!' in class 'SomeClass'")
          code shouldBe 1
        }
      }

      "label each report with its file name" in {
        withSchemas(validSchema, schemaWithIssues) { paths =>
          val (out, _) =
            Validate.runTestCommand(List("validate", "--format", "plain") ++ paths)

          out should include(s"# ${paths(0)}")
          out should include(s"# ${paths(1)}")
          // ...and the per-file verdicts are still there, one per schema.
          out should include("Schema is valid.")
          out should include("1 error, 1 warning")
        }
      }

      "close with a combined summary" in {
        withSchemas(validSchema, schemaWithIssues, validSchema) { paths =>
          val (out, _) =
            Validate.runTestCommand(List("validate", "--format", "plain") ++ paths)
          out should include("# 3 schemas checked, 1 with issues: 1 error, 1 warning")
        }
      }

      "succeed (exit 0) when all of them are valid" in {
        withSchemas(validSchema, validSchema) { paths =>
          val (out, _, code) =
            Validate.runTestCommandWithExitCode(
              List("validate", "--strict", "--format", "plain") ++ paths,
            )
          out should include("# 2 schemas checked, no issues.")
          code shouldBe 0
        }
      }

      "name every schema in the terminal report" in {
        withSchemas(validSchema, schemaWithIssues) { paths =>
          val (out, _) = Validate.runTestCommand(List("validate") ++ paths)
          out should include(s"Validating ${paths(0)}")
          out should include(s"Validating ${paths(1)}")
          out should include("2 schemas checked, 1 with issues: 1 error, 1 warning")
        }
      }

      "report an unreadable file as fatal and still check the rest" in {
        withSchemas(validSchema) { paths =>
          val missing = paths.head + ".does-not-exist"
          val (out, _, code) =
            Validate.runTestCommandWithExitCode(
              List("validate", "--format", "plain", missing) ++ paths,
            )
          out should include("FATAL:")
          out should include(s"# ${paths.head}")
          out should include("Schema is valid.")
          code shouldBe 1
        }
      }
    }

    "given no input file" should {
      "fail with a helpful message" in {
        val (_, err, code) = Validate.runTestCommandWithExitCode(List("validate"))
        err should include("At least one input file is required.")
        code shouldBe 1
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

    "invoked as `lint`" should {
      "behave exactly like `validate`" in {
        withSchemas(validSchema, schemaWithIssues) { paths =>
          val asValidate =
            Validate.runTestCommandWithExitCode(List("validate", "--format", "plain") ++ paths)
          val asLint =
            Validate.runTestCommandWithExitCode(List("lint", "--format", "plain") ++ paths)
          asLint shouldBe asValidate
        }
      }

      "be listed alongside `validate` in the CLI help" in {
        val (out, _, _) = Validate.runTestCommandWithExitCode(List("--help"))
        // The command list is colored, so drop the escape codes before matching.
        out.replaceAll(s"$Esc\\[[0-9;]*m", "") should include("validate, lint")
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
