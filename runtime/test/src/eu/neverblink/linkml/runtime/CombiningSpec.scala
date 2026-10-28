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

  "combineMax" should {
    "keep the smaller number, so that both bounds hold" in {
      combineMax(LinkmlAny("100"), LinkmlAny("10")) shouldBe LinkmlAny("10")
      combineMax(LinkmlAny("10\n"), LinkmlAny("100\n")) shouldBe LinkmlAny("10\n")
      combineMax(LinkmlAny("2.5"), LinkmlAny("1e1")) shouldBe LinkmlAny("2.5")
      combineMax(LinkmlAny("-1"), LinkmlAny("-2.0")) shouldBe LinkmlAny("-2.0")
    }

    "keep the value of v1 if the values can't be compared" in {
      combineMax(LinkmlAny("2020-01-02"), LinkmlAny("10")) shouldBe LinkmlAny("2020-01-02")
      combineMax(LinkmlAny("10"), LinkmlAny("2020-01-02")) shouldBe LinkmlAny("10")
    }
  }

  "combineMin" should {
    "keep the larger number, so that both bounds hold" in {
      combineMin(LinkmlAny("-100"), LinkmlAny("0")) shouldBe LinkmlAny("0")
      combineMin(LinkmlAny("5"), LinkmlAny("1")) shouldBe LinkmlAny("5")
      combineMin(LinkmlAny("0.5"), LinkmlAny("1")) shouldBe LinkmlAny("1")
    }

    "keep the value of v1 if the values can't be compared" in {
      combineMin(LinkmlAny("abc"), LinkmlAny("10")) shouldBe LinkmlAny("abc")
    }
  }
}
