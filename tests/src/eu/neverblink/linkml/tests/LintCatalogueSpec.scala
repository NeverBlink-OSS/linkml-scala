package eu.neverblink.linkml.tests

import eu.neverblink.linkml.validation.MissingRecommendedSlot
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LintCatalogueSpec extends AnyWordSpec, Matchers {
  for entry <- ModelCatalogue.allOptIn do {
    s"Model '${entry.model.root.name}'" should {
      "not use the same name for a valid and an invalid instance" in {
        val valid = entry.validInstances.map(_.name).toSet
        entry.invalidInstances.map(_.name).filter(valid.contains) shouldBe empty
      }
    }
  }

  for entry <- ModelCatalogue.all do {
    s"Model '${entry.model.root.name}'" should {
      "lint" in {
        // Test models don't describe all their elements
        entry.model.lintProblems.filter {
          case _: MissingRecommendedSlot => false
          case _ => true
        }.map(_.infer()) shouldBe empty
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
