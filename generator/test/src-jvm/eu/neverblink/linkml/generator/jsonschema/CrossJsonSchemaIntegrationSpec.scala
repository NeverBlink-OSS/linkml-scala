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
    "unionOf" -> "LinkML-py ignores constraints of union_of members",
    "lengths" -> "LinkML-Python's metamodel has no `minimum_length` and `maximum_length`",
    "lengthsNarrowing" -> "LinkML-Python's metamodel has no `minimum_length` and `maximum_length`",
    "boundsNarrowing" ->
      "LinkML-Python writes a `union_of` type as a string, and lets a child type, a slot and a child slot widen bounds",
  ) ++ Seq("treeRootAsCompactDict", "treeRootAsList", "treeRootAsSimpleDict").map(
    _ -> noTreeRootAs,
  )

  private def noTreeRootAs: String =
    "`tree_root_as` is a LinkML-Scala extension, so LinkML-py expects one object as a document"

  override val skipInstances: Map[(String, String), String] = Map(
    "treeRootAsOptional" -> "empty" -> noTreeRootAs,
    "referenceInteger" -> "zero" -> "LinkML-py doesn't check the identifier's constraints in a reference",
    "memberCardinality" -> "oneInteger" -> "LinkML-py ignores cardinality in `any_of` members",
    "memberCardinality" -> "integerAndString" ->
      "LinkML-py ignores cardinality in `any_of` members",
    "memberCardinality" -> "twoStrings" -> "LinkML-py ignores cardinality in `any_of` members",
    // See docs/slot_usage_inheritance.md
    "anyOfWidening" -> "booleanNotInBase" -> "LinkML-py keeps only the most specific `any_of`",
    "anyOfMixins" -> "booleanNotInBase" -> "LinkML-py keeps only the most specific `any_of`",
    "allOfInheritance" -> "failsBase" -> "LinkML-py keeps only the most specific `all_of`",
    "exactlyOneOfInheritance" -> "matchesBothInBase" ->
      "LinkML-py keeps only the most specific `exactly_one_of`",
    "noneOfInheritance" -> "matchesBase" -> "LinkML-py keeps only the most specific `none_of`",
    "anyOfChain" -> "stringNotInChild" -> "LinkML-py keeps only the most specific `any_of`",
    "allOfMixins" -> "failsBase" -> "LinkML-py keeps only the most specific `all_of`",
    "noneOfMixins" -> "matchesBase" -> "LinkML-py keeps only the most specific `none_of`",
    "exactlyOneOfMixins" -> "matchesBothInBase" -> "LinkML-py keeps only the most specific `exactly_one_of`",
    "exactlyOneOfWidening" -> "matchesBothInBase" -> "LinkML-py keeps only the most specific `exactly_one_of`",
    "allOfNoneOfChain" -> "failsBaseAllOf" -> "LinkML-py keeps only the most specific `all_of`",
    "allOfNoneOfChain" -> "failsBaseNoneOf" -> "LinkML-py keeps only the most specific `none_of`",
    "allOfNoneOfChain" -> "failsChildNoneOf" -> "LinkML-py keeps only the most specific `none_of`",
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
