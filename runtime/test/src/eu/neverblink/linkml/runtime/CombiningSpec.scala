package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CombiningSpec extends AnyWordSpec, Matchers {
  "combineMap" should {
    "keep the value from the first map for a key in both maps" in {
      combineMap(Map("a" -> 1, "b" -> 2), Map("a" -> 3, "c" -> 4)) shouldBe
        Map("a" -> 1, "b" -> 2, "c" -> 4)
    }

    "return the other map when one is empty" in {
      combineMap(Map("a" -> 1), Map.empty[String, Int]) shouldBe Map("a" -> 1)
      combineMap(Map.empty[String, Int], Map("a" -> 1)) shouldBe Map("a" -> 1)
    }
  }
}
