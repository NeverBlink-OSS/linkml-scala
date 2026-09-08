package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CaseSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {
  def checkRoundTrip(base: String, rename: String => String): Unit = {
    val tf = rename(base)
    Case.base(tf) shouldBe base
  }

  "Case" should {
    "convert to base" in {
      Case.base("") shouldBe ""
      Case.base("abc") shouldBe "abc"
      Case.base("abc_def") shouldBe "abc_def"
      Case.base("abc_def1") shouldBe "abc_def_1"
      Case.base("abc_def_1") shouldBe "abc_def_1"
      Case.base("abc def") shouldBe "abc_def"
      Case.base("AbcDef") shouldBe "abc_def"
      Case.base("abcDef") shouldBe "abc_def"
      Case.base("abcDef_1") shouldBe "abc_def_1"
      Case.base("abc-def") shouldBe "abc_def"
      Case.base("abc-def-1") shouldBe "abc_def_1"
      Case.base("abc-def-v1") shouldBe "abc_def_v_1"
      Case.base("ABC_DEF") shouldBe "abc_def"
      Case.base("ABC-DEF") shouldBe "abc_def"
      Case.base("ABC DEF") shouldBe "abc_def"
      Case.base("iName") shouldBe "i_name"
      Case.base("IName") shouldBe "i_name"

      // falling edge
      Case.base("HTTPHandler") shouldBe "http_handler"
      // rising edge
      Case.base("httpHandler") shouldBe "http_handler"
      Case.base("HandlerHTTP") shouldBe "handler_http"
      // both edges
      Case.base("HandlerHTTPSpecial") shouldBe "handler_http_special"
      Case.base("VoIP_MODE") shouldBe "vo_ip_mode"

      // leading separator stripped
      Case.base("_lead") shouldBe "lead"
      Case.base("[][]lead") shouldBe "lead"
      // trailing separator stripped
      Case.base("trail_") shouldBe "trail"
      Case.base("trail[][]") shouldBe "trail"
      // non-standard separators replaced and folded
      Case.base("abc->def") shouldBe "abc_def"
      // numbers allowed
      Case.base("123") shouldBe "123"
      // screaming snake not split
      Case.base("SCREAMING_SNAKE") shouldBe "screaming_snake"
      // non-ASCII replaced
      Case.base("snake🐍case") shouldBe "snake_case"
      Case.base("oto🪲chrabąszcz") shouldBe "oto_chrab_szcz"
      // results in empty name
      Case.base("!") shouldBe ""
      Case.base("żółć") shouldBe ""
    }

    "convert base to pascal" in {
      val f = Case.baseToPascal
      f("abc_def") shouldBe "AbcDef"
      f("abc") shouldBe "Abc"
      f("123") shouldBe "123"
      f("123_abc") shouldBe "123Abc"
      f("def_123") shouldBe "Def123"
      f("abc_123_def") shouldBe "Abc123Def"
    }

    "convert base to camel" in {
      val f = Case.baseToCamel
      f("abc_def") shouldBe "abcDef"
      f("abc") shouldBe "abc"
      f("123") shouldBe "123"
      f("123_abc") shouldBe "123Abc"
      f("def_123") shouldBe "def123"
      f("abc_123_def") shouldBe "abc123Def"
    }

    "convert base to screaming snake" in {
      val f = Case.baseToScreamingSnake
      f("abc_def") shouldBe "ABC_DEF"
      f("abc") shouldBe "ABC"
      f("123") shouldBe "123"
      f("123_abc") shouldBe "123_ABC"
      f("def_123") shouldBe "DEF_123"
      f("abc_123_def") shouldBe "ABC_123_DEF"
    }

    "round trip base via pascal" in {
      val f = Case.baseToPascal
      checkRoundTrip("abc_def", f)
      checkRoundTrip("abc", f)
      checkRoundTrip("123", f)
      checkRoundTrip("123_abc", f)
      checkRoundTrip("def_123", f)
      checkRoundTrip("abc_123_def", f)
    }

    "round trip base via camel" in {
      val f = Case.baseToCamel
      checkRoundTrip("abc_def", f)
      checkRoundTrip("abc", f)
      checkRoundTrip("123", f)
      checkRoundTrip("123_abc", f)
      checkRoundTrip("def_123", f)
      checkRoundTrip("abc_123_def", f)
    }

    "round trip base via screaming snake" in {
      val f = Case.baseToScreamingSnake
      checkRoundTrip("abc_def", f)
      checkRoundTrip("abc", f)
      checkRoundTrip("123", f)
      checkRoundTrip("123_abc", f)
      checkRoundTrip("def_123", f)
      checkRoundTrip("abc_123_def", f)
    }

    "round trip base via itself" in {
      val f = Case.base
      checkRoundTrip("abc_def", f)
      checkRoundTrip("abc", f)
      checkRoundTrip("123", f)
      checkRoundTrip("123_abc", f)
      checkRoundTrip("def_123", f)
      checkRoundTrip("abc_123_def", f)
    }
  }
}
