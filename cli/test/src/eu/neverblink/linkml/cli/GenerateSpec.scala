package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class GenerateSpec extends AnyWordSpec, Matchers {

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

  /** Write [[validSchema]] to n temporary `.yaml` files and pass their paths to test. */
  private def withSchemas(n: Int)(test: Seq[String] => Unit): Unit = {
    val files = Seq.fill(n) {
      val file = Files.createTempFile("linkml-generate", ".yaml")
      Files.writeString(file, validSchema)
      file
    }
    try test(files.map(_.toString))
    finally files.foreach(Files.deleteIfExists)
  }

  "a generate command" when {
    "given a single input file" should {
      "generate from it" in {
        withSchemas(1) { paths =>
          val (out, _, code) =
            JsonSchema.runTestCommandWithExitCode(List("generate", "json-schema") ++ paths)
          out should include("\"$schema\"")
          code shouldBe 0
        }
      }
    }

    "given more than one input file" should {
      "refuse to run" in {
        withSchemas(2) { paths =>
          val (out, err, code) =
            JsonSchema.runTestCommandWithExitCode(List("generate", "json-schema") ++ paths)

          err should include("takes a single input file, but 2 were given")
          err should include(paths(0))
          err should include(paths(1))
          out should not include "\"$schema\"" // nothing was generated
          code shouldBe 1
        }
      }

      "name the command that was invoked" in {
        withSchemas(3) { paths =>
          val (_, err, _) =
            Shacl.runTestCommandWithExitCode(List("generate", "shacl") ++ paths)
          err should include("`generate shacl` takes a single input file, but 3 were given")
        }
      }
    }

    "given no input file" should {
      "fail with a helpful message" in {
        val (_, err, code) =
          JsonSchema.runTestCommandWithExitCode(List("generate", "json-schema"))
        err should include("Input file is required.")
        code shouldBe 1
      }
    }
  }
}
