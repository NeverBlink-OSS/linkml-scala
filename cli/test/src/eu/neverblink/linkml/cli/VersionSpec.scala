package eu.neverblink.linkml.cli

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class VersionSpec extends AnyWordSpec, Matchers {
  for alias <- Seq("version", "v", "--version") do
    s"the $alias command" should {
      "print the tool name and component versions" in {
        val (out, _) = Version.runTestCommand(List(alias))
        out should startWith("linkml-scala")
        out should include("Scala")
        out should include("RDF4J")
        out should include("JVM")
      }

      "include the copyright year and a link to the license" in {
        val (out, _) = Version.runTestCommand(List(alias))
        out should include(
          s"Copyright (C) ${java.time.Year.now().getValue} NeverBlink and contributors",
        )
        out should include("https://www.apache.org/licenses/LICENSE-2.0")
      }
    }
}
