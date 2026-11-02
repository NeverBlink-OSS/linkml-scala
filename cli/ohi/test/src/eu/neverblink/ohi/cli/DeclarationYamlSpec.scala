package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.ohi.cli.generated.declaration.{Declaration, Format, ProjectDependency}
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

class DeclarationYamlSpec extends AnyWordSpec, Matchers {
  private val header = "format_version: 2\ndefault_format: linkml-yaml\ndownload_dir: .ohi\n"
  private val dependency = "- ontology: w3c/dcat@2\n  format: linkml-yaml\n"

  private def rejected(yaml: String): ProjectException = {
    val failure = intercept[ProjectException](DeclarationYaml.decode(yaml))
    failure.code shouldBe ProjectErrorCode.InvalidDeclaration
    failure.getMessage should include("ohi.yaml")
    failure
  }

  "DeclarationYaml.decode" should {
    "read the explicit default and preserve the supplied dependency order" in {
      val declaration = DeclarationYaml.decode(
        "format_version: 2\ndefault_format: rdfs-turtle\ndownload_dir: .ohi\ndependencies:\n" +
          "- ontology: w3c/dcat@3\n  format: owl-turtle\n" + dependency,
      )
      declaration.formatVersion shouldBe 2
      declaration.defaultFormat shouldBe Format.RdfsTurtle
      declaration.downloadDir shouldBe ".ohi"
      declaration.dependencies shouldBe Seq(
        ProjectDependency(ontology = "w3c/dcat@3", format = Format.OwlTurtle),
        ProjectDependency(ontology = "w3c/dcat@2", format = Format.LinkmlYaml),
      )
    }

    "reject old URL declarations with replacement guidance" in {
      val message = rejected("format_version: 1\nselections: []\n").getMessage
      message should include("format_version: 2")
      message should include("default_format")
      message should include("dependencies")
      message should include("https://ohi.dev/docs/guides/projects/")
    }

    "reject an unsupported declaration revision" in {
      rejected(
        "format_version: 99\ndefault_format: linkml-yaml\ndownload_dir: .ohi\ndependencies: []\n",
      )
    }

    for (label, value) <- Seq(
        "missing" -> "",
        "null" -> "default_format: null\n",
        "unknown" -> "default_format: invalid\n",
      )
    do
      s"reject a $label default" in {
        rejected(s"format_version: 2\n${value}download_dir: .ohi\ndependencies: []\n")
      }

    for value <- Seq(
        "",
        ".",
        "..",
        "../outside",
        "/absolute",
        "ohi.yaml",
        "OHI.LOCK.JSON./child",
        "cache/../ohi.lock.json",
      )
    do
      s"reject an unsafe project download directory '$value'" in {
        rejected(
          header.replace("download_dir: .ohi", s"download_dir: '$value'") + "dependencies: []\n",
        )
          .getMessage should include("download_dir")
      }

    for (label, field) <- Seq("missing" -> "", "null" -> "download_dir: null\n")
    do
      s"reject a $label download directory" in {
        rejected(header.replace("download_dir: .ohi\n", field) + "dependencies: []\n")
      }

    "reject a missing dependency list" in { rejected(header) }
    "reject a null dependency list instead of interpreting it as empty" in {
      rejected(header + "dependencies: null\n")
    }
    "reject a null dependency entry" in { rejected(header + "dependencies: [null]\n") }
    "reject an unknown root field" in { rejected(header + "dependencies: []\nextra: true\n") }
    "reject an unknown dependency field" in {
      rejected(s"${header}dependencies:\n${dependency}  extra: true\n")
    }

    for (label, entry) <- Seq(
        "missing ontology" -> "- format: linkml-yaml\n",
        "missing format" -> "- ontology: w3c/dcat@2\n",
        "unknown format" -> "- ontology: w3c/dcat@2\n  format: invalid\n",
        "unversioned ontology" -> "- ontology: w3c/dcat\n  format: linkml-yaml\n",
        "malformed ontology" -> "- ontology: w3c/dcat@bad+label\n  format: linkml-yaml\n",
      )
    do s"reject a dependency with $label" in { rejected(s"${header}dependencies:\n$entry") }

    "reject the same ontology and format listed twice" in {
      rejected(s"${header}dependencies:\n$dependency$dependency").getMessage should include(
        "ontology and format pair must occur once",
      )
    }
  }

  "DeclarationYaml.encode" should {
    "include the default and explicit empty list in a new declaration" in {
      DeclarationYaml.encode(Declaration(2, Format.RdfsTurtle, ".ohi", Seq.empty)) shouldBe
        "# Ontologies used by this project.\n# Docs: https://ohi.dev/docs/guides/projects/\n" +
        "format_version: 2\ndefault_format: rdfs-turtle\ndownload_dir: .ohi\ndependencies: []\n"
    }

    "quote a configured directory without changing its contents" in {
      val declaration = Declaration(2, Format.LinkmlYaml, "vocab #1", Seq.empty)
      val encoded = DeclarationYaml.encode(declaration)
      val data = ujson.read(JsonUtil.yamlToJson(parseYaml(encoded).toOption.get))
      data("download_dir").str shouldBe "vocab #1"
      data("dependencies") shouldBe ujson.Arr()
    }

    "preserve supplied order, distinct formats and distinct versions in deterministic YAML" in {
      val declaration = Declaration(
        formatVersion = 2,
        defaultFormat = Format.LinkmlYaml,
        downloadDir = ".ohi",
        dependencies = Seq(
          ProjectDependency(ontology = "w3c/dcat@3", format = Format.OwlTurtle),
          ProjectDependency(ontology = "w3c/dcat@2", format = Format.LinkmlYaml),
          ProjectDependency(ontology = "w3c/dcat@2", format = Format.OwlTurtle),
        ),
      )
      val encoded = DeclarationYaml.encode(declaration)
      encoded should startWith(
        "# Ontologies used by this project.\n# Docs: https://ohi.dev/docs/guides/projects/\n",
      )
      encoded.indexOf("format_version:") should be < encoded.indexOf("default_format:")
      encoded.indexOf("default_format:") should be < encoded.indexOf("download_dir:")
      encoded.indexOf("download_dir:") should be < encoded.indexOf("dependencies:")
      encoded.indexOf("ontology:") should be < encoded.indexOf("format: owl-turtle")
      val data = ujson.read(JsonUtil.yamlToJson(parseYaml(encoded).toOption.get))
      data shouldBe ujson.read(
        """{"format_version":2,"default_format":"linkml-yaml","download_dir":".ohi","dependencies":[
        {"ontology":"w3c/dcat@3","format":"owl-turtle"},
        {"ontology":"w3c/dcat@2","format":"linkml-yaml"},
        {"ontology":"w3c/dcat@2","format":"owl-turtle"}]}""",
      )
      DeclarationYaml.encode(declaration) shouldBe encoded
    }
  }
}
