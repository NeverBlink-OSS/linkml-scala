package eu.neverblink.ohi.catalog

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.{LinkmlYamlCodec, NodeJson}
import eu.neverblink.ohi.model.generated.error.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CatalogJsonSpec extends AnyWordSpec, Matchers {
  private val errorCodec: LinkmlYamlCodec[CatalogError] = LinkmlYamlCodec.derived

  private def response(
      page: Int = 1,
      urls: Seq[String] = Seq("urn:ohi:b", "urn:ohi:a"),
  ): ujson.Value =
    ujson.Obj(
      "hits" -> ujson.Arr.from(
        urls.map(url =>
          ujson.Obj(
            "document" -> ujson.Obj(
              "base_url" -> url,
              "namespace" -> "example",
              "name" -> "sensors",
              "version" -> "1.0.0+amendment.1",
              "title" -> "Sensors",
              "description" -> "Sensor measurements",
              "formats" -> ujson.Arr(
                "owl-rdfxml",
                "owl-turtle",
                "ossie-yaml",
                "rdfs-turtle",
                "linkml-yaml",
              ),
              "licenses" -> ujson.Arr("MIT", "Apache-2.0"),
              "published_at" -> "2026-10-13T13:30:00+01:00",
            ),
          ),
        ),
      ),
      "found" -> 3,
      "page" -> page,
      "request_params" -> ujson.Obj("per_page" -> 2),
      "search_cutoff" -> false,
    )

  "CatalogClient.decodeSearch" should {
    for (
      (page, urls) <- Seq(1 -> Seq("urn:ohi:b", "urn:ohi:a"), 2 -> Seq("urn:ohi:c"), 3 -> Seq.empty)
    ) {
      s"preserve counts on page $page including partial and empty pages" in {
        val result = CatalogClient.decodeSearch(ujson.write(response(page, urls)), page, 2)
        result.hits.map(_.baseUrl.original) shouldBe urls
        result.total shouldBe 3
        result.page shouldBe page
        result.perPage shouldBe 2
        result.searchCutoff shouldBe false
        result.facetCounts shouldBe empty
      }
    }

    "preserve every publication summary field and exact format value" in {
      val raw = response()
      raw("hits")(0)("document")("extra") = "ignored"
      raw("hits")(0)("document")("keywords") = ujson.Arr("sensors", "observations")
      val hit = CatalogClient.decodeSearch(ujson.write(raw), 1, 2).hits.head
      hit.baseUrl.original shouldBe "urn:ohi:b"
      hit.namespace shouldBe "example"
      hit.name shouldBe "sensors"
      hit.version shouldBe "1.0.0+amendment.1"
      hit.title shouldBe "Sensors"
      hit.description shouldBe "Sensor measurements"
      hit.formats.map(stringify(_)) shouldBe Seq(
        "owl-rdfxml",
        "owl-turtle",
        "ossie-yaml",
        "rdfs-turtle",
        "linkml-yaml",
      )
      hit.licenses shouldBe Seq("MIT", "Apache-2.0")
      hit.keywords shouldBe Seq("sensors", "observations")
      hit.publishedAt.value shouldBe "2026-10-13T13:30:00+01:00"
    }

    "preserve facet field and value order with integral counts and empty groups" in {
      val raw = response(urls = Seq.empty)
      raw("facet_counts") = ujson.read("""[
        {"field_name":"namespace","extra":true,"counts":[
          {"value":"z","count":2147483647,"extra":true},
          {"value":"a","count":0}]},
        {"field_name":"formats","counts":[]}
      ]""")
      val facets = CatalogClient.decodeSearch(ujson.write(raw), 1, 2).facetCounts
      facets.map(_.fieldName) shouldBe Seq("namespace", "formats")
      facets.head.counts.map(c => c.value -> c.count) shouldBe Seq(
        "z" -> Int.MaxValue,
        "a" -> 0,
      )
      facets(1).counts shouldBe empty
    }

    "accept an empty facet list" in {
      val raw = response()
      raw("facet_counts") = ujson.Arr()
      val result = CatalogClient.decodeSearch(ujson.write(raw), 1, 2)
      result.facetCounts shouldBe empty
    }

    "preserve cutoff on a short capped page" in {
      val raw = response(urls = Seq("urn:ohi:b"))
      raw("search_cutoff") = true
      val result = CatalogClient.decodeSearch(ujson.write(raw), 1, 2)
      result.searchCutoff shouldBe true
      result.total shouldBe 3
      result.hits.map(_.baseUrl.original) shouldBe Seq("urn:ohi:b")
    }

    for (
      (name, edit) <- Seq[(String, ujson.Value => Unit)](
        "missing count" -> (v => { v.obj.remove("found"); () }),
        "missing cutoff" -> (v => { v.obj.remove("search_cutoff"); () }),
        "missing reference" -> (v => { v("hits")(0)("document").obj.remove("base_url"); () }),
        "missing summary title" -> (v => { v("hits")(0)("document").obj.remove("title"); () }),
        "language-only format" -> (v => v("hits")(0)("document")("formats") = ujson.Arr("owl")),
        "missing facet field" -> (v => v("facet_counts") = ujson.read("""[{"counts":[]}]""")),
        "missing facet counts" -> (v =>
          v("facet_counts") = ujson.read("""[{"field_name":"formats"}]""")
        ),
        "grouped response" -> (v => v("grouped_hits") = ujson.Arr()),
        "nonarray hits" -> (v => v("hits") = ujson.Obj()),
        "nonobject document" -> (v => v("hits")(0)("document") = ujson.Null),
        "nonobject request params" -> (v => v("request_params") = ujson.Arr()),
        "page mismatch" -> (v => v("page") = 2),
        "page size mismatch" -> (v => v("request_params")("per_page") = 3),
        "too many hits" -> (v => { v("hits").arr += v("hits")(0); () }),
      )
    ) {
      s"reject $name" in {
        val raw = response()
        edit(raw)
        intercept[IllegalArgumentException](CatalogClient.decodeSearch(ujson.write(raw), 1, 2))
      }
    }

    for (count <- Seq(0.5d, 2147483648d)) {
      s"reject unrepresentable facet count $count" in {
        val raw = response()
        raw("facet_counts") = ujson.Arr(
          ujson.Obj(
            "field_name" -> "formats",
            "counts" -> ujson.Arr(ujson.Obj("value" -> "owl-turtle", "count" -> count)),
          ),
        )
        intercept[IllegalArgumentException](CatalogClient.decodeSearch(ujson.write(raw), 1, 2))
      }
    }

    "accept the maximum Scala count without wrapping" in {
      val raw = response()
      raw("found") = ujson.Num(2147483647d)
      CatalogClient.decodeSearch(ujson.write(raw), 1, 2).total shouldBe Int.MaxValue
    }

    "reject an integer count outside the Scala range" in {
      val raw = response()
      raw("found") = ujson.Num(2147483648d)
      intercept[IllegalArgumentException](CatalogClient.decodeSearch(ujson.write(raw), 1, 2))
        .getMessage should include("int")
    }
  }

  "CatalogError encoding" should {
    "embed server objects and nested nulls rather than the LinkmlAny wrapper" in {
      val error = CatalogError(
        code = CatalogErrorCode.HttpError,
        message = "unavailable",
        operation = CatalogOperation.Search,
        url = Some("https://example.org/search"),
        status = Some(503),
        responseJson = CatalogClient.responseObject(
          """{"details":{"unknown":[1,null]},"recovery":{"args":{"key":null}}}""",
        ),
      )
      ujson.read(NodeJson.write(errorCodec.encode(error))) shouldBe ujson.read(
        """{"code":"HTTP_ERROR","message":"unavailable","operation":"SEARCH","url":"https://example.org/search","status":503,"response_json":{"details":{"unknown":[1,null]},"recovery":{"args":{"key":null}}}}""",
      )
    }

    "omit absent fields on pre-request errors" in {
      val error =
        CatalogError(CatalogErrorCode.InvalidInput, "configure search", CatalogOperation.Search)
      ujson.read(NodeJson.write(errorCodec.encode(error))) shouldBe ujson.Obj(
        "code" -> "INVALID_INPUT",
        "message" -> "configure search",
        "operation" -> "SEARCH",
      )
    }
  }
}
