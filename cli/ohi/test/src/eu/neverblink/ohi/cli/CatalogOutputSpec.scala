package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.{LinkmlDateTime, Uri}
import eu.neverblink.ohi.model.generated.{ArtifactOrigin, Format}
import eu.neverblink.ohi.model.generated.search.SearchHit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class CatalogOutputSpec extends AnyWordSpec, Matchers {
  private val upstreamUrl = "https://example.org/Z%20sensor+v2.ttl?revision=2"
  private val generatedUrl = "https://example.org/sensors.linkml.yaml"
  private val base = ProjectTestData.publication(
    "https://example.org/release/1/",
    Seq(upstreamUrl -> Format.OwlTurtle, generatedUrl -> Format.LinkmlYaml),
    Seq("https://example.org/dependency/"),
  )
  private val publication = base.copy(
    artifacts = Seq(
      base.artifacts.head.copy(roots = Seq(Uri(upstreamUrl)), sizeBytes = 12501),
      base.artifacts(1).copy(
        origin = ArtifactOrigin.Generated,
        roots = Seq(Uri("https://example.org/z.yaml"), Uri("https://example.org/a.yaml")),
      ),
    ),
    namespaceIri = Some(Uri("https://example.org/terms#")),
    preferredPrefix = Some("ex"),
    licenses = Seq("MIT"),
    keywords = Seq("sensors"),
  )
  private val expected = """Example ontology
    |  Ontology: test/example
    |  Version: 1
    |  Description: Synthetic selection metadata
    |  Release URL: https://example.org/release/1/
    |
    |Files
    |  Z sensor+v2.ttl
    |    Format: OWL (Turtle) · owl-turtle
    |    Size: 12,501 bytes
    |    Source: Upstream
    |    URL: https://example.org/Z%20sensor+v2.ttl?revision=2
    |    SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    |
    |  sensors.linkml.yaml
    |    Format: LinkML (YAML) · linkml-yaml
    |    Size: 4 bytes
    |    Source: Generated
    |    URL: https://example.org/sensors.linkml.yaml
    |    SHA-256: 0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
    |    Entry points
    |      https://example.org/z.yaml
    |      https://example.org/a.yaml
    |
    |Dependencies
    |  https://example.org/dependency/
    |
    |Release details
    |  Published to Hub: 2026-10-15T00:00:00Z
    |  Publisher: Example publisher
    |  RDF namespace: https://example.org/terms#
    |  Preferred prefix: ex
    |  License: MIT
    |  Tags: sensors""".stripMargin

  private def release(version: String): SearchHit =
    SearchHit(
      baseUrl = Uri(s"https://example.org/release/$version/"),
      description = "Synthetic release",
      formats = Seq.empty,
      licenses = Seq.empty,
      name = "example",
      namespace = "test",
      publishedAt = LinkmlDateTime("2026-10-15T00:00:00Z"),
      title = "Example ontology",
      version = version,
    )

  "CatalogOutput.publication" should {
    "show release fields in order and omit only entry points equal to the download URL" in {
      CatalogOutput.publication(publication, json = false) shouldBe expected
    }

    "label empty collections without inventing absent optional metadata" in {
      val output = CatalogOutput.publication(
        base.copy(artifacts = Seq.empty, dependencies = Seq.empty),
        json = false,
      )
      output should include("\nFiles\n  None\n\nDependencies\n  None\n")
      output should endWith("  License: Not supplied\n  Tags: Not supplied")
      output should not include "RDF namespace"
      output should not include "Preferred prefix"
    }

    "style headings and URLs without changing the plain result" in {
      val output = CatalogOutput.publication(publication, json = false, colored = true)
      output should startWith("\u001b[1mExample ontology\u001b[0m\n")
      output should include("\u001b[1mZ sensor+v2.ttl\u001b[0m")
      output should include("\u001b[36mtest/example\u001b[0m")
      output should include(s"\u001b[36m$upstreamUrl\u001b[0m")
      output.replaceAll("\u001b\\[[0-9;]*m", "") shouldBe expected
    }

    "wrap the description under its field while preserving complete long URLs" in {
      val url =
        "https://example.org/releases/temperature-and-humidity-sensors/2026-10-28/metadata.json?revision=2"
      val output = CatalogOutput.publication(
        base.copy(
          baseUrl = Uri(url),
          description =
            "This ontology describes measurements from temperature sensors and humidity sensors in rooms.",
        ),
        json = false,
      )
      output should include(
        "  Description: This ontology describes measurements from temperature sensors and\n" +
          "    humidity sensors in rooms.\n",
      )
      output should include(s"  Release URL: $url\n")
    }

    "list 10 versions after the release URL, then the count of the rest" in {
      val releases = (1 to 12).map(version => release(version.toString))
      val output = CatalogOutput.publication(
        base,
        json = false,
        versions = Some(CatalogOutput.Versions(None, releases)),
      )
      output should include(
        """  Release URL: https://example.org/release/1/
          |
          |Versions
          |  - test/example@12
          |  - test/example@11
          |  - test/example@10
          |  - test/example@9
          |  - test/example@8
          |  - test/example@7
          |  - test/example@6
          |  - test/example@5
          |  - test/example@4
          |  - test/example@3
          |  and 2 more
          |
          |Files
          |""".stripMargin,
      )
    }

    for url <- Seq("https://example.org/files/", "urn:example:artifact", "https://example.org/%") do
      s"preserve the metadata URL '$url' when it has no usable display name" in {
        val output = CatalogOutput.publication(
          base.copy(artifacts =
            Seq(base.artifacts.head.copy(url = Uri(url), roots = Seq(Uri(url)))),
          ),
          json = false,
        )
        output should include(s"\nFiles\n  $url\n")
        output should include(s"    URL: $url\n")
      }
  }
}
