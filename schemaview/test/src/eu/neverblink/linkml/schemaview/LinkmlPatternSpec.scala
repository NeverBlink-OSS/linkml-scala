package eu.neverblink.linkml.schemaview

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LinkmlPatternSpec extends AnyWordSpec, Matchers {

  "LinkmlPattern" should {
    "write an anchored pattern to XSD without the anchors" in {
      LinkmlPattern("^abc$").xsd shouldBe "abc"
    }

    "let an unanchored pattern match anywhere in XSD" in {
      LinkmlPattern("abc").xsd shouldBe ".*abc.*"
      LinkmlPattern("^abc").xsd shouldBe "abc.*"
      LinkmlPattern("abc$").xsd shouldBe ".*abc"
    }

    "keep an escaped dollar sign as a character" in {
      LinkmlPattern("^a\\$").xsd shouldBe "a\\$.*"
      LinkmlPattern("^a\\\\$").xsd shouldBe "a\\\\"
    }

    "put a top-level alternation in brackets" in {
      LinkmlPattern("^a|b$").xsd shouldBe "(a|b)"
      LinkmlPattern("^(a|b)c$").xsd shouldBe "(a|b)c"
      LinkmlPattern("^[a|b]$").xsd shouldBe "[a|b]"
      LinkmlPattern("^\\|a$").xsd shouldBe "\\|a"
    }

    "read an XSD pattern" in {
      LinkmlPattern.fromXsd("abc").linkml shouldBe "^abc$"
      LinkmlPattern.fromXsd(".*abc.*").linkml shouldBe "abc"
      LinkmlPattern.fromXsd("a|b").linkml shouldBe "^(a|b)$"
    }

    "read back what it writes" in {
      for p <- Seq("^abc$", "abc", "^abc", "abc$", "^[0-9]{4}$", "^(a|b)$", "x\\.y") do
        LinkmlPattern.fromXsd(LinkmlPattern(p).xsd).linkml shouldBe p
    }

    "tell whether it is anchored" in {
      LinkmlPattern("^a$").anchoredAtStart shouldBe true
      LinkmlPattern("^a$").anchoredAtEnd shouldBe true
      LinkmlPattern("a\\$").anchoredAtEnd shouldBe false
    }
  }
}
