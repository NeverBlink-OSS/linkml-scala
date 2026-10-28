package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.cli.ProjectTestData.publication
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import eu.neverblink.ohi.model.generated.*
import eu.neverblink.ohi.model.generated.lock
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable.ArrayBuffer

class ProjectLockSpec extends AnyWordSpec, Matchers {
  private def selection(baseUrl: String, url: String): lock.ArtifactSelectionImpl =
    lock.ArtifactSelectionImpl(Uri(baseUrl), Uri(url))

  private def fileValues(result: lock.Lock): Seq[(String, String, lock.Format)] =
    result.files.map(file => (file.baseUrl.original, file.url.original, file.format))

  // Selects source.ttl from a publication with one dependency that offers `artifacts`.
  private def buildWithDependencyArtifacts(
      artifacts: Seq[(String, Format)],
  ): (ProjectException, Seq[String]) = {
    val requests = ArrayBuffer.empty[String]
    val failure = intercept[ProjectException] {
      ProjectLock.build(
        Seq(selection("https://example.org/source", "https://files.example/source.ttl")),
        url => {
          requests += url
          if url == "https://example.org/source" then
            publication(
              url,
              Seq("https://files.example/source.ttl" -> Format.OwlTurtle),
              Seq("https://example.org/dependency"),
            )
          else publication(url, artifacts)
        },
      )
    }
    (failure, requests.toSeq)
  }

  // Locks one selected artifact of `format` and returns its only locked file.
  private def lockedFile(format: Format): (String, String, lock.Format) = {
    val result = ProjectLock.build(
      Seq(selection("https://example.org/source", "https://files.example/artifact")),
      url => publication(url, Seq("https://files.example/artifact" -> format)),
    )
    fileValues(result) match {
      case Seq(file) => file
      case files => fail(s"Expected one locked file, got $files")
    }
  }

