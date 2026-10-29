package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import eu.neverblink.ohi.model.generated.Format
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.collection.mutable.ArrayBuffer

class ProjectFormatSpec extends AnyWordSpec, Matchers {
  private def withDirectory(check: os.Path => Unit): Unit = {
    val directory = os.temp.dir(prefix = "ohi project format ")
    try check(directory)
    finally os.remove.all(directory)
  }

  private val declaration = """format_version: 1
    |selections:
    |  - base_url: https://example.org/publication
    |    url: https://files.example/first.yaml
    |  - base_url: https://example.org/publication
    |    url: https://files.example/second.yaml
    |""".stripMargin

  private val metadata = ProjectTestData.publication(
    "https://example.org/publication",
    Seq(
      "https://files.example/unselected.ttl" -> Format.LinkmlYaml,
      "https://files.example/first.yaml" -> Format.OwlTurtle,
      "https://files.example/second.yaml" -> Format.OwlTurtle,
    ),
    Seq("https://example.org/dependency"),
  )

  "project format inference" should {
    for empty <- Seq(false, true) do
      s"use no preference for a ${
          if empty then "empty" else "missing"
        } declaration without inspection" in {
        withDirectory { directory =>
          if empty then os.write(directory / "ohi.yaml", "format_version: 1\nselections: []\n")
          val inspection = new ProjectLock.Inspection(url => fail(s"Unexpected inspection: $url"))
          ProjectFormat.infer(directory.toNIO, inspection) shouldBe None
        }
      }

    "infer unanimity from exact selected metadata while ignoring filenames, other artifacts, dependencies and the lock" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", declaration)
        os.write(directory / "ohi.lock.json", "not a lock")
        val requests = ArrayBuffer.empty[String]
        val inspection = new ProjectLock.Inspection(url => {
          requests += url
          metadata
        })
        ProjectFormat.infer(directory.toNIO, inspection) shouldBe Some(Format.OwlTurtle)
        requests.toSeq shouldBe Seq("https://example.org/publication")
        os.read(directory / "ohi.yaml") shouldBe declaration
        os.read(directory / "ohi.lock.json") shouldBe "not a lock"
      }
    }

    "use no preference when direct selected artifacts have different formats" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", declaration)
        val inspection = new ProjectLock.Inspection(_ =>
          metadata.copy(artifacts =
            metadata.artifacts.updated(
              2,
              metadata.artifacts.last.copy(format = Format.LinkmlYaml),
            ),
          ),
        )
        ProjectFormat.infer(directory.toNIO, inspection) shouldBe None
      }
    }

    for (changed, expected) <- Seq(
        metadata.copy(artifacts = metadata.artifacts.take(2)) ->
          "does not advertise artifact https://files.example/second.yaml",
        metadata.copy(baseUrl = Uri("https://example.org/other")) ->
          "returned identity https://example.org/other",
      )
    do
      s"reject selected metadata that $expected" in {
        withDirectory { directory =>
          os.write(directory / "ohi.yaml", declaration)
          val failure = intercept[ProjectException] {
            ProjectFormat.infer(directory.toNIO, new ProjectLock.Inspection(_ => changed))
          }
          failure.code shouldBe ProjectErrorCode.InvalidSelection
          failure.getMessage should include(expected)
          os.read(directory / "ohi.yaml") shouldBe declaration
        }
      }
  }
}
