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
    "retain get version and destination when correcting an invalid format before writing files" in {
      val root = os.temp.dir(prefix = "ohi guidance ")
      try {
        val destination = root / "a friend's files"
        val output = rejected(
          Get,
          "get",
          "w3c/prov-o@2013",
          "--format",
          "turtle",
          "--to",
          destination.toString,
        )
        output should startWith("Error: Unsupported format 'turtle'.\n\nSupported formats\n")
        output should include("ohi get w3c/prov-o@2013 --format owl-turtle")
        output should include(s"--to '$root/a friend'\\''s files'")
        os.exists(destination) shouldBe false
        os.list(root) shouldBe empty
      } finally os.remove.all(root)
    }

    "retain add version and project when correcting an invalid format before writing files" in {
      val root = os.temp.dir(prefix = "ohi guidance ")
      try {
        val destination = root / "a friend's files"
        val output = rejected(
          Add,
          "add",
          "w3c/prov-o@2013",
          "--format",
          "turtle",
          "--project",
          root.toString,
        )
        output should startWith("Error: Unsupported format 'turtle'.\n\nSupported formats\n")
        output should include("ohi add w3c/prov-o@2013 --format owl-turtle")
        output should include(s"--project '$root'")
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

    "quote multiple query words together and retain the supplied search options" in {
      val output = rejected(Search, "search", "water", "quality", "--page=2")
      output should include("Received 2 arguments: water, quality.")
      output should include("  ohi search 'water quality' --page=2")
    }

    "show supported formats for an invalid supplied format" in {
      val output = rejected(Get, "get", "w3c/prov-o", "--format", "turtle")
      output should startWith("Error: Unsupported format 'turtle'.")
      output should include("Supported formats")
      output should include("ohi get w3c/prov-o --format owl-turtle")
    }

    "explain the reference grammar for a dash-prefixed malformed inspect input" in {
      val output = rejected(Inspect, "inspect", "--", "-sensor")
      output should include("Invalid ontology reference '-sensor'.")
      output should include("Expected namespace/name[@version] without backticks.")
      output should include("ohi inspect --help")
    }

    "explain the URL rule for an unsupported inspect scheme" in {
      val output = rejected(Inspect, "inspect", "ftp://example.org/release")
      output should include("URL: ftp://example.org/release")
      output should include("absolute HTTP(S) URL")
      output should not include "Copy the URL shown by search"
    }
  }
}
