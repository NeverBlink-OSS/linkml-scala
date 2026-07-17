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
    "throw exception for invalid input" in {
      assert(intercept[Throwable](UriOrCurie("<>")).getMessage.contains("Illegal curie value"))
      assert(intercept[Throwable](UriOrCurie("http://<>")).getMessage.contains("Illegal uri value"))
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
