package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VersionSpec extends AnyWordSpec, Matchers {
  for alias <- Seq("version", "v", "--version") do
    s"the $alias command" should {
      "print the tool name and component versions" in {
        val (out, _) = Version.runTestCommand(List(alias))
        out should startWith("linkml-scala")
        out should include("Metamodel")
        out should include("Scala")
        out should include("RDF4J")
        out should include("Runtime")
      }

      "include the copyright year and a link to the license" in {
        val (out, _) = Version.runTestCommand(List(alias))
        out should include(
          s"Copyright (C) ${java.time.Year.now().getValue} NeverBlink and contributors",
        )
        out should include("https://www.apache.org/licenses/LICENSE-2.0")
      }

      "serialize a BuildInfo for --format json" in {
        val (out, _) = Version.runTestCommand(List(alias, "--format", "json"))

        out.trim should startWith("{")
        out.trim should endWith("}")
        // The versions themselves depend on the checkout, so only the slots are checked.
        out should include("\"linkml_scala_version\"")
        out should include("\"metamodel_version\"")
        out should include("\"scala_version\"")
        out should include("\"runtime\"")
        out should include("\"platform\": \"JVM\"")
        out should include("\"rdf4j_version\"")
        // Scala.js and the C ABI are not part of a CLI build, so they must not be claimed.
        out should not include "scala_js_version"
        out should not include "abi_version"
        // No display chrome.
        out should not include "linkml-scala  "
        out should not include "Copyright"
      }

      "reject an unknown format" in {
        val (_, err, code) = Version.runTestCommandWithExitCode(List(alias, "--format", "yaml"))
        code shouldBe 1
        err should include("Unknown format 'yaml'")
      }
    }
}
