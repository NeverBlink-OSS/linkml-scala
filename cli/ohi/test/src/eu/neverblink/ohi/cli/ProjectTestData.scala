package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.{LinkmlDateTime, Uri}
import eu.neverblink.ohi.model.generated.*

private[cli] object ProjectTestData {
  // Synthetic metadata isolates project behavior from SDK decoding and HTTP transport.
  def publication(
      baseUrl: String,
      artifacts: Seq[(String, Format)],
      dependencies: Seq[String] = Seq.empty,
  ): Publication = Publication(
    artifacts = artifacts.map { (url, format) =>
      Artifact(
        format = format,
        origin = ArtifactOrigin.Upstream,
        roots = Seq(Uri("https://example.org/entry-root")),
        sha256 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        sizeBytes = 4,
        url = Uri(url),
      )
    },
    baseUrl = Uri(baseUrl),
    dependencies = dependencies.map(url => Dependency(Uri(url))),
    description = "Synthetic selection metadata",
    keywords = Seq.empty,
    licenses = Seq.empty,
    name = "example",
    namespace = "test",
    publishedAt = LinkmlDateTime("2026-10-15T00:00:00Z"),
    publisher = "Example publisher",
    schemaVersion = 2,
    title = "Example ontology",
    version = "1",
  )
}
