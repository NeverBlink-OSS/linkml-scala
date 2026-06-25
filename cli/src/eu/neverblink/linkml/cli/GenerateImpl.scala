package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.PruningMode
import eu.neverblink.linkml.generator.rdf.RdfUtils
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.schemaview.{Case, SchemaView}

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
    @HelpMessage(
      "Whether to generate a 'Prefixes' object with the model's emit_prefixes inside. Default value: true",
    )
    generateEmitPrefixes: Boolean = true,
) extends HasGenerateOptions

object Scala extends Generate[ScalaOptions] {
  override protected def generatorName: String = "scala"

  override protected def generate(
      options: ScalaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    ScalaGenerator().generate(options.`package`, options.generateEmitPrefixes)
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

// RDFS

@HelpMessage("Generate RDF schema from a LinkML model")
@ArgsName("<input-file>")
final case class RdfsOptions(
    @Recurse
    common: GenerateOptions,
) extends HasGenerateOptions

object Rdfs extends Generate[RdfsOptions] {
  override protected def generatorName: String = "rdfs"

  override protected def generate(
      options: RdfsOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", RdfUtils.toTurtle(RdfsGenerator().generate())),
    )
}

// LinkML -> LinkML

@HelpMessage(
  "Materialize a derived LinkML schema from a LinkML model. " +
    "Resolves imports, derives classes, and prunes unreachable elements.",
)
@ArgsName("<input-file>")
final case class LinkMlOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage("Whether to skip the class derivation. Default: false.")
    skipDerivation: Boolean = false,
    @HelpMessage(
      "Pruning mode to use for removing unused elements (classes, types, enums). " +
        "One of treeRoot|schemaRoot|skip.\n" +
        "treeRoot - remove all elements unreachable from the tree_root class.\n" +
        "schema - remove all elements unreachable from any of the classes defined in the root schema.\n" +
        "skip - do not remove unused elements.\n" +
        "Default: treeRoot.",
    )
    pruningMode: String = "treeRoot",
    @HelpMessage(
      "Tree root class name to use instead of the schema defined tree_root. " +
        "Does nothing if not in tree root pruning mode.",
    )
    treeRoot: Option[String] = None,
    @HelpMessage("Format to serialize the model in. One of yaml|json. Default: yaml.")
    format: String = "yaml",
) extends HasGenerateOptions

object LinkMl extends Generate[LinkMlOptions] {
  override protected def generatorName: String = "linkml"

  override protected def generate(
      options: LinkMlOptions,
  )(using SchemaView): Iterable[(String, String)] = {
    val pruningMode = Case.camelCase(options.pruningMode) match {
      case "treeRoot" => PruningMode.treeRoot(options.treeRoot)
      case "schema" => PruningMode.schemaRoot
      case "skip" => PruningMode.skip
    }

    val format = options.format.toLowerCase match {
      case "yaml" => LinkMlGenerator.OutputFormat.yaml
      case "yml" => LinkMlGenerator.OutputFormat.yaml
      case "json" => LinkMlGenerator.OutputFormat.json
      case s => err(s"Unknown output format: $s")
    }

    Seq(
      (
        "",
        LinkMlGenerator().serialize(
          skipClassDerivation = options.skipDerivation,
          pruningMode = pruningMode,
          outputFormat = format,
        ),
      ),
    )
  }
}
