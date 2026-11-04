package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.compiletime.testing.typeCheckErrors

class ReferenceCollectorSpec extends AnyWordSpec, Matchers {
  "ReferenceCollector.derived" should {
    "accept the names of skipped fields as they are written in YAML" in {
      typeCheckErrors(
        """ReferenceCollector.derived[eu.neverblink.linkml.metamodel.ClassDefinition]("is_a")""",
      ) shouldBe empty
    }

    "reject the name of a skipped field that the class doesn't have" in {
      typeCheckErrors(
        """ReferenceCollector.derived[eu.neverblink.linkml.metamodel.ClassDefinition]("isA")""",
      ).map(_.message) shouldBe Seq(
        "Can't skip 'isA', as ClassDefinition has no such field. Use the names of the fields " +
          "as they are written in YAML, such as 'is_a'.",
      )
    }
  }
}
