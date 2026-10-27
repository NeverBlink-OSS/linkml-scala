package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class UriOrCurieSpec extends AnyWordSpec, Matchers {
  "UriOrCurie.isCurie" should {
    "accept values with the shape of a CURIE" in {
      Seq("acme:Person", "NCIT:C41185", "ex:a/b", "_x:y", "a-b.c:d", "ex:").foreach(s =>
        withClue(s)(UriOrCurie.isCurie(s) shouldBe true),
      )
    }
    "reject URIs, file paths and values without a prefix" in {
      Seq(
        "http://example.org/a",
        "file:/schemas/core",
        "C:\\schemas\\core",
        "urn:isbn:0451450523",
        "mailto:someone@example.org",
        "URN:isbn:0451450523",
        "Person",
        ":Person",
        "1ex:a",
        "a b:c",
      ).foreach(s => withClue(s)(UriOrCurie.isCurie(s) shouldBe false))
    }
  }

  "UriOrCurie.compacted" should {
    val prefixes = PrefixMap(defaults = Seq("ex" -> "https://example.org/"))
    "write a URI as a CURIE if it can" in {
      UriOrCurie.compacted(Uri("https://example.org/a"), prefixes) shouldBe "ex:a"
      UriOrCurie.compacted(Uri("acme:a"), prefixes) shouldBe "acme:a"
      UriOrCurie.compacted(Curie("other:a"), prefixes) shouldBe "other:a"
    }
    "fail on a URI that would be read back as a CURIE" in {
      Seq(prefixes, prefixes.withoutCompaction).foreach { p =>
        (the[IllegalArgumentException] thrownBy UriOrCurie.compacted(Uri("ex:a"), p))
          .getMessage shouldBe
          "The URI 'ex:a' can't be written, as 'ex' is a declared prefix, so it would be read " +
          "back as a CURIE with another meaning. If it is meant as a CURIE, declare the prefix " +
          "'ex' where it is used. Otherwise, use a URI whose scheme isn't a declared prefix"
      }
    }
  }

  "Uri.isValid" should {
    "be true for a valid value" in {
      Uri("http://example.org/thing").isValid shouldBe true
    }
    "be false for an invalid value" in {
      Uri("http://<>").isValid shouldBe false
    }
  }
  "Curie.isValid" should {
    "be true for a valid value" in {
      Curie("skos:exactMatch").isValid shouldBe true
    }
    "be false for an invalid value" in {
      Curie("<>").isValid shouldBe false
    }
  }
  "UriCurieValidator.isValidUri" should {
    "accept valid URIs" in {
      Seq(
        "http://www.w3.org/2004/02/skos/core#exactMatch",
        "urn:isbn:0451450523",
        "mailto:someone@example.org",
        "file:///tmp/a%20b.yaml",
        "a+b-c.d:",
        "http://user:pw@example.org:8080/a/b;c=d?q=1&r=/?x#f/?",
        "http://example.org:/",
        "http://127.0.0.1/",
        "http://[::]/",
        "http://[::1]:80",
        "http://[1::1]",
        "http://[ffff::]",
        "http://[1:2:3:4:5:6:7:8]",
        "http://[1:2:3:4:5:6:7::]",
        "http://[::2:3:4:5:6:7:8]",
        "http://[::ffff:192.168.0.1]",
        "http://[1:2:3:4:5:6:255.255.255.255]",
        "http://[v1.fe80::a+en1]",
        "http://@host",
        // RFC 3987 `ucschar` is allowed unencoded: this is an IRI, not a URI.
        "http://example.org/\u00e9",
        "https://onerecord.iata.org/ns/code-lists/CommodityCode#LIVE_MMLS_DOGS_L\u00f6wchen",
        "http://ex\u00e4mple.org/a",
        "http://example.org/a?q=\u00e9#f\u00e9",
        "http://example.org/\ud83d\ude00",
      ).foreach(s => withClue(s)(UriCurieValidator.isValidUri(s) shouldBe true))
    }
    "reject invalid URIs" in {
      Seq(
        "",
        ":",
        "1http://example.org",
        "http//example.org",
        "http://<>",
        "http://exa mple.org",
        // Outside `ucschar`: a C1 control, a noncharacter, and a lone surrogate.
        "http://example.org/\u0085",
        "http://example.org/\ufffe",
        "http://example.org/\ud83d",
        "http://example.org/%4",
        "http://example.org/%G1",
        "http://example.org#a#b",
        "http://example.org:80x",
        "http://example.org:1:2",
        "http://a@b@c",
        "http://[::1]x",
        "http://[::1",
        "http://[]",
        "http://[1::2::3]",
        "http://[:1:2:3:4:5:6:7]",
        "http://[1:2:3:4:5:6:7:]",
        "http://[1:2:3:4:5:6:7]",
        "http://[1:2:3:4:5:6:7:8:9]",
        "http://[1:2:3:4:5:6:7::8]",
        "http://[12345::]",
        "http://[::1.2.3]",
        "http://[::1.2.3.256]",
        "http://[::1.2.3.04]",
        "http://[1.2.3.4]",
        "http://[::1.2.3.4:5]",
        "http://[v1]",
        "http://[v.x]",
        "http://[v1.]",
      ).foreach(s => withClue(s)(UriCurieValidator.isValidUri(s) shouldBe false))
    }
  }
  "UriCurieValidator.isValidCurie" should {
    "accept valid CURIEs" in {
      Seq(
        "",
        "skos:exactMatch",
        "_a.b-c:d",
        ":local",
        "local",
        "IAO:0100001",
        "doi:10.1000/182",
        "ex:a/b:c?d:e#f:g",
        "ex://host:80/path",
        "//host/path",
        "/abs:path",
        "rel/a:b",
        "?q:1",
        "#f:1",
        "ex:%41",
        "ex:\u00e9",
      ).foreach(s => withClue(s)(UriCurieValidator.isValidCurie(s) shouldBe true))
    }
    "reject invalid CURIEs" in {
      Seq(
        "<>",
        "not a curie!",
        "1ex:a",
        "ex:a:b",
        "ex::a",
        "a:b:c",
        "ex:a#b#c",
        "ex:%4",
        "ex:\ufffe",
      ).foreach(s => withClue(s)(UriCurieValidator.isValidCurie(s) shouldBe false))
    }
  }
}
