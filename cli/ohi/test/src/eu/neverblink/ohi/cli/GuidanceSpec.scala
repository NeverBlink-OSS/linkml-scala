package eu.neverblink.ohi.cli

import eu.neverblink.linkml.cli.BaseCommand
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GuidanceSpec extends AnyWordSpec, Matchers {
  private def rejected(command: BaseCommand[?], args: String*): String = {
    val (out, err, code) = command.runTestCommandWithExitCode(args.toList, new OhiApp().main)
    code shouldBe 1
    out shouldBe empty
    err
  }

  "focused corrections" should {
    for (name, command) <- Seq("get" -> Get, "add" -> Add) do
      s"retain $name version and destination in neutral format guidance before writing files" in {
        val root = os.temp.dir(prefix = "ohi guidance ")
        try {
          val destination = root / "a friend's files"
          val project = if name == "add" then Seq("--project", root.toString) else Seq.empty
          val output = rejected(
            command,
            (Seq(name, "w3c/prov-o@2013", "--to", destination.toString) ++ project)*,
          )
          output should startWith("Choose a file format with --format.\n\nSupported formats\n")
          output should include(
            s"ohi $name w3c/prov-o@2013 --format owl-turtle --to '$root/a friend'\\''s files'",
          )
          if name == "add" then output should include(s"--project '$root'")
          output should not include "Error:"
          os.exists(destination) shouldBe false
          os.list(root) shouldBe empty
        } finally os.remove.all(root)
      }

    "remove an equals-form conflicting selector without dropping the destination or timeout" in {
      val output = rejected(
        Get,
        "get",
        "https://example.org/file.ttl",
        "--format=owl-turtle",
        "--to",
        "./files here",
        "--timeout-ms=50",
      )
      output should startWith("Error: --format cannot be used with a file URL.")
      output should include(
        "  ohi get https://example.org/file.ttl --to './files here' --timeout-ms=50",
      )
      output should not include "--format=owl-turtle"
    }

    "retain a dash-prefixed query behind the separator when correcting its page" in {
      val output = rejected(Search, "search", "--page=0", "--per-page", "25", "--", "-topic")
      output should startWith("Error: --page must be at least 1. Received 0.")
      output should include("  ohi search --page 1 --per-page 25 -- -topic")
    }

    "quote multiple query words together and retain the supplied search options" in {
      val output = rejected(Search, "search", "water", "quality", "--page=2")
      output should include("Received 2 arguments: water, quality.")
      output should include("  ohi search 'water quality' --page=2")
    }

    "distinguish an invalid supplied format from an omitted one" in {
      val output = rejected(Get, "get", "w3c/prov-o", "--format", "turtle")
      output should startWith("Error: Unsupported format 'turtle'.")
      output should include("Supported formats")
      output should include("ohi get w3c/prov-o --format owl-turtle")
    }

    "retain a dash-prefixed inspect input as search text" in {
      val output = rejected(Inspect, "inspect", "--", "-sensor")
      output should include("ohi search -- -sensor")
    }

    "explain the URL rule for an unsupported inspect scheme" in {
      val output = rejected(Inspect, "inspect", "ftp://example.org/release")
      output should include("URL: ftp://example.org/release")
      output should include("absolute HTTP(S) URL")
      output should not include "Copy the URL shown by search"
    }
  }
}
