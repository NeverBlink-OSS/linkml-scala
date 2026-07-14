package eu.neverblink.linkml.tests

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LintCatalogueSpec extends AnyWordSpec, Matchers {
  for entry <- ModelCatalogue.all do {
    s"Model '${entry.model.root.name}'" should {
      "lint" in {
        entry.model.lint() shouldBe None
      }

      "have ranks defined for tree_root" in {
        // This is needed for frictionless table schema
        entry.model.treeRoot.foreach { cls =>
          val slots = cls.derivedAttributes.values
          if slots.size > 1 then
            slots.foreach { slot =>
              withClue(cls.cls.name + "." + slot.slot.name + " does not have a rank defined") {
                slot.slot.rank should not be None
              }
            }
        }
      }
    }
  }
}
