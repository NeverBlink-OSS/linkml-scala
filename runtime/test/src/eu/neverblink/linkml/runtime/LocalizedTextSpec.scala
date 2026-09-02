package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LocalizedTextSpec extends AnyWordSpec, Matchers {
  "plain" should {
    "return the value of a plain text" in {
      PlainText("hello").plain shouldBe "hello"
    }

    "prefer the English text" in {
      MultilingualText(
        Map("pl" -> "cześć", "en" -> "hello", "de" -> "hallo"),
      ).plain shouldBe "hello"
    }

    "fall back to the alphabetically first language tag" in {
      MultilingualText(Map("pl" -> "cześć", "de" -> "hallo")).plain shouldBe "hallo"
    }

    "not depend on the order the languages were given in" in {
      val one = MultilingualText(Map("pl" -> "cześć", "de" -> "hallo"))
      val other = MultilingualText(Map("de" -> "hallo", "pl" -> "cześć"))
      one.plain shouldBe other.plain
    }
  }

  "inLanguage" should {
    "return the value of a plain text" in {
      PlainText("hello").inLanguage("en") shouldBe Some("hello")
    }

    "return the selected language text" in {
      MultilingualText(
        Map("pl" -> "cześć", "en" -> "hello", "de" -> "hallo"),
      ).inLanguage("en") shouldBe Some("hello")
    }

    "return None if the language is missing from the map" in {
      MultilingualText(Map("pl" -> "cześć", "de" -> "hallo")).inLanguage("en") shouldBe None
    }
  }
}
