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
      |    attributes:
      |      name:
      |""".stripMargin

  /** Every generator, with markers that must appear in its stdout output for [[validSchema]].
    */
  private val generators: Seq[(BaseCommand[?], String, Seq[String])] = Seq(
    (JsonSchema, "json-schema", Seq("\"Root\"", "\"name\"")),
    (Shacl, "shacl", Seq("shacl#NodeShape", "<https://neverblink.eu/test/name>")),
    (Scala, "scala", Seq("abstract class Root", "def name: Option[String]")),
    (Rdfs, "rdfs", Seq("rdf-schema#Class", "rdf-schema#domain")),
    (LinkMl, "linkml", Seq("Root:", "attributes:")),
    (TableSchema, "table-schema", Seq("\"fields\"", "\"name\": \"name\"")),
    (GraphQl, "graphql", Seq("type Root", "name: string @linkml_uri")),
  )

  /** A schema with a class that pruning can remove: `Other` is reachable only through the `Root`
    * tree root, so the pruning mode and tree root override are both visible in the output.
    */
  private val prunableSchema =
    """id: https://neverblink.eu/test/
      |name: test
      |default_range: string
      |types:
      |  string:
      |classes:
      |  Root:
      |    tree_root: true
      |    attributes:
      |      other:
      |        range: Other
      |  Other:
      |    attributes:
      |      name:
      |""".stripMargin

  /** Write [[schema]] to n temporary `.yaml` files and pass their paths to test. */
  private def withSchemas[A](n: Int, schema: String = validSchema)(test: Seq[String] => A): A = {
    val files = Seq.fill(n) {
      val file = Files.createTempFile("linkml-generate", ".yaml")
      Files.writeString(file, schema)
      file
    }
    try test(files.map(_.toString))
    finally files.foreach(Files.deleteIfExists)
  }

  "a generate command" when {
    "given a single input file" should {
      for (command, name, markers) <- generators do
        s"generate $name from it" in {
          withSchemas(1) { paths =>
            val (out, err, code) =
              command.runTestCommandWithExitCode(List("generate", name) ++ paths)
            withClue(s"stderr was: $err\nstdout was: $out\n") {
              code shouldBe 0
              markers.foreach(out should include(_))
            }
          }
        }

      "cover every registered generator" in {
        val registered = App()
          .commands
          .filter(_.group == "generate")
          .flatMap(_.names.map(_.mkString(" ")))
        registered should contain theSameElementsAs generators.map("generate " + _._2)
      }
    }

    "given a pruning mode" should {
      def graphQl(args: String*): (String, String, Int) =
        withSchemas(1, prunableSchema) { paths =>
          GraphQl.runTestCommandWithExitCode(List("generate", "graphql") ++ args ++ paths)
        }

      "reject an unknown one, naming the valid modes" in {
        val (out, err, code) = graphQl("--pruning-mode", "bogus")
        err should include("Malformed pruning mode: bogus")
        err should include("treeRoot, schema, skip")
        out should not include "type Root" // nothing was generated
        code shouldBe 1
      }

      "reject `schemaRoot`, which the help used to advertise" in {
        val (_, err, code) = graphQl("--pruning-mode", "schemaRoot")
        err should include("Malformed pruning mode: schemaRoot")
        code shouldBe 1
      }

      "accept the mode in kebab case too" in {
        val (out, _, code) = graphQl("--pruning-mode", "tree-root")
        out should include("type Root")
        code shouldBe 0
      }

      "apply --tree-root to the tree root mode" in {
        val (out, _, code) = graphQl("--tree-root", "Other")
        out should include("type Other")
        out should not include "type Root" // unreachable from the overridden tree root
        code shouldBe 0
      }

      "ignore --tree-root when not pruning by tree root" in {
        val (out, _, code) = graphQl("--pruning-mode", "skip", "--tree-root", "Other")
        out should include("type Other")
        out should include("type Root")
        code shouldBe 0
      }

      "be shared with the linkml generator" in {
        withSchemas(1, prunableSchema) { paths =>
          val (_, err, code) = LinkMl.runTestCommandWithExitCode(
            List("generate", "linkml", "--pruning-mode", "bogus") ++ paths,
          )
          err should include("Malformed pruning mode: bogus")
          code shouldBe 1
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
