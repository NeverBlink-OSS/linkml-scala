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

  "inLanguageOrFallback" should {
    "return the value of a plain text for any language" in {
      PlainText("hello").inLanguageOrFallback("pl") shouldBe "hello"
    }

    "return the selected language text" in {
      MultilingualText(
        Map("pl" -> "cześć", "en" -> "hello", "de" -> "hallo"),
      ).inLanguageOrFallback("pl") shouldBe "cześć"
    }

    "fall back to the English text" in {
      MultilingualText(
        Map("pl" -> "cześć", "en" -> "hello", "de" -> "hallo"),
      ).inLanguageOrFallback("fr") shouldBe "hello"
    }

    "fall back to the alphabetically first language tag" in {
      MultilingualText(Map("pl" -> "cześć", "de" -> "hallo")).inLanguageOrFallback(
        "en",
      ) shouldBe "hallo"
    }
  }

  "localized" should {
    val text = MultilingualText(Map("pl" -> "cześć", "de" -> "hallo"))

    "use the language from the context" in {
      given MetadataLanguage = MetadataLanguage("pl")
      text.localized shouldBe "cześć"
      PlainText("hello").localized shouldBe "hello"
      Some(text).localized shouldBe Some("cześć")
      Seq(text, PlainText("hello")).localized shouldBe Seq("cześć", "hello")
    }

    "fall back for each value separately" in {
      given MetadataLanguage = MetadataLanguage("pl")
      MultilingualText(Map("en" -> "hello")).localized shouldBe "hello"
      Seq(text, MultilingualText(Map("en" -> "hello"))).localized shouldBe Seq("cześć", "hello")
    }

    "keep an empty Option or Seq empty" in {
      given MetadataLanguage = MetadataLanguage("pl")
      (None: Option[LocalizedText]).localized shouldBe None
      Seq.empty[LocalizedText].localized shouldBe Seq.empty
    }

    "use the metadataLanguage of the options in context" in {
      case class Options(metadataLanguage: String) extends HasMetadataLanguage
      given Options = Options("pl")
      text.localized shouldBe "cześć"
      Some(text).localized shouldBe Some("cześć")
      Seq(text).localized shouldBe Seq("cześć")
    }
  }
}
