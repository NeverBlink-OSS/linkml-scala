package eu.neverblink.linkml.generator.jsonschema

import com.networknt.schema.{ExecutionConfig, ExecutionContext, InputFormat, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JsonSchemaIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  val sr: SchemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft202012)
  override val skipModels: Map[String, String] = JsonSchemaGeneratorSpec.skipModels

  "Json Schema generator" should {
    for entry <- ModelCatalogue.all do
      s"generate Json Schema for model '${entry.model.root.name}'" when {

        lazy val jsonSchema = JsonSchemaGenerator(using entry.model).serialize()

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
