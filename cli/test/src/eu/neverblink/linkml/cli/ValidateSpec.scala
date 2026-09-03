package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}

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

  /** Write each `name -> yaml` pair into one temporary directory, so that relative `imports:`
    * between them resolve, and pass that directory to the test.
    */
  private def withSchemaDir(files: (String, String)*)(test: Path => Unit): Unit = {
    val dir = Files.createTempDirectory("linkml-validate")
    try {
      files.foreach((name, yaml) => Files.writeString(dir.resolve(name), yaml))
      test(dir)
    } finally {
      files.foreach((name, _) => Files.deleteIfExists(dir.resolve(name)))
      Files.deleteIfExists(dir)
    }
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

      "serialize a SchemaValidationReport for --format json" in {
        withSchema(schemaWithIssues) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "json", path))

          // One report object per input file, wrapped in an array.
          out.trim should startWith("[")
          out.trim should endWith("]")
          out should include("\"issues\"")
          out should include(s"\"validation_run_id\": \"$path\"")
          out should include("\"severity\": \"ERROR\"")
          out should include("\"severity\": \"WARNING\"")
          // The inferred messages are included, keyed by their LinkML slot names.
          out should include("\"message\"")
          out should include("Invalid URI or CURIE 'not a curie!' in class 'SomeClass'")
          out should include("No 'tree_root' class is defined in the schema")
          // Structured fields, not just prose.
          out should include("\"element_name\": \"SomeClass\"")
          out should include("\"element_type\": \"class\"")
          out should include("\"schema_id\": \"https://neverblink.eu/test/\"")
          // The type designator names the concrete issue type.
          out should include("\"issue_type\": \"InvalidUriOrCurie\"")
          out should include("\"issue_type\": \"NoTreeRootClass\"")
          // No display chrome
          out should not include "ERROR:"
          out should not include "1 error, 1 warning"
        }
      }

      "emit one JSON report per input file, tagged with the name the user gave" in {
        withSchemas(schemaWithIssues, validSchema) { paths =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "json") ++ paths)
          // JSON is valid YAML, so reuse the parser already on the classpath.
          import org.virtuslab.yaml.Node
          val reports = org.virtuslab.yaml.parseYaml(out).toOption.get
            .asInstanceOf[Node.SequenceNode].nodes.map(_.asInstanceOf[Node.MappingNode])

          def slot(report: Node.MappingNode, name: String): Option[Node] =
            report.mappings.collectFirst {
              case (k: Node.ScalarNode, v) if k.value == name => v
            }

          reports should have size 2
          // The run id is the input name verbatim, in input order.
          reports.map(r => slot(r, "validation_run_id").map(_.asInstanceOf[Node.ScalarNode].value))
            .shouldBe(paths.map(Some(_)))
          // First file has the issues, second is clean.
          slot(reports.head, "issues").get
            .asInstanceOf[Node.SequenceNode].nodes should not be empty
          slot(reports(1), "issues").get.asInstanceOf[Node.SequenceNode].nodes shouldBe empty
        }
      }

      "still exit non-zero for --format json" in {
        withSchema(schemaWithIssues) { path =>
          val (_, _, code) =
            Validate.runTestCommandWithExitCode(List("validate", "--format", "json", path))
          code shouldBe 1
        }
      }

      "not print the ugly Uri(...) wrapper for the defining schema id" in {
        withSchema(schemaWithIssues) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "plain", path))
          out should include("imported from schema 'https://neverblink.eu/test/'")
          out should not include "Uri("
        }
      }

      "label the issues with the schema id even when there is only one schema" in {
        withSchema(schemaWithIssues) { path =>
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "plain", path))
          out should include("## https://neverblink.eu/test/")
        }
      }
    }

    "an issue cannot be attributed to any schema" should {
      "report it without a schema header" in {
        withSchemas(validSchema) { paths =>
          // The file cannot be read at all, so the import failure carries no schema id.
          val missing = paths.head + ".does-not-exist"
          val (out, _) =
            Validate.runTestCommand(List("validate", "--format", "plain", missing))
          out should include("FATAL:")
          out should not include "##"
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

    "given a schema with imports" should {
      // Both schemas have an invalid class_uri, and the root one also has no tree_root, so
      // there is something to report against each of them.
      val mainWithIssues =
        """id: urn:main
          |name: main
          |default_range: string
          |imports:
          |  - imported
          |types:
          |  string:
          |classes:
          |  MainClass:
          |    class_uri: "not a curie!"
          |""".stripMargin

      val importedWithIssues =
        """id: urn:imported
          |name: imported
          |classes:
          |  ImportedClass:
          |    class_uri: "also not a curie!"
          |""".stripMargin

      "group the issues under the id of the schema each one came from" in {
        withSchemaDir("main.yaml" -> mainWithIssues, "imported.yaml" -> importedWithIssues) { dir =>
          val path = dir.resolve("main.yaml").toString
          val (out, _) = Validate.runTestCommand(List("validate", "--format", "plain", path))

          out should include("## urn:main")
          out should include("## urn:imported")

          // The lines under one `## <schema id>` header, up to the next one.
          def group(schemaId: String): String =
            out.split("## ").find(_.startsWith(schemaId)).getOrElse(fail(s"no $schemaId group"))

          group("urn:main") should include(
            "ERROR: Invalid URI or CURIE 'not a curie!' in class 'MainClass'",
          )
          group("urn:main") should include("WARNING: No 'tree_root' class is defined")
          group("urn:main") should not include "ImportedClass"
          group("urn:imported") should include(
            "ERROR: Invalid URI or CURIE 'also not a curie!' in class 'ImportedClass'",
          )
          group("urn:imported") should not include "MainClass"

          // Still one combined summary for the file, not one per group.
          out should include("2 errors, 1 warning")
        }
      }

      "name both schemas in the terminal report" in {
        withSchemaDir("main.yaml" -> mainWithIssues, "imported.yaml" -> importedWithIssues) { dir =>
          val path = dir.resolve("main.yaml").toString
          val (out, _) = Validate.runTestCommand(List("validate", path))

          out should include(s"Validating $path")
          out should include("urn:main")
          out should include("urn:imported")
          out should include("2 errors, 1 warning")
        }
      }

      "attribute a fatal problem to the imported schema it came from" in {
        // The bad range makes the whole SchemaView unbuildable, so this is the fatal path - but
        // the problem is in the import, not in the file the user named.
        val main =
          """id: urn:main
            |name: main
            |imports:
            |  - imported
            |classes:
            |  MainClass:
            |    slots:
            |      - s1
            |""".stripMargin
        val imported =
          """id: urn:imported
            |name: imported
            |slots:
            |  s1:
            |    range: bad
            |""".stripMargin
        withSchemaDir("main.yaml" -> main, "imported.yaml" -> imported) { dir =>
          val path = dir.resolve("main.yaml").toString
          val (out, _, code) =
            Validate.runTestCommandWithExitCode(List("validate", "--format", "plain", path))

          out should include("## urn:imported")
          out should not include "urn:main"
          out should include("FATAL: Unknown reference 'bad'")
          code shouldBe 1
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
