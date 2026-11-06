package eu.neverblink.linkml.tests

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LintCatalogueSpec extends AnyWordSpec, Matchers {

  /** Models that show on purpose something that `validate` warns about, with the type of the one
    * warning that each gets.
    */
  private val expectedWarnings: Map[String, String] = Map(
    // A class makes an inherited single-valued slot multivalued
    "multivaluedOverride" -> "LooserSlotConstraint",
  )
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
        val problems = ModelCatalogue.lintProblemsExceptMissingRecommendedSlots(entry.model)
        expectedWarnings.get(entry.model.root.name) match {
          case Some(issueType) => problems.map(_.issueType) shouldBe Seq(issueType)
          case _ => problems shouldBe empty
        }
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
