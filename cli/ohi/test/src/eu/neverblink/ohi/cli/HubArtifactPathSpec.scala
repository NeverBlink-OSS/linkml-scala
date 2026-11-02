package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.fileerror.CliFileErrorCode
import eu.neverblink.ohi.model.generated.lock.Format
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Path

class HubArtifactPathSpec extends AnyWordSpec, Matchers {
  private val base = "https://hub.example/files/packages/w3c/dcat/3/"

  "Hub artifact paths" should {
    for (format, expected) <- Seq(
        Format.LinkmlYaml -> "w3c/dcat/3/dcat.linkml.yaml",
        Format.OwlTurtle -> "w3c/dcat/3/dcat.owl.ttl",
        Format.OwlRdfxml -> "w3c/dcat/3/dcat.owl",
        Format.RdfsTurtle -> "w3c/dcat/3/dcat.rdfs.ttl",
        Format.OssieYaml -> "w3c/dcat/3/dcat.ossie.yaml",
      )
    do
      s"use the declared $format suffix and publication name instead of the advertised basename" in {
        HubArtifactPath.relative(base, base + "legacy/dcat3.yaml", format, ".ohi") shouldBe Path.of(
          expected,
        )
      }

    for (publication, expected) <- Seq(
        "https://hub.example/files/packages/other/dcat/3/" -> "other/dcat/3/dcat.linkml.yaml",
        "https://hub.example/files/packages/w3c/dcat/3.0.1/" -> "w3c/dcat/3.0.1/dcat.linkml.yaml",
      )
    do
      s"retain the namespace and complete version in $expected" in {
        HubArtifactPath.relative(
          publication,
          publication + "same.yaml",
          Format.LinkmlYaml,
          ".ohi",
        ) shouldBe Path.of(expected)
      }

    for (name, expected) <- Seq(
        "a%41" -> "a%2541",
        "aA" -> "a%41",
        "a+b:c" -> "a%2Bb%3Ac",
        "café" -> "caf%C3%A9",
        "name." -> "name%2E",
        "con.txt" -> "%63on.txt",
        "lpt9" -> "%6Cpt9",
      )
    do
      s"encode identity $name injectively as $expected" in {
        val publication = s"https://hub.example/files/packages/ns/$name/1/"
        HubArtifactPath.relative(
          publication,
          publication + "raw.yaml",
          Format.LinkmlYaml,
          ".ohi",
        ) shouldBe
          Path.of(s"ns/$expected/1/$expected.linkml.yaml")
      }

    "extract raw identity from an artifact on another Hub without decoding percent escapes" in {
      val identity = HubArtifactPath.artifact(
        "https://other.example/files/packages/n%41/a%41/v%41/nested/file.yaml",
        ".ohi",
      )
      identity.baseUrl shouldBe "https://other.example/files/packages/n%41/a%41/v%41/"
      identity.namespace shouldBe "n%41"
      identity.name shouldBe "a%41"
      identity.version shouldBe "v%41"
    }

    for invalid <- Seq(
        "https://hub.example/packages/w3c/dcat/3/",
        "https://hub.example/files/packages/w3c/dcat/",
        "https://hub.example/files/packages//dcat/3/",
        "https://hub.example/files/packages/w3c/./3/",
        "https://hub.example/files/packages/w3c/dcat/../",
        base + "?query=1",
        base + "#fragment",
        "urn:publication",
      )
    do
      s"reject malformed publication route $invalid" in {
        val error = intercept[CliFileException](HubArtifactPath.publication(invalid, ".ohi")).error
        error.code shouldBe CliFileErrorCode.UnsupportedFilename
        error.url shouldBe invalid
        error.directory shouldBe ".ohi"
        error.path shouldBe None
      }

    for invalid <- Seq(
        base,
        base + "../other/file.yaml",
        base + "file.yaml?query=1",
        "https://other.example/files/packages/w3c/dcat/3/file.yaml",
        "http://hub.example/files/packages/w3c/dcat/3/file.yaml",
        "https://hub.example/files/packages/w3c/dcat/4/file.yaml",
      )
    do
      s"reject artifact $invalid outside the canonical publication" in {
        val error = intercept[CliFileException](
          HubArtifactPath.relative(base, invalid, Format.LinkmlYaml, ".ohi"),
        ).error
        error.code shouldBe CliFileErrorCode.UnsupportedFilename
        error.url shouldBe invalid
        error.path shouldBe None
      }
  }
}
