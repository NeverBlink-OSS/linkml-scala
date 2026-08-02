package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{ByteArrayOutputStream, PrintStream}

class AppSpec extends AnyWordSpec, Matchers {

  // Only the program name is colored on the usage screen, so these two markers around it can be
  // matched without stripping escape codes.
  private def shouldBeTheUsageScreen(out: String): Unit = {
    out should include("Usage: ")
    out should include("<COMMAND>")
  }

  private def runApp(args: List[String]): (String, String, Int) = {
    val out = new ByteArrayOutputStream()
    val err = new ByteArrayOutputStream()
    val exitCode = try {
      App(
        testMode = true,
        outStream = new PrintStream(out, true, "UTF-8"),
        errStream = new PrintStream(err, true, "UTF-8"),
      ).main(args.toArray)
      0
    } catch {
      case ExitException(code) => code
    }
    (out.toString("UTF-8"), err.toString("UTF-8"), exitCode)
  }

  "the CLI entry point" when {
    "given an unknown command" should {
      "fail (exit 1) instead of quietly printing the usage" in {
        val (_, err, code) =
          runApp(List("genrate", "json-schema", "model.yaml"))
        err should include("Unknown command: genrate")
        err should include("Run 'linkml-scala --help'")
        code shouldBe 1
      }

      "name the subcommand when the group is right but the generator isn't" in {
        // The Malbolge language is NOT and will not be supported by linkml-scala :)
        val (_, err, code) =
          runApp(List("generate", "malbolge", "model.yaml"))
        // The file name is not part of the guess.
        err should include("Unknown command: generate malbolge")
        err should not include "model.yaml"
        code shouldBe 1
      }

      "fail on an unknown option too" in {
        val (_, err, code) = runApp(List("--bogus"))
        err should include("Unknown command: --bogus")
        code shouldBe 1
      }

      "fail on a command group given without a subcommand" in {
        val (_, _, code) = runApp(List("generate"))
        code shouldBe 1
      }
    }

    "asked for help" should {
      "still succeed (exit 0)" in {
        for flag <- Seq("--help", "-h", "-help", "--usage") do
          val (out, _, code) = runApp(List(flag))
          withClue(s"for $flag: ") {
            shouldBeTheUsageScreen(out)
            code shouldBe 0
          }
      }
    }

    "invoked bare" should {
      "print the usage and succeed (exit 0)" in {
        val (out, _, code) = runApp(List.empty)
        shouldBeTheUsageScreen(out)
        code shouldBe 0
      }
    }
  }
}
