package eu.neverblink.ohi.model

import eu.neverblink.ohi.model.generated.{ArtifactOrigin, Format, Publication}
import java.nio.file.{Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PublicationJsonSpec extends AnyWordSpec, Matchers {
  private def example(name: String = "sensors"): ujson.Value =
    ujson.read(
      Files.readString(
        Path.of(sys.env("MILL_WORKSPACE_ROOT"), "ohi", "examples", s"$name-package.json"),
      ),
    )

  private def decode(value: ujson.Value): Publication = PublicationJson.decode(ujson.write(value))

  private def reject(edit: ujson.Value => Unit): Unit = {
    val value = example()
    edit(value)
    intercept[IllegalArgumentException](decode(value))
  }

  "PublicationJson.decode" should {
    "map publication fields" in {
      val publication = decode(example())
      publication.schemaVersion shouldBe 2
      publication.baseUrl.original shouldBe "https://example.org/packages/sensors/1.0.0/"
      publication.version shouldBe "1.0.0"
      publication.namespace shouldBe "example"
      publication.name shouldBe "sensors"
      publication.title shouldBe "Sensors"
      publication.publishedAt.value shouldBe "2026-10-13T12:30:00Z"
      publication.publisher shouldBe "Example Project"
      publication.licenses shouldBe Seq("Apache-2.0")
      publication.namespaceIri.map(_.original) shouldBe Some("https://example.org/sensors#")
      publication.preferredPrefix shouldBe Some("sensors")
      publication.artifacts.map(_.sizeBytes) shouldBe Seq(42, 42)
      publication.artifacts.map(_.sha256) shouldBe Seq.fill(2)("0123456789abcdef" * 4)
      publication.description shouldBe "Sensors and their observations."
      publication.keywords shouldBe Seq("sensors", "observations")
      publication.artifacts.map(a =>
        (a.format, a.origin, a.url.original, a.roots.map(_.original)),
      ) shouldBe Seq(
        (
          Format.OwlTurtle,
          ArtifactOrigin.Upstream,
          "https://example.org/packages/sensors/1.0.0/sensors.ttl",
          Seq("https://example.org/packages/sensors/1.0.0/sensors.ttl"),
        ),
        (
          Format.LinkmlYaml,
          ArtifactOrigin.Generated,
          "https://example.org/packages/sensors/1.0.0/sensors.yaml",
          Seq("https://example.org/packages/sensors/1.0.0/sensors.yaml"),
        ),
      )
      publication.dependencies.map(_.baseUrl.original) shouldBe Seq(
        "https://example.org/packages/units/2.0.0/",
      )
    }

    "accept no dependencies" in {
      val publication = decode(example("units"))
      publication.baseUrl.original shouldBe "https://example.org/packages/units/2.0.0/"
      publication.artifacts.head.url.original shouldBe "https://example.org/packages/units/2.0.0/units.ttl"
      publication.dependencies shouldBe empty
    }

    "accept empty keywords" in {
      decode(example("units")).keywords shouldBe empty
    }

    "accept omitted namespace metadata" in {
      val publication = decode(example("units"))
      publication.namespaceIri shouldBe None
      publication.preferredPrefix shouldBe None
    }

    "require namespace IRI when a preferred prefix is supplied" in {
      reject(value => value.obj.remove("namespace_iri"))
    }

    "preserve timestamp precision and spelling" in {
      val value = example()
      value("published_at") = "2026-10-13T12:30:00.123456789+00:00"
      decode(value).publishedAt.value shouldBe "2026-10-13T12:30:00.123456789+00:00"
    }

    "accept the maximum Scala artifact size" in {
      val value = example()
      value("artifacts")(0)("size_bytes") = ujson.Num(2147483647d)
      decode(value).artifacts.head.sizeBytes shouldBe Int.MaxValue
    }

    "reject an artifact size that would overflow Scala Int" in {
      val value = example()
      value("artifacts")(0)("size_bytes") = ujson.Num(2147483648d)
      intercept[IllegalArgumentException](decode(value)).getMessage should include("int")
    }

    for (
      field <- Seq(
        "namespace",
        "keywords",
        "dependencies",
        "title",
        "published_at",
        "publisher",
        "licenses",
      )
    ) {
      s"require publication $field" in {
        reject(value => value.obj.remove(field))
      }
    }

    "require artifact roots despite publication roots" in {
      reject { value =>
        value("roots") = value("artifacts")(0)("roots")
        value("artifacts")(0).obj.remove("roots")
      }
    }

    "require dependency base despite file metadata" in {
      reject(value =>
        value("dependencies")(0) = ujson.Obj("url" -> "https://example.org/units.ttl"),
      )
    }

    for (wrong <- Seq(ujson.Null, ujson.Arr())) {
      s"reject namespace $wrong" in {
        reject(value => value("namespace") = wrong)
      }
    }

    "reject a nonobject publication" in {
      intercept[IllegalArgumentException](decode(ujson.Arr()))
    }

    "reject a nonobject artifact" in {
      reject(value => value("artifacts")(0) = ujson.Null)
    }

    "reject a nonobject dependency" in {
      reject(value => value("dependencies")(0) = "units")
    }

    "preserve opaque version labels" in {
      val value = example()
      value("version") = " release + amendment "
      decode(value).version shouldBe " release + amendment "
    }

    for (wrong <- Seq(ujson.Str("1"), ujson.Bool(true))) {
      s"reject nonnumeric revision $wrong" in {
        reject(value => value("schema_version") = wrong)
      }
    }

    "reject an unknown format" in {
      reject(value => value("artifacts")(0)("format") = "future-format")
    }

    for (
      (label, expected) <- Seq(
        "owl-rdfxml" -> Format.OwlRdfxml,
        "rdfs-turtle" -> Format.RdfsTurtle,
        "ossie-yaml" -> Format.OssieYaml,
      )
    ) {
      s"map format $label" in {
        val value = example()
        value("artifacts")(0)("format") = label
        decode(value).artifacts.head.format shouldBe expected
      }
    }

    "reject an unknown origin" in {
      reject(value => value("artifacts")(0)("origin") = "FUTURE")
    }

    "discard extras at each record level" in {
      val value = example()
      value("package") = "obsolete"
      value("roots") = ujson.Arr("obsolete")
      value("artifacts")(0)("dependencies") = "obsolete"
      value("dependencies")(0)("url") = "obsolete"
      val publication = decode(value)
      publication.namespace shouldBe "example"
      publication.artifacts.head.url.original shouldBe "https://example.org/packages/sensors/1.0.0/sensors.ttl"
      publication.dependencies.head.baseUrl.original shouldBe "https://example.org/packages/units/2.0.0/"
    }

    "reject an invalid dependency beside extras" in {
      val value = example()
      value("backend") = true
      value("dependencies")(0)("base_url") = ujson.Arr()
      intercept[IllegalArgumentException](decode(value))
    }

    "accept HTTP development references" in {
      val publication = PublicationJson.decode(
        ujson.write(example()).replace("https://example.org", "http://localhost:8080"),
      )
      publication.baseUrl.original shouldBe "http://localhost:8080/packages/sensors/1.0.0/"
      publication.artifacts.head.url.original shouldBe "http://localhost:8080/packages/sensors/1.0.0/sensors.ttl"
      publication.artifacts.head.roots.map(_.original) shouldBe Seq(
        "http://localhost:8080/packages/sensors/1.0.0/sensors.ttl",
      )
      publication.dependencies.head.baseUrl.original shouldBe "http://localhost:8080/packages/units/2.0.0/"
    }

    "accept native URI schemes" in {
      val value = example()
      value("base_url") = "urn:ohi:sensors:1"
      decode(value).baseUrl.original shouldBe "urn:ohi:sensors:1"
    }

    "preserve URI spelling" in {
      val value = example()
      value("base_url") = "https://Example.org/publication?revision=1#entry"
      decode(value).baseUrl.original shouldBe "https://Example.org/publication?revision=1#entry"
    }

    "reject duplicate artifact URLs" in {
      reject(value => value("artifacts")(1)("url") = value("artifacts")(0)("url"))
    }

    "accept repeated artifact formats" in {
      val value = example()
      value("artifacts")(1)("format") = "owl-turtle"
      value("artifacts") = ujson.Arr.from(value("artifacts").arr.reverse)
      decode(value).artifacts.map(_.url.original) shouldBe Seq(
        "https://example.org/packages/sensors/1.0.0/sensors.yaml",
        "https://example.org/packages/sensors/1.0.0/sensors.ttl",
      )
    }

    "reject duplicate dependency bases" in {
      reject { value =>
        value("dependencies").arr += ujson.Obj(
          "base_url" -> "https://example.org/packages/units/2.0.0/",
          "url" -> "ignored",
        )
      }
    }

    "reject self dependency" in {
      reject(value =>
        value("dependencies")(0)("base_url") = "https://example.org/packages/sensors/1.0.0/",
      )
    }

    "preserve dependency identity and order" in {
      val value = example()
      value("dependencies") = ujson.Arr(
        ujson.Obj("base_url" -> "https://example.org/units"),
        ujson.Obj("base_url" -> "https://Example.org/units"),
      )
      decode(value).dependencies.map(_.baseUrl.original) shouldBe Seq(
        "https://example.org/units",
        "https://Example.org/units",
      )
    }

    "preserve artifact roots exactly" in {
      val value = example()
      value("artifacts")(0)("roots") = ujson.Arr(
        "https://example.org/b.ttl",
        "https://example.org/a.ttl",
        "https://example.org/b.ttl",
      )
      decode(value).artifacts.head.roots.map(_.original) shouldBe Seq(
        "https://example.org/b.ttl",
        "https://example.org/a.ttl",
        "https://example.org/b.ttl",
      )
    }

    "reject malformed JSON and retain its parser cause" in {
      intercept[IllegalArgumentException](PublicationJson.decode("{")).getCause should not be null
    }
  }
}
