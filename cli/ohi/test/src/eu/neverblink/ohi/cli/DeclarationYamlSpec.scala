package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.cli.generated.declaration.{ArtifactSelection, Declaration}
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tools.jackson.databind.json.JsonMapper
import org.virtuslab.yaml.parseYaml

class DeclarationYamlSpec extends AnyWordSpec, Matchers {
  private val selection =
    "- base_url: https://hub.example/releases/z/\n  url: https://files.example/z.ttl?revision=2\n"

  private def rejected(yaml: String): ProjectException = {
    val failure = intercept[ProjectException](DeclarationYaml.decode(yaml))
    failure.code shouldBe ProjectErrorCode.InvalidDeclaration
    failure.getMessage should not be empty
    failure
  }

  "DeclarationYaml.decode" should {
    "reject an unknown root field" in {
      rejected("format_version: 1\nselections: []\nextra: true\n")
    }

    "reject an unknown selection field" in {
      rejected(s"format_version: 1\nselections:\n${selection}  extra: true\n")
    }

    "reject an unsupported format version" in {
      rejected("format_version: 2\nselections: []\n")
    }

    "reject a declaration without a selections list" in {
      rejected("format_version: 1\n")
    }

    "reject a selection whose base URL is not a URI" in {
      rejected("format_version: 1\nselections:\n- base_url: not a URI\n  url: urn:file:z\n")
    }

    "reject a selection listed twice" in {
      rejected(s"format_version: 1\nselections:\n$selection$selection").getMessage should include(
        "selection pair must occur once",
      )
    }
  }

  "DeclarationYaml.encode" should {
    "preserve exact URLs and supplied order in deterministic YAML" in {
      val declaration = Declaration(
        1,
        Seq(
          ArtifactSelection(Uri("urn:release:z"), Uri("https://files.example/Z%2F.ttl?q=1")),
          ArtifactSelection(Uri("urn:release:a"), Uri("https://files.example/a.ttl")),
        ),
      )
      val encoded = DeclarationYaml.encode(declaration)
      val mapper = JsonMapper.builder().build()
      val data = mapper.readTree(JsonUtil.yamlToJson(parseYaml(encoded).toOption.get))
      data shouldBe mapper.readTree("""{"format_version":1,"selections":[
        {"base_url":"urn:release:z","url":"https://files.example/Z%2F.ttl?q=1"},
        {"base_url":"urn:release:a","url":"https://files.example/a.ttl"}]}""")
      DeclarationYaml.encode(declaration) shouldBe encoded
    }
  }
}
