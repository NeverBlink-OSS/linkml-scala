package eu.neverblink.linkml.generator.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PruningModeSpec extends AnyWordSpec, Matchers {

  "PruningMode" should {
    "parse every mode the JS API and the CLI flag accept" in {
      PruningMode("treeRoot", Some("Person")) shouldBe PruningMode.treeRoot(Some("Person"))
      PruningMode("tree-root", None) shouldBe PruningMode.treeRoot(None)
      PruningMode("schema", None) shouldBe PruningMode.schemaRoot
      PruningMode("skip", None) shouldBe PruningMode.skip
    }

    "reject an unknown mode by name, rather than with a MatchError" in {
      intercept[IllegalArgumentException](PruningMode("tree_toot", None))
        .getMessage should include("tree_toot")
    }
  }
}
