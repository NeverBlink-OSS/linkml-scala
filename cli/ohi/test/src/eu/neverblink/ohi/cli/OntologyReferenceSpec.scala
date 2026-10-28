package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.{LinkmlDateTime, Uri}
import eu.neverblink.ohi.model.generated.{Format, Publication}
import eu.neverblink.ohi.model.generated.search.{SearchHit, SearchResult}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable.ArrayBuffer

class OntologyReferenceSpec extends AnyWordSpec, Matchers {
  private val hit = SearchHit(
    baseUrl = Uri("https://Catalog.example/releases/exact?key=%2F"),
    description = "Synthetic reference lookup",
    formats = Seq(eu.neverblink.ohi.model.generated.search.Format.OwlTurtle),
    licenses = Seq.empty,
    name = "prov-o",
    namespace = "w3c",
    publishedAt = LinkmlDateTime("2026-10-27T00:00:00Z"),
    title = "Reference fixture",
    version = "1",
  )
  private val metadata = ProjectTestData.publication(
    "https://Catalog.example/releases/exact?key=%2F",
    Seq(
      "https://files.example/unselected.yaml" -> Format.LinkmlYaml,
      "https://Files.example/advertised%2F.ttl" -> Format.OwlTurtle,
    ),
  ).copy(namespace = "w3c", name = "prov-o")
  private val nearMatches = Seq.fill(250)(hit.copy(namespace = "W3C"))

  private def page(hits: Seq[SearchHit], total: Int, number: Int = 1): SearchResult =
    SearchResult(hits = hits, page = number, perPage = 250, searchCutoff = false, total = total)

  private def noSearch(request: SearchRequest): SearchResult =
    fail(s"Unexpected search: $request")

  private def noInspect(url: String): Publication = fail(s"Unexpected inspection: $url")

  "ontology reference classification" should {
    "recognize HTTP(S) prefixes without treating a colon-bearing namespace as a URL" in {
      OntologyReference.isUrl("HTTP://example.org/file") shouldBe true
      OntologyReference.isUrl("hTtPs://example.org/file") shouldBe true
      OntologyReference.isUrl("https:team/model") shouldBe false
      OntologyReference.isUrl("ftp://example.org/file") shouldBe false
    }
  }

  "ontology reference resolution" should {
    "require a format before searching" in {
      OntologyReference.resolve("w3c/prov-o", None, noSearch, noInspect) shouldBe
        Left("--format is required for ontology reference 'w3c/prov-o'.")
    }

    "reject an unknown format before searching" in {
      OntologyReference.resolve("w3c/prov-o", Some("owl-jsonld"), noSearch, noInspect) shouldBe
        Left("Invalid format 'owl-jsonld'.")
    }

    for (input, defect) <- Seq(
        "w3c" -> "missing name",
        "/prov-o" -> "empty namespace",
        "w3c/" -> "empty name",
        "w3c/prov-o/extra" -> "extra component",
        "w3c/prov-o@" -> "empty version",
        "w3c/prov-o@v+1" -> "plus in version",
        "w3c/prov-o@v@1" -> "extra version separator",
        " w3c/prov-o" -> "leading space",
        "w3c/prov\u00a0o" -> "Unicode whitespace",
        "w3c/prov`o" -> "unquotable backtick",
      )
    do
      s"reject $defect before searching" in {
        OntologyReference.resolve(input, Some("owl-turtle"), noSearch, noInspect) shouldBe
          Left(
            s"Invalid ontology reference '$input'. Expected namespace/name[@version] without backticks.",
          )
      }

    for (timestamp, behavior) <- Seq(
        "2020-01-01T00:00:00Z" -> "choose the newest publication when its version sorts lower",
        "2026-10-27T00:00:00Z" -> "retain backend order when publication timestamps tie",
      )
    do
      behavior in {
        val searches = ArrayBuffer.empty[SearchRequest]
        val inspections = ArrayBuffer.empty[String]
        val result = OntologyReference.resolve(
          "w3c/prov-o",
          Some("owl-turtle"),
          request => {
            searches += request
            page(Seq(hit, hit.copy(version = "9", publishedAt = LinkmlDateTime(timestamp))), 2)
          },
          url => {
            inspections += url
            metadata
          },
        )
        searches.toSeq shouldBe Seq(
          SearchRequest(
            q = "*",
            page = Some(1),
            perPage = Some(250),
            filterBy = Some("namespace:=w3c && name:=prov-o"),
            sortBy = Some("published_at:desc"),
          ),
        )
        inspections.toSeq shouldBe Seq("https://Catalog.example/releases/exact?key=%2F")
        result.map { (publication, artifact) =>
          (publication.baseUrl.original, publication.version, artifact.url.original)
        } shouldBe Right(
          (
            "https://Catalog.example/releases/exact?key=%2F",
            "1",
            "https://Files.example/advertised%2F.ttl",
          ),
        )
      }

    "quote opaque components and select the exact namespace, name and explicit version" in {
      val exact = hit.copy(namespace = "https:team", name = "Model+%2F", version = "v:1")
      val searches = ArrayBuffer.empty[SearchRequest]
      val result = OntologyReference.resolve(
        "https:team/Model+%2F@v:1",
        Some("owl-turtle"),
        request => {
          searches += request
          page(
            Seq(
              exact.copy(namespace = "HTTPS:team"),
              exact.copy(name = "Model%2F"),
              exact.copy(version = "v1"),
              exact,
            ),
            4,
          )
        },
        url => {
          url shouldBe "https://Catalog.example/releases/exact?key=%2F"
          metadata.copy(namespace = "https:team", name = "Model+%2F", version = "v:1")
        },
      )
      searches.map(_.filterBy).toSeq shouldBe
        Seq(Some("namespace:=`https:team` && name:=`Model+%2F` && version:=`v:1`"))
      result.map { (publication, artifact) =>
        (publication.namespace, publication.name, publication.version, artifact.url.original)
      } shouldBe Right(
        (
          "https:team",
          "Model+%2F",
          "v:1",
          "https://Files.example/advertised%2F.ttl",
        ),
      )
    }

    "continue past a full page of near-matches to the next exact match" in {
      val pages = ArrayBuffer.empty[Option[Int]]
      val result = OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        request => {
          pages += request.page
          request.page match {
            case Some(1) => page(nearMatches, 251)
            case Some(2) => page(Seq(hit), 251, 2)
            case other => fail(s"Unexpected page: $other")
          }
        },
        url => {
          url shouldBe "https://Catalog.example/releases/exact?key=%2F"
          metadata
        },
      )
      pages.toSeq shouldBe Seq(Some(1), Some(2))
      result.map { (publication, artifact) =>
        (publication.version, artifact.url.original)
      } shouldBe Right(("1", "https://Files.example/advertised%2F.ttl"))
    }

