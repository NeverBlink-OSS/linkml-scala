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
  private def build(
      selections: Seq[lock.ArtifactSelectionImpl],
      inspect: String => Publication,
  ): lock.Lock = ProjectLock.build(selections, new ProjectLock.Inspection(inspect))

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
      build(
        Seq(
          selection(
            "https://example.org/files/packages/test/source/1/",
            "https://example.org/files/packages/test/source/1/source.ttl",
          ),
        ),
        url => {
          requests += url
          if url == "https://example.org/files/packages/test/source/1/" then
            publication(
              url,
              Seq(
                "https://example.org/files/packages/test/source/1/source.ttl" -> Format.OwlTurtle,
              ),
              Seq("https://example.org/files/packages/test/dependency/1/"),
            )
          else publication(url, artifacts)
        },
      )
    }
    (failure, requests.toSeq)
  }

  // Locks one selected artifact of `format` and returns its only locked file.
  private def lockedFile(format: Format): (String, String, lock.Format) = {
    val result = build(
      Seq(
        selection(
          "https://example.org/files/packages/test/source/1/",
          "https://example.org/files/packages/test/source/1/artifact",
        ),
      ),
      url =>
        publication(url, Seq("https://example.org/files/packages/test/source/1/artifact" -> format)),
    )
    fileValues(result) match {
      case Seq(file) => file
      case files => fail(s"Expected one locked file, got $files")
    }
  }

  "ProjectLock.build" should {
    "return an empty revision-two lock without inspection for no selections" in {
      val result = build(Seq.empty, url => fail(s"Unexpected inspection of $url"))
      result.formatVersion shouldBe 2
      result.selections shouldBe empty
      result.files shouldBe empty
    }

    "inspect direct publications before flat dependencies and preserve file-pair order and hashes" in {
      val metadata = Map(
        "https://Hub.example/files/packages/test/z%2F/1/" -> {
          val direct = publication(
            "https://Hub.example/files/packages/test/z%2F/1/",
            Seq(
              "https://Hub.example/files/packages/test/z%2F/1/unselected.yaml" -> Format.LinkmlYaml,
              "https://Hub.example/files/packages/test/z%2F/1/z%2F.ttl" -> Format.OwlTurtle,
              "https://Hub.example/files/packages/test/z%2F/1/z-second.ttl" -> Format.OwlTurtle,
            ),
            Seq(
              "https://example.org/files/packages/test/a/1/",
              "https://example.org/files/packages/test/b/1/",
            ),
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
        "https://example.org/files/packages/test/b/1/" -> publication(
          "https://example.org/files/packages/test/b/1/",
          Seq("https://example.org/files/packages/test/b/1/b.ttl" -> Format.OwlTurtle),
          Seq(
            "https://example.org/files/packages/test/a/1/",
            "https://example.org/files/packages/test/c/1/",
          ),
        ),
        "https://example.org/files/packages/test/a/1/" -> publication(
          "https://example.org/files/packages/test/a/1/",
          Seq(
            "https://example.org/files/packages/test/a/1/a.yaml" -> Format.LinkmlYaml,
            "https://example.org/files/packages/test/a/1/a.ttl" -> Format.OwlTurtle,
          ),
          Seq("https://example.org/files/packages/test/not-recursed/1/"),
          sha256 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210",
        ),
        "https://example.org/files/packages/test/c/1/" -> publication(
          "https://example.org/files/packages/test/c/1/",
          Seq("https://example.org/files/packages/test/c/1/c.ttl" -> Format.OwlTurtle),
        ),
        "https://example.org/files/packages/test/not-recursed/1/" -> publication(
          "https://example.org/files/packages/test/not-recursed/1/",
          Seq(
            "https://example.org/files/packages/test/not-recursed/1/not-recursed.ttl" -> Format.OwlTurtle,
          ),
        ),
      )
      val requests = ArrayBuffer.empty[String]
      val result = build(
        Seq(
          selection(
            "https://Hub.example/files/packages/test/z%2F/1/",
            "https://Hub.example/files/packages/test/z%2F/1/z%2F.ttl",
          ),
          selection(
            "https://example.org/files/packages/test/b/1/",
            "https://example.org/files/packages/test/b/1/b.ttl",
          ),
          selection(
            "https://Hub.example/files/packages/test/z%2F/1/",
            "https://Hub.example/files/packages/test/z%2F/1/z-second.ttl",
          ),
        ),
        url => {
          requests += url
          metadata.getOrElse(url, fail(s"Unexpected publication request: $url"))
        },
      )
      requests.toSeq shouldBe Seq(
        "https://Hub.example/files/packages/test/z%2F/1/",
        "https://example.org/files/packages/test/b/1/",
        "https://example.org/files/packages/test/a/1/",
        "https://example.org/files/packages/test/c/1/",
      )
      result.formatVersion shouldBe 2
      result.selections.map(s => (s.baseUrl.original, s.url.original)) shouldBe Seq(
        (
          "https://Hub.example/files/packages/test/z%2F/1/",
          "https://Hub.example/files/packages/test/z%2F/1/z%2F.ttl",
        ),
        (
          "https://example.org/files/packages/test/b/1/",
          "https://example.org/files/packages/test/b/1/b.ttl",
        ),
        (
          "https://Hub.example/files/packages/test/z%2F/1/",
          "https://Hub.example/files/packages/test/z%2F/1/z-second.ttl",
        ),
      )
      fileValues(result) shouldBe Seq(
        (
          "https://Hub.example/files/packages/test/z%2F/1/",
          "https://Hub.example/files/packages/test/z%2F/1/z%2F.ttl",
          lock.Format.OwlTurtle,
        ),
        (
          "https://example.org/files/packages/test/a/1/",
          "https://example.org/files/packages/test/a/1/a.ttl",
          lock.Format.OwlTurtle,
        ),
        (
          "https://example.org/files/packages/test/b/1/",
          "https://example.org/files/packages/test/b/1/b.ttl",
          lock.Format.OwlTurtle,
        ),
        (
          "https://example.org/files/packages/test/c/1/",
          "https://example.org/files/packages/test/c/1/c.ttl",
          lock.Format.OwlTurtle,
        ),
        (
          "https://Hub.example/files/packages/test/z%2F/1/",
          "https://Hub.example/files/packages/test/z%2F/1/z-second.ttl",
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
        "https://example.org/files/packages/test/source/1/" -> publication(
          "https://example.org/files/packages/test/source/1/",
          Seq(
            "https://example.org/files/packages/test/source/1/source.ttl" -> Format.OwlTurtle,
            "https://example.org/files/packages/test/source/1/source.yaml" -> Format.LinkmlYaml,
          ),
          Seq("https://example.org/files/packages/test/dependency/1/"),
        ),
        "https://example.org/files/packages/test/dependency/1/" -> publication(
          "https://example.org/files/packages/test/dependency/1/",
          Seq(
            "https://example.org/files/packages/test/dependency/1/dependency.yaml" -> Format.LinkmlYaml,
            "https://example.org/files/packages/test/dependency/1/dependency.ttl" -> Format.OwlTurtle,
          ),
        ),
      )
      val requests = ArrayBuffer.empty[String]
      val result = build(
        Seq(
          selection(
            "https://example.org/files/packages/test/source/1/",
            "https://example.org/files/packages/test/source/1/source.ttl",
          ),
          selection(
            "https://example.org/files/packages/test/source/1/",
            "https://example.org/files/packages/test/source/1/source.yaml",
          ),
        ),
        url => {
          requests += url
          metadata(url)
        },
      )
      requests.toSeq shouldBe Seq(
        "https://example.org/files/packages/test/source/1/",
        "https://example.org/files/packages/test/dependency/1/",
      )
      fileValues(result) shouldBe Seq(
        (
          "https://example.org/files/packages/test/source/1/",
          "https://example.org/files/packages/test/source/1/source.ttl",
          lock.Format.OwlTurtle,
        ),
        (
          "https://example.org/files/packages/test/dependency/1/",
          "https://example.org/files/packages/test/dependency/1/dependency.ttl",
          lock.Format.OwlTurtle,
        ),
        (
          "https://example.org/files/packages/test/source/1/",
          "https://example.org/files/packages/test/source/1/source.yaml",
          lock.Format.LinkmlYaml,
        ),
        (
          "https://example.org/files/packages/test/dependency/1/",
          "https://example.org/files/packages/test/dependency/1/dependency.yaml",
          lock.Format.LinkmlYaml,
        ),
      )
    }

    "reject a dependency with no artifact in the selected format" in {
      val (failure, requests) = buildWithDependencyArtifacts(
        Seq(
          "https://example.org/files/packages/test/dependency/1/dependency.yaml" -> Format.LinkmlYaml,
        ),
      )
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include(
        "https://example.org/files/packages/test/dependency/1/ has 0 artifacts in format owl-turtle required by " +
          "https://example.org/files/packages/test/source/1/source.ttl",
      )
      requests shouldBe Seq(
        "https://example.org/files/packages/test/source/1/",
        "https://example.org/files/packages/test/dependency/1/",
      )
    }

    "reject a dependency with two artifacts in the selected format" in {
      val (failure, requests) = buildWithDependencyArtifacts(
        Seq(
          "https://example.org/files/packages/test/dependency/1/dependency-first.ttl" -> Format.OwlTurtle,
          "https://example.org/files/packages/test/dependency/1/dependency-second.ttl" -> Format.OwlTurtle,
        ),
      )
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include(
        "https://example.org/files/packages/test/dependency/1/ has 2 artifacts in format owl-turtle required by " +
          "https://example.org/files/packages/test/source/1/source.ttl",
      )
      requests shouldBe Seq(
        "https://example.org/files/packages/test/source/1/",
        "https://example.org/files/packages/test/dependency/1/",
      )
    }

    "reject a dependency publication whose exact response identity differs from its request" in {
      val requests = ArrayBuffer.empty[String]
      val failure = intercept[ProjectException] {
        build(
          Seq(
            selection(
              "https://example.org/files/packages/test/source/1/",
              "https://example.org/files/packages/test/source/1/source.ttl",
            ),
          ),
          url => {
            requests += url
            if url == "https://example.org/files/packages/test/dependency/1/" then
              publication(
                url + "/",
                Seq(
                  "https://example.org/files/packages/test/source/1/source.ttl" -> Format.OwlTurtle,
                ),
              )
            else
              publication(
                url,
                Seq(
                  "https://example.org/files/packages/test/source/1/source.ttl" -> Format.OwlTurtle,
                ),
                Seq("https://example.org/files/packages/test/dependency/1/"),
              )
          },
        )
      }
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      requests.toSeq shouldBe Seq(
        "https://example.org/files/packages/test/source/1/",
        "https://example.org/files/packages/test/dependency/1/",
      )
      failure.getMessage should include(
        "https://example.org/files/packages/test/dependency/1/ returned identity https://example.org/files/packages/test/dependency/1//",
      )
    }

    "reject an unadvertised exact direct artifact before inspecting dependencies" in {
      val requests = ArrayBuffer.empty[String]
      val failure = intercept[ProjectException] {
        build(
          Seq(
            selection(
              "https://example.org/files/packages/test/source/1/",
              "https://example.org/files/packages/test/source/1/source.ttl?revision=2",
            ),
          ),
          url => {
            requests += url
            publication(
              url,
              Seq(
                "https://example.org/files/packages/test/source/1/source.ttl" -> Format.OwlTurtle,
              ),
              if url == "https://example.org/files/packages/test/source/1/" then
                Seq("https://example.org/files/packages/test/dependency/1/")
              else Seq.empty,
            )
          },
        )
      }
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include("https://example.org/files/packages/test/source/1/")
      failure.getMessage should include(
        "https://example.org/files/packages/test/source/1/source.ttl?revision=2",
      )
      requests.toSeq shouldBe Seq("https://example.org/files/packages/test/source/1/")
    }

    "lock an OWL RDF/XML artifact as OWL RDF/XML" in {
      lockedFile(Format.OwlRdfxml) shouldBe
        (
          "https://example.org/files/packages/test/source/1/",
          "https://example.org/files/packages/test/source/1/artifact",
          lock.Format.OwlRdfxml,
        )
    }

    "lock an RDFS Turtle artifact as RDFS Turtle" in {
      lockedFile(Format.RdfsTurtle) shouldBe
        (
          "https://example.org/files/packages/test/source/1/",
          "https://example.org/files/packages/test/source/1/artifact",
          lock.Format.RdfsTurtle,
        )
    }

    "lock an Ossie YAML artifact as Ossie YAML" in {
      lockedFile(Format.OssieYaml) shouldBe
        (
          "https://example.org/files/packages/test/source/1/",
          "https://example.org/files/packages/test/source/1/artifact",
          lock.Format.OssieYaml,
        )
    }

    "reject URL identity that disagrees with publication metadata" in {
      val metadata = publication(
        "https://example.org/files/packages/test/a%41/1/",
        Seq("https://example.org/files/packages/test/a%41/1/source.yaml" -> Format.LinkmlYaml),
      )
      val accepted = ProjectLock.file(metadata, metadata.artifacts.head)
      accepted.baseUrl.original shouldBe "https://example.org/files/packages/test/a%41/1/"
      accepted.url.original shouldBe "https://example.org/files/packages/test/a%41/1/source.yaml"
      accepted.sha256 shouldBe Some(
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
      )
      val failure = intercept[ProjectException] {
        ProjectLock.file(metadata.copy(name = "aA"), metadata.artifacts.head)
      }
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include("does not match its namespace, name and version")
    }

    "reject an advertised artifact beneath a different Hub publication" in {
      val metadata = publication(
        "https://example.org/files/packages/test/source/1/",
        Seq("https://example.org/files/packages/test/other/1/source.yaml" -> Format.LinkmlYaml),
      )
      val failure = intercept[ProjectException] {
        build(
          Seq(selection(metadata.baseUrl.original, metadata.artifacts.head.url.original)),
          _ => metadata,
        )
      }
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include("is outside publication")
    }

    "report malformed metadata URLs as selection errors while preserving the URL failure" in {
      val metadata = publication(
        "https://example.org/publication",
        Seq("https://example.org/file.yaml" -> Format.LinkmlYaml),
      )
      val failure = intercept[ProjectException] {
        ProjectLock.file(metadata, metadata.artifacts.head)
      }
      failure.code shouldBe ProjectErrorCode.InvalidSelection
      failure.getMessage should include("https://example.org/publication")
      val cause = failure.getCause.asInstanceOf[CliFileException]
      cause.error.code shouldBe generated.fileerror.CliFileErrorCode.UnsupportedFilename
      cause.getMessage shouldBe failure.getMessage
    }

  }
}
