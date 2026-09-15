package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files

class FromSpec extends AnyWordSpec, Matchers {

  private val ontology =
    """version: 0.2.0.dev0
      |name: flights
      |description: A tiny ontology.
      |ontology:
      |  - concept: Airport
      |    type: EntityType
      |    identify_by: [code]
      |    relationships:
      |      - name: code
      |        roles: [{concept: String}]
      |        multiplicity: OneToOne
      |        verbalizes: ['{Airport} is identified by {String}']
      |""".stripMargin

  /** Write [[ontology]] to a temporary file and pass its path to the test. */
  private def withOntology[A](document: String = ontology)(test: String => A): A = {
    val file = Files.createTempFile("linkml-from", ".yaml")
    Files.writeString(file, document)
    try test(file.toString)
    finally Files.deleteIfExists(file)
  }

  "from ossie" should {
    "write the schema to stdout" in {
      withOntology() { path =>
        val (out, err, code) = FromOssie.runTestCommandWithExitCode(List("from", "ossie", path))
        withClue(s"stderr was: $err\nstdout was: $out\n") {
          code shouldBe 0
          out should include("name: flights")
          out should include("Airport:")
          out should include("identifier: true")
          out should include("title: is identified by")
        }
      }
    }

    "write JSON when asked to" in {
      withOntology() { path =>
        val (out, _, code) =
          FromOssie.runTestCommandWithExitCode(List("from", "ossie", "--format", "json", path))
        code shouldBe 0
        out should startWith("{")
        out should include("\"name\": \"flights\"")
      }
    }

    "use the schema id it was given" in {
      withOntology() { path =>
        val (out, _, code) = FromOssie.runTestCommandWithExitCode(
          List("from", "ossie", "--schema-id", "https://example.com/mine", path),
        )
        code shouldBe 0
        out should include("id: https://example.com/mine")
      }
    }

    "replace an existing file at --to" in {
      withOntology() { path =>
        val dest = os.temp(contents = "x" * 10_000, suffix = ".yaml")
        val (_, err, code) = FromOssie.runTestCommandWithExitCode(
          List("from", "ossie", "--to", dest.toString, path),
        )
        withClue(s"stderr was: $err\n")(code shouldBe 0)
        val written = os.read(dest)
        written should include("name: flights")
        written should not include "xxxx"
      }
    }

    "refuse more than one input" in {
      withOntology() { path =>
        val (_, err, code) =
          FromOssie.runTestCommandWithExitCode(List("from", "ossie", path, path))
        code shouldBe 1
        err should include("single input file")
      }
    }

    "say so when there is no input" in {
      val (_, err, code) = FromOssie.runTestCommandWithExitCode(List("from", "ossie"))
      code shouldBe 1
      err should include("Input file is required.")
    }

    "say so when the file is not there" in {
      val (_, err, code) =
        FromOssie.runTestCommandWithExitCode(List("from", "ossie", "/no/such/file.yaml"))
      code shouldBe 1
      err should include("No such file")
    }

    "say so when the format is not one it writes" in {
      withOntology() { path =>
        val (_, err, code) =
          FromOssie.runTestCommandWithExitCode(List("from", "ossie", "--format", "xml", path))
        code shouldBe 1
        err should include("xml")
      }
    }

    "say so when the document is not an ontology" in {
      withOntology("name: [unclosed") { path =>
        val thrown = intercept[RuntimeException](
          FromOssie.runTestCommandWithExitCode(List("from", "ossie", path)),
        )
        thrown.getMessage should include("Ossie ontology")
      }
    }
  }
}
