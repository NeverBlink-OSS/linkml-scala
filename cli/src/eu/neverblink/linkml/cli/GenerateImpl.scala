package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.PruningMode
import eu.neverblink.linkml.generator.rdf.RdfUtils
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.schemaview.SchemaView

// Scala

@HelpMessage("Generate Scala classes from a LinkML model")
@ArgsName("<input-file>")
final case class ScalaOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Package name for generated Scala classes. Default value: eu.neverblink.linkml.metamodel",
    )
    `package`: String = "eu.neverblink.linkml.metamodel",
) extends HasGenerateOptions

object Scala extends Generate[ScalaOptions] {
  override protected def generatorName: String = "scala"

  override protected def generate(
      options: ScalaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    ScalaGenerator().generate(options.`package`)
}

// JSON Schema

@HelpMessage("Generate JSON Schema from a LinkML model")
@ArgsName("<input-file>")
final case class JsonSchemaOptions(
    @Recurse
    common: GenerateOptions,
) extends HasGenerateOptions

object JsonSchema extends Generate[JsonSchemaOptions] {
  override protected def generatorName: String = "json-schema"

  override protected def generate(
      options: JsonSchemaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", JsonSchemaGenerator().serialize()),
    )
}

// SHACL

@HelpMessage("Generate SHACL shapes from a LinkML model")
@ArgsName("<input-file>")
final case class ShaclOptions(
    @Recurse
    common: GenerateOptions,
) extends HasGenerateOptions

object Shacl extends Generate[ShaclOptions] {
  override protected def generatorName: String = "shacl"

  override protected def generate(
      options: ShaclOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", RdfUtils.toTurtle(ShaclGenerator().generate())),
    )
}

@HelpMessage(
  "Materialize a derived LinkML schema from a LinkML model. " +
    "Resolves imports, derives classes, and prunes elements unreachable from the schema tree_root. " +
    "If no tree root can be found, will instead prune elements that can't be reached from any of the root schema classes.",
)
@ArgsName("<input-file>")
final case class LinkMlOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Tree root class name to use instead of the schema defined tree_root. " +
        "Does nothing if not in tree root pruning mode.",
    )
    treeRootOverride: Option[String] = None,
    @HelpMessage("Whether to skip the class derivation.")
    skipDerivation: Boolean = false,
    @HelpMessage(
      "Prune elements that are unreachable from any root schema classes instead.",
    )
    schemaPrune: Boolean = false,
    @HelpMessage("Whether to skip the schema pruning.")
    skipPruning: Boolean = false,
    @HelpMessage("Whether to serialize the model using JSON instead of YAML.")
    asJson: Boolean = false,
) extends HasGenerateOptions

object LinkMl extends Generate[LinkMlOptions] {
  override protected def generatorName: String = "linkml"

  override protected def generate(
      options: LinkMlOptions,
  )(using SchemaView): Iterable[(String, String)] = {
    val pruningMode =
      if options.schemaPrune then PruningMode.schemaRoot
      else if options.skipPruning then PruningMode.skip
      else if options.treeRootOverride.isDefined then PruningMode.treeRoot(options.treeRootOverride)
      else PruningMode.treeRoot(None)
    Seq(
      (
        "",
        LinkMlGenerator().serialize(
          skipClassDerivation = options.skipDerivation,
          pruningMode = pruningMode,
          asJson = options.asJson,
        ),
      ),
    )
  }
}
