package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CaseSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {
  "Case" should {
    "snake_case" in {
      Case.snake_case("") shouldBe ""
      Case.snake_case("abc") shouldBe "abc"
      Case.snake_case("abc_def") shouldBe "abc_def"
      Case.snake_case("abc_def1") shouldBe "abc_def1"
      Case.snake_case("abc_def_1") shouldBe "abc_def1"
      Case.snake_case("abc def") shouldBe "abc_def"
      Case.snake_case("AbcDef") shouldBe "abc_def"
      Case.snake_case("abcDef") shouldBe "abc_def"
      Case.snake_case("abcDef1") shouldBe "abc_def1"
      Case.snake_case("abc-def") shouldBe "abc_def"
      Case.snake_case("abc-def-1") shouldBe "abc_def1"
      Case.snake_case("abc-def-v1") shouldBe "abc_def_v1"
      Case.snake_case("ABC_DEF") shouldBe "abc_def"
      Case.snake_case("ABC-DEF") shouldBe "abc_def"
      Case.snake_case("ABC DEF") shouldBe "abc_def"
      Case.snake_case("iName") shouldBe "i_name"
      Case.snake_case("IName") shouldBe "i_name"
      Case.snake_case("httpHandler") shouldBe "http_handler"
      Case.snake_case("HTTPHandler") shouldBe "http_handler"
      Case.snake_case("HTTP") shouldBe "http"
    }
    "PascalCase" in {
      Case.PascalCase("") shouldBe ""
      Case.PascalCase("Abc") shouldBe "Abc"
      Case.PascalCase("AbcDef") shouldBe "AbcDef"
      Case.PascalCase("abc_def") shouldBe "AbcDef"
      Case.PascalCase("abc_def1") shouldBe "AbcDef1"
      Case.PascalCase("abc_def_1") shouldBe "AbcDef1"
      Case.PascalCase("abc def") shouldBe "AbcDef"
      Case.PascalCase("abcDef") shouldBe "AbcDef"
      Case.PascalCase("abcDef1") shouldBe "AbcDef1"
      Case.PascalCase("abc-def") shouldBe "AbcDef"
      Case.PascalCase("abc-def-1") shouldBe "AbcDef1"
      Case.PascalCase("abc-def-v1") shouldBe "AbcDefV1"
      Case.PascalCase("ABC_DEF") shouldBe "AbcDef"
      Case.PascalCase("ABC-DEF") shouldBe "AbcDef"
      Case.PascalCase("ABC DEF") shouldBe "AbcDef"
      Case.PascalCase("IName") shouldBe "IName"
      Case.PascalCase("HTTPHandler") shouldBe "HttpHandler"
      Case.PascalCase("HTTP") shouldBe "Http"
    }
    "camelCase" in {
      Case.camelCase("") shouldBe ""
      Case.camelCase("abc") shouldBe "abc"
      Case.camelCase("abcDef") shouldBe "abcDef"
      Case.camelCase("abc_def") shouldBe "abcDef"
      Case.camelCase("abc_def1") shouldBe "abcDef1"
      Case.camelCase("abc_def_1") shouldBe "abcDef1"
      Case.camelCase("abc def") shouldBe "abcDef"
      Case.camelCase("AbcDef") shouldBe "abcDef"
      Case.camelCase("AbcDef1") shouldBe "abcDef1"
      Case.camelCase("abc-def") shouldBe "abcDef"
      Case.camelCase("abc-def-1") shouldBe "abcDef1"
      Case.camelCase("abc-def-v1") shouldBe "abcDefV1"
      Case.camelCase("ABC_DEF") shouldBe "abcDef"
      Case.camelCase("ABC-DEF") shouldBe "abcDef"
      Case.camelCase("ABC DEF") shouldBe "abcDef"
      Case.camelCase("IName") shouldBe "iName"
      Case.camelCase("HTTPHandler") shouldBe "httpHandler"
      Case.camelCase("HTTP") shouldBe "http"
    }

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
      Case.baseToCamel("abc_def", true) shouldBe "AbcDef"
      Case.baseToCamel("abc", true) shouldBe "Abc"
      Case.baseToCamel("123", true) shouldBe "123"
      Case.baseToCamel("123_abc", true) shouldBe "123Abc"
      Case.baseToCamel("def_123", true) shouldBe "Def123"
      Case.baseToCamel("abc_123_def", true) shouldBe "Abc123Def"
    }

    "convert base to camel" in {
      Case.baseToCamel("abc_def", false) shouldBe "abcDef"
      Case.baseToCamel("abc", false) shouldBe "abc"
      Case.baseToCamel("123", false) shouldBe "123"
      Case.baseToCamel("123_abc", false) shouldBe "123Abc"
      Case.baseToCamel("def_123", false) shouldBe "def123"
      Case.baseToCamel("abc_123_def", false) shouldBe "abc123Def"
    }
  }
}
