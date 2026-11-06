package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.model.generated.{Artifact, Format, Publication}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable.ArrayBuffer

class OntologyReferenceSpec extends AnyWordSpec, Matchers {
  private val metadata = ProjectTestData.publication(
    "https://Catalog.example/releases/exact?key=%2F",
    Seq(
      "https://files.example/linkml.yaml" -> Format.LinkmlYaml,
      "https://Files.example/advertised%2F.ttl" -> Format.OwlTurtle,
    ),
  ).copy(namespace = "w3c", name = "prov-o")

  private def noInspect(url: String): Publication = fail(s"Unexpected inspection: $url")

  private def resolve(
      input: String,
      format: Option[String],
      inspect: String => Publication,
      preference: => Option[Format] = None,
  ): Either[String, (Publication, Artifact)] =
    OntologyReference.resolve(input, format, inspect, preference).left.map(_.message)

  "ontology reference classification" should {
    "recognize HTTP(S) prefixes without treating a colon-bearing namespace as a URL" in {
      OntologyReference.isUrl("HTTP://example.org/file") shouldBe true
      OntologyReference.isUrl("hTtPs://example.org/file") shouldBe true
      OntologyReference.isUrl("https:team/model") shouldBe false
      OntologyReference.isUrl("ftp://example.org/file") shouldBe false
    }
  }

  "ontology reference resolution" should {
    "default to LinkML when no project format is preferred" in {
      val result = resolve("w3c/prov-o", None, _ => metadata)
      result.map(_._2.url.original) shouldBe Right("https://files.example/linkml.yaml")
    }

    "select the project format before an available LinkML artifact" in {
      val result = resolve("w3c/prov-o", None, _ => metadata, Some(Format.OwlTurtle))
      result.map(_._2.url.original) shouldBe Right("https://Files.example/advertised%2F.ttl")
    }

    "fall back to LinkML in the selected publication when its project format is absent" in {
      val inspections = ArrayBuffer.empty[String]
      val result = resolve(
        "w3c/prov-o",
        None,
        reference => {
          inspections += reference
          metadata
        },
        Some(Format.OwlRdfxml),
      )
      result.map(_._2.url.original) shouldBe Right("https://files.example/linkml.yaml")
      inspections.toSeq shouldBe Seq("w3c/prov-o")
    }

    "reject unavailable preferred formats instead of choosing another advertised format" in {
      resolve(
        "w3c/prov-o",
        None,
        _ => metadata.copy(artifacts = metadata.artifacts.filter(_.format == Format.OwlTurtle)),
        Some(Format.OwlRdfxml),
      ) shouldBe Left(
        "Formats 'owl-rdfxml' or 'linkml-yaml' are unavailable for ontology reference 'w3c/prov-o': " +
          "https://Catalog.example/releases/exact?key=%2F",
      )
    }

    "keep an ambiguous preferred format ambiguous when LinkML is available" in {
      resolve(
        "w3c/prov-o",
        None,
        _ =>
          metadata.copy(artifacts =
            metadata.artifacts :+
              metadata.artifacts.last.copy(url = Uri("https://files.example/second.ttl")),
          ),
        Some(Format.OwlTurtle),
      ) shouldBe Left("w3c/prov-o has more than one file in owl-turtle format.")
    }

    "honor an explicit format without evaluating the project preference" in {
      val result = resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ => metadata,
        fail("Explicit format must not read the project"),
      )
      result.map { (publication, artifact) =>
        publication.baseUrl.original -> artifact.url.original
      } shouldBe Right(
        "https://Catalog.example/releases/exact?key=%2F" ->
          "https://Files.example/advertised%2F.ttl",
      )
    }

    "reject a malformed reference with the CLI message before inspecting" in {
      resolve("w3c/prov-o@", None, noInspect) shouldBe
        Left("Invalid ontology reference 'w3c/prov-o@'. Expected namespace/name[@version].")
    }

    "reject an unknown format before inspecting" in {
      resolve("w3c/prov-o", Some("owl-jsonld"), noInspect) shouldBe
        Left("Invalid format 'owl-jsonld'.")
    }

    "refuse an absent format in the resolved release without another lookup" in {
      val inspections = ArrayBuffer.empty[String]
      val result = resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        reference => {
          inspections += reference
          metadata.copy(artifacts = metadata.artifacts.filter(_.format == Format.LinkmlYaml))
        },
      )
      result shouldBe Left(
        "Format 'owl-turtle' is unavailable for ontology reference 'w3c/prov-o': " +
          "https://Catalog.example/releases/exact?key=%2F",
      )
      inspections.toSeq shouldBe Seq("w3c/prov-o")
    }

    "refuse multiple artifacts of the requested format instead of choosing the first" in {
      resolve(
        "w3c/prov-o",
        Some("owl-turtle"),
        _ =>
          metadata.copy(artifacts =
            Seq(
              metadata.artifacts.last,
              metadata.artifacts.last.copy(url = Uri("https://files.example/second.ttl")),
            ),
          ),
      ) shouldBe Left(
        "w3c/prov-o has more than one file in owl-turtle format.",
      )
    }
  }
}
