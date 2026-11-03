package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import eu.neverblink.ohi.cli.generated.declaration.Format
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DeclarationLookupSpec extends AnyWordSpec, Matchers {
  private def withDirectory(check: os.Path => Unit): Unit = {
    val directory = os.temp.dir(prefix = "ohi project format ")
    try check(directory)
    finally os.remove.all(directory)
  }

  "project declaration lookup" should {
    "use no project preference when the declaration is absent" in {
      withDirectory { directory =>
        DeclarationFiles.readIfPresent(directory.toNIO) shouldBe None
      }
    }

    for (label, dependencies) <- Seq(
        "empty" -> "[]\n",
        "different-format" -> "\n  - ontology: w3c/dcat@2\n    format: linkml-yaml\n",
      )
    do
      s"read the declared default with $label dependencies without inspecting metadata or the lock" in {
        withDirectory { directory =>
          val declaration =
            s"format_version: 1\ndefault_format: owl-turtle\ndownload_dir: vendor/ontologies\ndependencies: $dependencies"
          os.write(directory / "ohi.yaml", declaration)
          os.write(directory / "ohi.lock.json", "not a lock")
          val settings = DeclarationFiles.readIfPresent(directory.toNIO).get
          settings.defaultFormat shouldBe Format.OwlTurtle
          settings.downloadDir shouldBe "vendor/ontologies"
          os.read(directory / "ohi.yaml") shouldBe declaration
          os.read(directory / "ohi.lock.json") shouldBe "not a lock"
        }
      }

    "report an invalid declaration instead of substituting a default" in {
      withDirectory { directory =>
        val declaration = "format_version: 1\ndependencies: []\n"
        os.write(directory / "ohi.yaml", declaration)
        val failure = intercept[ProjectException](DeclarationFiles.readIfPresent(directory.toNIO))
        failure.code shouldBe ProjectErrorCode.InvalidDeclaration
        os.read(directory / "ohi.yaml") shouldBe declaration
      }
    }
  }
}
