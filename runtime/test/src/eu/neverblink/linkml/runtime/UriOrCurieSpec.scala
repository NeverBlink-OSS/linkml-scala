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
      UriOrCurie("just-a-local-name") shouldBe a[Relative]
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
  "BasicPrefixResolver" should {
    "expand curie" in {
      val resolver = new BasicPrefixResolver(
        "",
        Seq(
          ("IAO", Uri("http://purl.obolibrary.org/obo/IAO_")),
          ("OIO", Uri("http://www.geneontology.org/formats/oboInOwl#")),
          ("schema", Uri("http://schema.org/")),
          ("skos", Uri("http://www.w3.org/2004/02/skos/core#")),
        ),
        Uri("urn:test"),
      )
      resolver.expand(
        Curie("IAO:0100001"),
      ).value shouldBe "http://purl.obolibrary.org/obo/IAO_/0100001"
      resolver.expand(
        Curie("OIO:consider"),
      ).value shouldBe "http://www.geneontology.org/formats/oboInOwl#consider"
      resolver.expand(Curie("schema:CreativeWork")).value shouldBe "http://schema.org/CreativeWork"
      resolver.expand(
        Curie("skos:exactMatch"),
      ).value shouldBe "http://www.w3.org/2004/02/skos/core#exactMatch"
    }

    "compact uri" in {
      val resolver = new BasicPrefixResolver(
        "",
        Seq(
          ("IAO", Uri("http://purl.obolibrary.org/obo/IAO_")),
          ("OIO", Uri("http://www.geneontology.org/formats/oboInOwl#")),
          ("schema", Uri("http://schema.org/")),
          ("skos", Uri("http://www.w3.org/2004/02/skos/core#")),
        ),
        Uri("urn:test"),
      )

      resolver.compact(Uri("http://purl.obolibrary.org/obo/IAO_/0100001")) shouldBe Curie(
        "IAO:0100001",
      )
      resolver.compact(
        Uri(
          "http://www.geneontology.org/formats/oboInOwl#consider",
        ),
      ) shouldBe Curie("OIO:consider")
      resolver.compact(Uri("http://schema.org/CreativeWork")) shouldBe Curie("schema:CreativeWork")
      resolver.compact(
        Uri("http://www.w3.org/2004/02/skos/core#exactMatch"),
      ) shouldBe Curie("skos:exactMatch")
    }

    "return the input as-is if couldn't compact" in {
      val resolver = new BasicPrefixResolver("", Seq.empty, Uri("urn:test"))
      resolver.compact(Uri("http://www.w3.org/2004/02/skos/core#exactMatch")) shouldBe Uri(
        "http://www.w3.org/2004/02/skos/core#exactMatch",
      )
    }

    "provide name in error message" in {
      val resolver = new BasicPrefixResolver("some schema", Seq.empty, Uri("urn:test"))
      val ex = intercept[RuntimeException] {
        Curie("ex:blep").uriStr(using resolver)
      }
      ex.getMessage should include("some schema")
      ex.getMessage should include("ex:blep")
    }
  }
}
