package eu.neverblink.linkml.generator.jsonschema

import com.networknt.schema.dialect.Dialects
import com.networknt.schema.{InputFormat, SchemaRegistry}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import os.Path

class CrossJsonSchemaIntegrationSpec extends AnyWordSpec, Matchers {
  val sr: SchemaRegistry = SchemaRegistry.withDialect(Dialects.getDraft201909)
  val pwd: Path = Option(System.getenv("MILL_WORKSPACE_ROOT"))
    .map(Path(_))
    .getOrElse(os.pwd)
  val generatedModelsDir: Path = pwd / ".generated" / "tests" / "resources"
  val enabled: Boolean = System.getenv("CI") != null || os.exists(generatedModelsDir)
  val skipModels: Seq[String] = Seq(
    // Metamodel extended_types.yaml is not bundled
    "anything",
    "typeDesignator",
    "unionRange",
    // LinkML-py outputs an "accept all" for references
    "reference",
    "multivaluedReference",
    // LinkML-py outputs draft 202012 but declares 201909
    // "format": "date" doesn't get validated in 201909
    "typed",
    // LinkML-py outputs an "accept all" for an empty default_range
    // This is off-spec, which specifies this should be a "string":
    // https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#rule-populate-schema-metadata
    "explicitInlineImplicitlyAsList",
    "explicitInlineImplicitlyAsSimpleDict",
    // LinkML-py allows unaliased names to be used in addition to aliased ones
    // This seems to contradict the description of the `alias` slot
    // https://linkml.io/linkml-model/latest/docs/alias/
    "aliases",
  )

  "LinkML Python Json Schema generator" should {
    for entry <- ModelCatalogue.all do
      s"generate Json Schema for model '${entry.model.root.name}'" when {

        lazy val path = Path(entry.path).segments.toSeq
        lazy val jsonSchema = os.read(generatedModelsDir / path / "schema.json")

        for valid <- entry.validInstances.filter(_.json.isDefined).distinct do
          s"valid instance '${valid.name}'" in {
            assume(enabled && (!skipModels.contains(entry.model.root.name)))
            sr.getSchema(jsonSchema).validate(valid.json.get, InputFormat.JSON) shouldBe empty
          }
        for invalid <- entry.invalidInstances.filter(_.json.isDefined).distinct do
          s"invalid data '${invalid.name}'" in {
            assume(enabled && (!skipModels.contains(entry.model.root.name)))
            sr.getSchema(jsonSchema).validate(
              invalid.json.get,
              InputFormat.JSON,
            ) should not be empty
          }
      }
  }
}