  "ProjectLock.build" should {
    "return an empty revision-two lock without inspection for no selections" in {
      val result = ProjectLock.build(Seq.empty, url => fail(s"Unexpected inspection of $url"))
      result.formatVersion shouldBe 2
      result.selections shouldBe empty
      result.files shouldBe empty
    }

    "inspect direct publications before flat dependencies and preserve file-pair order and hashes" in {
      val metadata = Map(
        "https://Hub.example/z/?v=%2F" -> {
          val direct = publication(
            "https://Hub.example/z/?v=%2F",
            Seq(
              "https://files.example/unselected.yaml" -> Format.LinkmlYaml,
              "https://Files.example/z%2F.ttl#first" -> Format.OwlTurtle,
              "https://files.example/z-second.ttl" -> Format.OwlTurtle,
            ),
            Seq("https://example.org/a", "https://example.org/b"),
          )
          direct.copy(artifacts =
            direct.artifacts.updated(
              0,
              direct.artifacts.head.copy(sha256 =
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
              ),
            ),
          )
        },
        "https://example.org/b" -> publication(
          "https://example.org/b",
          Seq("https://files.example/b.ttl" -> Format.OwlTurtle),
          Seq("https://example.org/a", "https://example.org/c"),
        ),
        "https://example.org/a" -> publication(
          "https://example.org/a",
          Seq(
            "https://files.example/a.yaml" -> Format.LinkmlYaml,
            "https://files.example/a.ttl" -> Format.OwlTurtle,
          ),
          Seq("https://example.org/not-recursed"),
          sha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
        ),
        "https://example.org/c" -> publication(
          "https://example.org/c",
          Seq("https://files.example/c.ttl" -> Format.OwlTurtle),
        ),
        "https://example.org/not-recursed" -> publication(
          "https://example.org/not-recursed",
          Seq("https://files.example/not-recursed.ttl" -> Format.OwlTurtle),
        ),
      )
      val requests = ArrayBuffer.empty[String]
      val result = ProjectLock.build(
        Seq(
          selection("https://Hub.example/z/?v=%2F", "https://Files.example/z%2F.ttl#first"),
          selection("https://example.org/b", "https://files.example/b.ttl"),
          selection("https://Hub.example/z/?v=%2F", "https://files.example/z-second.ttl"),
        ),
        url => {
          requests += url
          metadata.getOrElse(url, fail(s"Unexpected publication request: $url"))
        },
      )
      requests.toSeq shouldBe Seq(
        "https://Hub.example/z/?v=%2F",
        "https://example.org/b",
        "https://example.org/a",
        "https://example.org/c",
      )
      result.formatVersion shouldBe 2
      result.selections.map(s => (s.baseUrl.original, s.url.original)) shouldBe Seq(
        ("https://Hub.example/z/?v=%2F", "https://Files.example/z%2F.ttl#first"),
        ("https://example.org/b", "https://files.example/b.ttl"),
        ("https://Hub.example/z/?v=%2F", "https://files.example/z-second.ttl"),
      )
      fileValues(result) shouldBe Seq(
        (
          "https://Hub.example/z/?v=%2F",
          "https://Files.example/z%2F.ttl#first",
          lock.Format.OwlTurtle,
        ),
        ("https://example.org/a", "https://files.example/a.ttl", lock.Format.OwlTurtle),
        ("https://example.org/b", "https://files.example/b.ttl", lock.Format.OwlTurtle),
        ("https://example.org/c", "https://files.example/c.ttl", lock.Format.OwlTurtle),
        (
          "https://Hub.example/z/?v=%2F",
          "https://files.example/z-second.ttl",
          lock.Format.OwlTurtle,
        ),
      )
      result.files.map(_.sha256) shouldBe Seq(
        Some("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        Some("fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"),
        Some("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        Some("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
        Some("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
      )
    }

    "retain dependency files in every required format" in {
      val metadata = Map(
        "https://example.org/source" -> publication(
          "https://example.org/source",
          Seq(
            "https://files.example/source.ttl" -> Format.OwlTurtle,
            "https://files.example/source.yaml" -> Format.LinkmlYaml,
          ),
          Seq("https://example.org/dependency"),
        ),
        "https://example.org/dependency" -> publication(
          "https://example.org/dependency",
          Seq(
            "https://files.example/dependency.yaml" -> Format.LinkmlYaml,
            "https://files.example/dependency.ttl" -> Format.OwlTurtle,
          ),
        ),
      )
      val requests = ArrayBuffer.empty[String]
      val result = ProjectLock.build(
        Seq(
          selection("https://example.org/source", "https://files.example/source.ttl"),
          selection("https://example.org/source", "https://files.example/source.yaml"),
        ),
        url => {
          requests += url
          metadata(url)
        },
      )
      requests.toSeq shouldBe Seq("https://example.org/source", "https://example.org/dependency")
      fileValues(result) shouldBe Seq(
        ("https://example.org/source", "https://files.example/source.ttl", lock.Format.OwlTurtle),
        (
          "https://example.org/dependency",
          "https://files.example/dependency.ttl",
          lock.Format.OwlTurtle,
        ),
        ("https://example.org/source", "https://files.example/source.yaml", lock.Format.LinkmlYaml),
        (
          "https://example.org/dependency",
          "https://files.example/dependency.yaml",
          lock.Format.LinkmlYaml,
        ),
      )
    }

    "reject a dependency with no artifact in the selected format" in {
      val (failure, requests) = buildWithDependencyArtifacts(
        Seq("https://files.example/dependency.yaml" -> Format.LinkmlYaml),
      )
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include(
        "https://example.org/dependency has 0 artifacts in format owl-turtle required by " +
          "https://files.example/source.ttl",
      )
      requests shouldBe Seq("https://example.org/source", "https://example.org/dependency")
    }

    "reject a dependency with two artifacts in the selected format" in {
      val (failure, requests) = buildWithDependencyArtifacts(
        Seq(
          "https://files.example/dependency-first.ttl" -> Format.OwlTurtle,
          "https://files.example/dependency-second.ttl" -> Format.OwlTurtle,
        ),
      )
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include(
        "https://example.org/dependency has 2 artifacts in format owl-turtle required by " +
          "https://files.example/source.ttl",
      )
      requests shouldBe Seq("https://example.org/source", "https://example.org/dependency")
    }

    "reject a dependency publication whose exact response identity differs from its request" in {
      val requests = ArrayBuffer.empty[String]
      val failure = intercept[ProjectException] {
        ProjectLock.build(
          Seq(selection("https://example.org/source", "https://files.example/source.ttl")),
          url => {
            requests += url
            if url == "https://example.org/dependency" then
              publication(url + "/", Seq("https://files.example/source.ttl" -> Format.OwlTurtle))
            else
              publication(
                url,
                Seq("https://files.example/source.ttl" -> Format.OwlTurtle),
                Seq("https://example.org/dependency"),
              )
          },
        )
      }
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      requests.toSeq shouldBe Seq("https://example.org/source", "https://example.org/dependency")
      failure.getMessage should include(
        "https://example.org/dependency returned identity https://example.org/dependency/",
      )
    }

    "reject an unadvertised exact direct artifact before inspecting dependencies" in {
      val requests = ArrayBuffer.empty[String]
      val failure = intercept[ProjectException] {
        ProjectLock.build(
          Seq(
            selection("https://example.org/source", "https://files.example/source.ttl?revision=2"),
          ),
          url => {
            requests += url
            publication(
              url,
              Seq("https://files.example/source.ttl" -> Format.OwlTurtle),
              if url == "https://example.org/source" then Seq("https://example.org/dependency")
              else Seq.empty,
            )
          },
        )
      }
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include("https://example.org/source")
      failure.getMessage should include("https://files.example/source.ttl?revision=2")
      requests.toSeq shouldBe Seq("https://example.org/source")
    }

    "lock an OWL RDF/XML artifact as OWL RDF/XML" in {
      lockedFile(Format.OwlRdfxml) shouldBe
        ("https://example.org/source", "https://files.example/artifact", lock.Format.OwlRdfxml)
    }

    "lock an RDFS Turtle artifact as RDFS Turtle" in {
      lockedFile(Format.RdfsTurtle) shouldBe
        ("https://example.org/source", "https://files.example/artifact", lock.Format.RdfsTurtle)
    }

    "lock an Ossie YAML artifact as Ossie YAML" in {
      lockedFile(Format.OssieYaml) shouldBe
        ("https://example.org/source", "https://files.example/artifact", lock.Format.OssieYaml)
    }
  }
}
