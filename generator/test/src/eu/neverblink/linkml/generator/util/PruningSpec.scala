package eu.neverblink.linkml.generator.util

import eu.neverblink.linkml.generator.options.PruningMode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PruningSpec extends AnyWordSpec, Matchers {

  "Pruning" should {
    "use the tree root override only in the tree root mode" in {
      Pruning(PruningMode.TreeRoot, Some("Person")) shouldBe Pruning.treeRoot(Some("Person"))
      Pruning(PruningMode.TreeRoot, None) shouldBe Pruning.treeRoot(None)
      Pruning(PruningMode.Schema, Some("Person")) shouldBe Pruning.schemaRoot
      Pruning(PruningMode.Skip, None) shouldBe Pruning.skip
    }
  }
}
