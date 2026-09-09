package eu.neverblink.linkml.generator.ossie

import com.networknt.schema.InputFormat
import eu.neverblink.linkml.generator.util.JsonOutputFormat
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks the `ossie` generator's output against Apache Ossie's own JSON Schema. */
class OssieIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  import OssieIntegrationSpec.*

  "OssieGenerator" should {
    for entry <- ModelCatalogue.all do
      s"generate an ontology for model '${entry.name}'" when {
        lazy val generator = OssieGenerator(using entry.model)

        lazy val asJson = generator.serialize(
          OssieGenerator.Options(outputFormat = JsonOutputFormat.json),
        )
        lazy val asYaml = generator.serialize(
          OssieGenerator.Options(outputFormat = JsonOutputFormat.yaml),
        )

        "the JSON output validates against the Ossie ontology schema" in {
          processSkip(entry.name, "json")
          OssieSchema.validate(asJson, InputFormat.JSON) shouldBe empty
        }

        "the YAML output validates against the Ossie ontology schema" in {
          processSkip(entry.name, "yaml")
          OssieSchema.validate(asYaml, InputFormat.YAML) shouldBe empty
        }

        "the output does not include ontology mappings" in {
          processSkip(entry.name, "no-ontology-mappings")
          asJson should not include "\"ontology_mappings\""
        }

        "the upstream Ossie validator accepts the output" in {
          processSkip(entry.name, "upstream")
          val (python, repo) = upstreamValidator.getOrElse(
            cancel(
              "No apache/ossie checkout and Python environment to validate with. Set " +
                "LINKML_OSSIE_REPO to a checkout (the `ossieRepo` mill task clones one) and " +
                "create the .venv from requirements.txt.",
            ),
          )
          val document = os.temp(asYaml, suffix = ".yaml")
          val result = os.call(
            (
              python,
              repo / "validation" / "validate.py",
              document,
              "--schema",
              repo / "ontology" / "ontology.json",
            ),
            check = false,
          )
          withClue(s"${result.out.text()}${result.err.text()}\n$asYaml") {
            result.exitCode shouldBe 0
          }
        }
      }
  }

  "ai_context" should {
    "validate when it is an object" in {
      OssieSchema.validate(
        ontologyOf("""
          |extensions:
          |  ai_context:
          |    value:
          |      instructions: Prefer the full name.
          |      synonyms: [human, individual]
          |classes:
          |  Person:
          |    attributes:
          |      name: {}
          """),
        InputFormat.YAML,
      ) shouldBe empty
    }

    "validate when it is a plain string" in {
      OssieSchema.validate(
        ontologyOf("""
          |extensions:
          |  ai_context: Answer questions about people.
          |classes:
          |  Person:
          |    attributes:
          |      name: {}
          """),
        InputFormat.YAML,
      ) shouldBe empty
    }

    "be rejected when it is neither, so we know the $ref is live and not silently skipped" in {
      val errors = OssieSchema.validate(
        """version: 0.2.0.dev0
          |name: spec
          |ai_context:
          |  - not a string or an object
          |ontology:
          |  - concept: Person
          |    type: EntityType
          |""".stripMargin,
        InputFormat.YAML,
      )
      withClue("the AIContext ref is not being enforced: ")(errors should not be empty)
    }
  }
}

object OssieIntegrationSpec {

  /** The ontology for a one-off schema, as YAML. `body` is spliced into a minimal schema. */
  private def ontologyOf(body: String): String = {
    val schema =
      s"""id: https://example.org/spec
         |name: spec
         |prefixes:
         |  linkml: https://w3id.org/linkml/
         |  ex: https://example.org/
         |default_prefix: ex
         |default_range: string
         |imports:
         |  - linkml:types
         |${body.stripMargin}
         |""".stripMargin
    given SchemaView = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schema))
    OssieGenerator().serialize()
  }

  private val repoRoot: os.Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path(_)).getOrElse(os.pwd)

  /** Python interpreter and apache/ossie checkout to run the upstream validator with, if both are
    * available.
    */
  private lazy val upstreamValidator: Option[(os.Path, os.Path)] = {
    val python = repoRoot / ".venv" / "bin" / "python"
    val repo = Option(System.getenv("LINKML_OSSIE_REPO")).filter(_.nonEmpty).map(os.Path(_, os.pwd))
    for
      r <- repo
      if os.exists(r / "validation" / "validate.py") && os.exists(r / "ontology" / "ontology.json")
      if os.exists(python)
      if os.call((python, "-c", "import jsonschema, yaml"), check = false).exitCode == 0
    yield (python, r)
  }
}
