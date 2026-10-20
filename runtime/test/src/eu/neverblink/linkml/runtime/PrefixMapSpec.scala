package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PrefixMapSpec extends AnyWordSpec, Matchers {
  private val ex = "ex" -> "http://example.org/"

  "PrefixMap.expandOrNull" should {
    "replace a known prefix with its namespace" in {
      PrefixMap(defaults = Seq(ex)).expandOrNull("ex:blip") shouldBe "http://example.org/blip"
    }
    "keep the namespace as it is written" in {
      val go = "GO" -> "http://purl.obolibrary.org/obo/GO_"
      PrefixMap(defaults = Seq(go)).expandOrNull("GO:0008150") shouldBe
        "http://purl.obolibrary.org/obo/GO_0008150"
    }
    "return null for an unknown prefix or no colon" in {
      val pm = PrefixMap(defaults = Seq(ex))
      pm.expandOrNull("other:blip") shouldBe null
      pm.expandOrNull("blip") shouldBe null
      pm.expandOrNull("http://example.org/blip") shouldBe null
    }
    "expand a value that looks like a URI when its scheme is declared as a prefix" in {
      val pm = PrefixMap(defaults = Seq("http" -> "http://other.org/"))
      pm.expandOrNull("http://example.org/blip") shouldBe "http://other.org///example.org/blip"
    }
    "give overrides precedence over document prefixes, and those over defaults" in {
      val pm = PrefixMap(
        overrides = Seq("a" -> "http://overrides.org/"),
        defaults = Seq("a" -> "http://defaults.org/", "b" -> "http://defaults.org/"),
      ).withDocumentPrefixes(Seq("a" -> "http://document.org/", "b" -> "http://document.org/"))
      pm.expandOrNull("a:x") shouldBe "http://overrides.org/x"
      pm.expandOrNull("b:x") shouldBe "http://document.org/x"
    }
    "use the first declaration of a prefix that a place declares twice" in {
      val pm = PrefixMap.empty
        .withDocumentPrefixes(Seq("a" -> "http://first.org/", "a" -> "http://second.org/"))
      pm.expandOrNull("a:x") shouldBe "http://first.org/x"
      pm.compactOrNull("http://first.org/x") shouldBe "a:x"
      pm.compactOrNull("http://second.org/x") shouldBe null
    }
  }

  "PrefixMap.compactOrNull" should {
    "use the longest matching namespace" in {
      val pm = PrefixMap(defaults = Seq(ex, "exs" -> "http://example.org/sub/"))
      pm.compactOrNull("http://example.org/sub/x") shouldBe "exs:x"
      pm.compactOrNull("http://example.org/x") shouldBe "ex:x"
    }
    "return null for an unknown namespace or an empty local part" in {
      val pm = PrefixMap(defaults = Seq(ex))
      pm.compactOrNull("http://other.org/x") shouldBe null
      pm.compactOrNull("http://example.org/") shouldBe null
    }
    "try a shorter namespace when the longest one doesn't give a valid CURIE" in {
      val pm = PrefixMap(defaults = Seq(ex, "exa" -> "http://example.org/a/"))
      // `exa:b:c` isn't valid, as the first segment of a CURIE's local part can't have a colon
      pm.compactOrNull("http://example.org/a/b:c") shouldBe "ex:a/b:c"
      // Neither `exa::b` nor `ex:a::b` is valid
      PrefixMap(defaults = Seq(ex)).compactOrNull("http://example.org/a:b") shouldBe null
    }
    "not use a prefix that a place with higher precedence redefines" in {
      val pm = PrefixMap(
        overrides = Seq("ex" -> "http://other.org/"),
        defaults = Seq(ex),
      )
      pm.compactOrNull("http://example.org/x") shouldBe null
      pm.compactOrNull("http://other.org/x") shouldBe "ex:x"
    }
    "pick the prefix with the highest precedence when two have the same namespace" in {
      val pm = PrefixMap(defaults = Seq("b" -> "http://example.org/"))
        .withDocumentPrefixes(Seq("a" -> "http://example.org/", "c" -> "http://example.org/"))
      pm.compactOrNull("http://example.org/x") shouldBe "a:x"
    }
    "give a CURIE that expands back to the same URI" in {
      val pm = PrefixMap(defaults = Seq(ex, "exs" -> "http://example.org/sub/"))
      for (uri <- Seq("http://example.org/x", "http://example.org/sub/y", "http://example.org/a#b"))
        pm.expandOrNull(pm.compactOrNull(uri)) shouldBe uri
    }
  }

  "PrefixMap.empty" should {
    "expand and compact nothing" in {
      PrefixMap.empty.isEmpty shouldBe true
      PrefixMap.empty.expandOrNull("ex:x") shouldBe null
      PrefixMap.empty.compactOrNull("http://example.org/x") shouldBe null
    }
  }
}
