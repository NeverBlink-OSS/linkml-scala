package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.util.JsonOutputFormat
import com.networknt.schema.InputFormat
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

/** Checks the `ossie` generator's output against Apache Ossie's own JSON Schema for ontology
  * definitions.
  */
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

        "the ontology reads back into the model it was written from" in {
          processSkip(entry.name, "round-trip")
          val parsed = parseYaml(asYaml).getOrElse(fail(s"not YAML:\n$asYaml"))
          OssieOntology.codec.decode(parsed) shouldBe generator.generate()
        }

        "the upstream Ossie validator accepts the output" in {
          processSkip(entry.name, "upstream")
          val (python, repo) = upstreamValidator.getOrElse(
            cancel(
              "No apache/ossie checkout and Python environment to validate with. Set LINKML_OSSIE_REPO " +
                "to a checkout (the `ossieRepo` mill task clones one) and create the .venv from " +
                "requirements.txt.",
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

  "the mapping" should {
    "include the schema's ai_context extension" in {
      val ontology = ontologyOf(
        """
          |extensions:
          |  ai_context:
          |    value:
          |      instructions: Prefer the full name.
          |      synonyms: [human, individual]
          |classes:
          |  Person:
          |    attributes:
          |      name: {}
        """.stripMargin,
      )
      ontology should include("ai_context")
      ontology should include("Prefer the full name.")
      ontology should include("individual")
      OssieSchema.validate(ontology, InputFormat.YAML) shouldBe empty
    }

    "carry a plain-string ai_context through" in {
      val ontology = ontologyOf(
        """
          |extensions:
          |  ai_context: Answer questions about people.
          |classes:
          |  Person:
          |    attributes:
          |      name: {}
        """.stripMargin,
      )
      ontology should include("Answer questions about people.")
      OssieSchema.validate(ontology, InputFormat.YAML) shouldBe empty
    }

    "skip ai_context when the schema declares no such extension" in {
      ontologyOf(
        """
          |classes:
          |  Person:
          |    attributes:
          |      name: {}
        """.stripMargin,
      ) should not include "ai_context"
    }

    "identify a concept by its unique_keys when it has no identifier or key" in {
      val ontology = ontologyOf(
        """
          |slots:
          |  order: {}
          |  nr:
          |    range: integer
          |classes:
          |  OrderLine:
          |    slots: [order, nr]
          |    unique_keys:
          |      line:
          |        unique_key_slots: [order, nr]
        """.stripMargin,
      )
      // Both slots of the key, in the order the key declares them.
      ontology should include("identify_by")
      ontology should include("- order")
      ontology should include("- nr")
      // But neither slot identifies a line on its own, so it's not a OneToOne relationship.
      ontology should not include "OneToOne"
    }

    "prefer an identifier over unique_keys" in {
      val ontology = ontologyOf(
        """
          |slots:
          |  a: {}
          |  b: {}
          |classes:
          |  Thing:
          |    slots: [a, b]
          |    unique_keys:
          |      pair:
          |        unique_key_slots: [a, b]
          |    attributes:
          |      id:
          |        identifier: true
        """.stripMargin,
      )
      ontology should include("identify_by:\n      - id\n")
    }

    "validate against a live core-spec ref, not a skipped one" in {
      // Check if the $ref in the JSON Schema of Ossie is resolved correctly
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

    "use a slot's alias verbatim as the relationship name" in {
      val ontology = ontologyOf(
        """
          |classes:
          |  Person:
          |    attributes:
          |      full_name:
          |        alias: fullName
        """.stripMargin,
      )
      ontology should include("name: fullName")
      // The verbalization is space-cased regardless, so an alias in any casing reads as words.
      ontology should include("{Person} full name {String}")
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
         |$body
         |""".stripMargin
    given SchemaView = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schema))
    OssieGenerator().serialize()
  }

  private val repoRoot: os.Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path(_)).getOrElse(os.pwd)

  /** Python interpreter and apache/ossie checkout to run the upstream validator with, if both are
    * available.
    *
    * The validator needs `jsonschema` and `pyyaml`, which are in `requirements.txt`.
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
