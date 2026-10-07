package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class UriOrCurieSpec extends AnyWordSpec, Matchers {
  "UriOrCurie" should {
    "return valid Uri" in {
      UriOrCurie("http://www.w3.org/2004/02/skos/core#exactMatch") shouldBe Uri(
        "http://www.w3.org/2004/02/skos/core#exactMatch",
      )
    }
    "return valid URN URI" in {
      UriOrCurie("urn:isbn:0451450523") shouldBe Uri("urn:isbn:0451450523")
    }
    "return valid Curie" in {
      UriOrCurie("skos:exactMatch") shouldBe Curie("skos:exactMatch")
    }
    "dispatch to Uri or Curie based on the string shape" in {
      UriOrCurie("http://example.org/thing") shouldBe a[Uri]
      UriOrCurie("urn:isbn:0451450523") shouldBe a[Uri]
      UriOrCurie("skos:exactMatch") shouldBe a[Curie]
      UriOrCurie("just-a-local-name") shouldBe a[Curie]
    }
    "not validate on construction" in {
      // Construction never inspects the value; only validate() does.
      noException should be thrownBy UriOrCurie("<>")
      noException should be thrownBy UriOrCurie("http://<>")
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
        "http://example.org/é",
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
        "ex:é",
      ).foreach(s => withClue(s)(UriCurieValidator.isValidCurie(s) shouldBe false))
    }
  }
  "BasicPrefixResolver" should {
    "expand curie" in {
      val resolver = new BasicPrefixResolver("")
      resolver.add("IAO", "http://purl.obolibrary.org/obo/IAO_")
      resolver.add("OIO", "http://www.geneontology.org/formats/oboInOwl#")
      resolver.add("schema", "http://schema.org/")
      resolver.add("skos", "http://www.w3.org/2004/02/skos/core#")
      resolver.expand("IAO:0100001") shouldBe "http://purl.obolibrary.org/obo/IAO_/0100001"
      resolver.expand(
        "OIO:consider",
      ) shouldBe "http://www.geneontology.org/formats/oboInOwl#consider"
      resolver.expand("schema:CreativeWork") shouldBe "http://schema.org/CreativeWork"
      resolver.expand("skos:exactMatch") shouldBe "http://www.w3.org/2004/02/skos/core#exactMatch"
    }
    "compact uri" in {
      val resolver = new BasicPrefixResolver("")
      resolver.add("IAO", "http://purl.obolibrary.org/obo/IAO_")
      resolver.add("OIO", "http://www.geneontology.org/formats/oboInOwl#")
      resolver.add("schema", "http://schema.org/")
      resolver.add("skos", "http://www.w3.org/2004/02/skos/core#")
      resolver.compact("http://purl.obolibrary.org/obo/IAO_/0100001") shouldBe "IAO:0100001"
      resolver.compact(
        "http://www.geneontology.org/formats/oboInOwl#consider",
      ) shouldBe "OIO:consider"
      resolver.compact("http://schema.org/CreativeWork") shouldBe "schema:CreativeWork"
      resolver.compact("http://www.w3.org/2004/02/skos/core#exactMatch") shouldBe "skos:exactMatch"
    }
    "provide name in error message" in {
      val resolver = new BasicPrefixResolver("some schema")
      val ex = intercept[RuntimeException] {
        Curie("ex:blep").uri(using resolver)
      }
      ex.getMessage should include("some schema")
      ex.getMessage should include("ex:blep")
    }
  }
}
