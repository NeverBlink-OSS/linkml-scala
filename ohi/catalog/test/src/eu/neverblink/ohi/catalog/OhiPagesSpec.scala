package eu.neverblink.ohi.catalog

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class OhiPagesSpec extends AnyWordSpec, Matchers {
  "OhiPages.releasePage" should {
    "link a release page on ohi.dev" in {
      OhiPages.releasePage("w3c", "sosa", "2017") shouldBe "https://ohi.dev/o/w3c/sosa@2017"
    }

    "encode each part of the release page path" in {
      OhiPages.releasePage("a b", "x/y@z", "1.0+amendment.1 é") shouldBe
        "https://ohi.dev/o/a%20b/x%2Fy%40z@1.0%2Bamendment.1%20%C3%A9"
    }

    "leave the same characters as JavaScript" in {
      OhiPages.releasePage("-_.!~*'()", "n", "v") shouldBe "https://ohi.dev/o/-_.!~*'()/n@v"
    }

    "link the release of a search hit" in {
      val response = ujson.Obj(
        "hits" -> ujson.Arr(
          ujson.Obj(
            "document" -> ujson.Obj(
              "base_url" -> "urn:ohi:a",
              "namespace" -> "example",
              "name" -> "sensors",
              "version" -> "1.0.0+amendment.1",
              "title" -> "Sensors",
              "description" -> "Sensor measurements",
              "formats" -> ujson.Arr("owl-turtle"),
              "licenses" -> ujson.Arr("MIT"),
              "published_at" -> "2026-10-13T13:30:00+01:00",
            ),
          ),
        ),
        "found" -> 1,
        "page" -> 1,
        "request_params" -> ujson.Obj("per_page" -> 1),
        "search_cutoff" -> false,
      )
      val hit = CatalogClient.decodeSearch(ujson.write(response), 1, 1).hits.head
      OhiPages.releasePage(hit) shouldBe "https://ohi.dev/o/example/sensors@1.0.0%2Bamendment.1"
    }
  }
}
