package eu.neverblink.linkml.generator.jsonschema

import com.networknt.schema.dialect.Dialects
import com.networknt.schema.{ExecutionConfig, ExecutionContext, InputFormat, SchemaRegistry}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import os.Path

class CrossJsonSchemaIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  val sr: SchemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft201909)
  val pwd: Path = Option(System.getenv("MILL_WORKSPACE_ROOT"))
    .map(Path(_))
    .getOrElse(os.pwd)
  val generatedModelsDir: Path = pwd / ".generated" / "tests" / "resources"
  override val globalEnable: Boolean = os.exists(generatedModelsDir) || System.getenv("CI") != null

  override val skipModels: Map[String, String] = Map(
    "anything" -> "Metamodel extended_types.yaml is not bundled in LinkML-py",
    "typeDesignator" -> "Type designators seem to be very broken in LinkML-py",
    "unionRange" -> "Metamodel extended_types.yaml is not bundled in LinkML-py",
  )

  override val skipInstances: Map[(String, String), String] = Map(
    // This seems to contradict the description of the `alias` slot
    // https://linkml.io/linkml-model/latest/docs/alias/
    "aliases" -> "nonRdf" -> "LinkML-py allows unaliased names to be used in addition to aliased ones",
  )

  "LinkML Python Json Schema generator" should {
    for entry <- ModelCatalogue.all do
      s"generate Json Schema for model '${entry.model.root.name}'" when {

        lazy val path = Path(entry.path).segments.toSeq
        lazy val jsonSchema = os.read(generatedModelsDir / path / "schema.json")

        for valid <- entry.validInstances.filter(_.json.isDefined).distinct do
          s"valid instance '${valid.name}'" in {
            processSkip(entry, valid)
            sr.getSchema(jsonSchema).validate(valid.json.get, InputFormat.JSON) shouldBe empty
          }
        for invalid <- entry.invalidInstances.filter(_.json.isDefined).distinct do
          s"invalid data '${invalid.name}'" in {
            processSkip(entry, invalid)
            sr.getSchema(jsonSchema).validate(
              invalid.json.get,
              InputFormat.JSON,
              (ec: ExecutionContext) =>
                ec.setExecutionConfig(
                  ExecutionConfig.builder().formatAssertionsEnabled(true).build(),
                ),
            ) should not be empty
          }
      }
  }
}