    "report no release when search has no hits" in {
      OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ => page(Seq.empty, 0),
        noInspect,
      ) shouldBe Left("No matching release for ontology reference 'w3c/prov-o'.")
    }

    "report exhaustion for a complete final page of near-matches" in {
      val pages = ArrayBuffer.empty[Option[Int]]
      OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        request => {
          pages += request.page
          request.page match {
            case Some(1) => page(nearMatches, 251)
            case Some(2) => page(Seq(hit.copy(name = "provo")), 251, 2)
            case other => fail(s"Unexpected page: $other")
          }
        },
        noInspect,
      ) shouldBe Left("No matching release for ontology reference 'w3c/prov-o'.")
      pages.toSeq shouldBe Seq(Some(1), Some(2))
    }

    "reject cutoff before inspecting an exact hit" in {
      OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ => page(Seq(hit), 1).copy(searchCutoff = true),
        noInspect,
      ) shouldBe Left("Search stopped early for ontology reference 'w3c/prov-o'.")
    }

    "reject an empty unfinished page before inspecting" in {
      OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ => page(Seq.empty, 251),
        noInspect,
      ) shouldBe Left("Incomplete search for ontology reference 'w3c/prov-o' on page 1.")
    }

    "reject a nonempty short page before inspecting" in {
      OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ => page(nearMatches.take(1), 251),
        noInspect,
      ) shouldBe Left("Incomplete search for ontology reference 'w3c/prov-o' on page 1.")
    }

    for (changed, field) <- Seq(
        metadata.copy(baseUrl =
          Uri("https://catalog.example/releases/exact?key=%2F"),
        ) -> "base URL",
        metadata.copy(namespace = "W3C") -> "namespace",
        metadata.copy(name = "provo") -> "name",
        metadata.copy(version = "9") -> "version",
      )
    do
      s"reject an inspected $field that differs from the selected hit" in {
        OntologyReference.resolve(
          "w3c/prov-o",
          Some("owl-turtle"),
          _ => page(Seq(hit), 1),
          url => {
            url shouldBe "https://Catalog.example/releases/exact?key=%2F"
            changed
          },
        ) shouldBe Left(
          "Inspected publication does not match ontology reference 'w3c/prov-o': " +
            "https://Catalog.example/releases/exact?key=%2F",
        )
      }

    "refuse an absent format on the newest release without trying an older release" in {
      val inspections = ArrayBuffer.empty[String]
      val result = OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ =>
          page(
            Seq(
              hit.copy(formats = Seq(eu.neverblink.ohi.model.generated.search.Format.LinkmlYaml)),
              hit.copy(baseUrl = Uri("https://example.org/older"), version = "9"),
            ),
            2,
          ),
        url => {
          inspections += url
          metadata.copy(artifacts = metadata.artifacts.filter(_.format == Format.LinkmlYaml))
        },
      )
      result shouldBe Left(
        "Format 'owl-turtle' is unavailable for ontology reference 'w3c/prov-o': " +
          "https://Catalog.example/releases/exact?key=%2F",
      )
      inspections.toSeq shouldBe Seq("https://Catalog.example/releases/exact?key=%2F")
    }

    "refuse multiple artifacts of the requested format instead of choosing the first" in {
      OntologyReference.resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ => page(Seq(hit), 1),
        _ =>
          metadata.copy(artifacts =
            Seq(
              metadata.artifacts.last,
              metadata.artifacts.last.copy(url = Uri("https://files.example/second.ttl")),
            ),
          ),
      ) shouldBe Left(
        "Format 'owl-turtle' is ambiguous for ontology reference 'w3c/prov-o': " +
          "https://Catalog.example/releases/exact?key=%2F",
      )
    }
  }
}
