package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator.PruningMode
import eu.neverblink.linkml.generator.rdf.{RdfSink, RdfUtils}
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator
import eu.neverblink.linkml.schemaview.{Case, SchemaView}

import java.io.OutputStream

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

object Scala extends StringGenerate[ScalaOptions] {
  override protected def generatorName: String = "scala"

  override protected[cli] def generate(
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
    @HelpMessage(
      "Whether the generated JSON Schema should allow additionalProperties for classes. Default: false",
    )
    open: Boolean = false,
    @HelpMessage("If provided, override the schema tree_root with this class")
    treeRootOverride: Option[String] = None,
) extends HasGenerateOptions

object JsonSchema extends StringGenerate[JsonSchemaOptions] {
  override protected def generatorName: String = "json-schema"

  override protected[cli] def generate(
      options: JsonSchemaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", JsonSchemaGenerator().serialize(options.open, options.treeRootOverride)),
    )
}

// SHACL

@HelpMessage("Generate SHACL shapes from a LinkML model")
@ArgsName("<input-file>")
final case class ShaclOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Whether the generated SHACL should allow additional properties for classes. Default: false",
    )
    open: Boolean = false,
    @HelpMessage(
      "Whether to include only classes from the root schema. " +
        "This is useful if you intend to generate SHACL shapes for each schema file separately, " +
        "and you don't need the imported classes to be included in the generated SHACL shapes. Default: false",
    )
    onlyClassesFromRootSchema: Boolean = false,
    @HelpMessage(RdfOutput.formatHelp)
    format: String = RdfOutput.defaultFormat,
) extends HasGenerateOptions

object Shacl extends StreamGenerate[ShaclOptions] {
  override protected def generatorName: String = "shacl"

  override protected[cli] def generate(options: ShaclOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    if !RdfOutput.write(
        out,
        options.format,
        ShaclGenerator().generate(_, options.open, options.onlyClassesFromRootSchema),
      )
    then err(RdfOutput.unknownFormat(options.format))
}

// RDFS

@HelpMessage("Generate RDF schema from a LinkML model")
@ArgsName("<input-file>")
final case class RdfsOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage(
      "Whether to include only classes from the root schema. " +
        "This is useful if you intend to generate RDFS for each schema file separately, " +
        "and you don't need the imported classes to be included in the RDFS. Default: false",
    )
    onlyClassesFromRootSchema: Boolean = false,
    @HelpMessage(RdfOutput.formatHelp)
    format: String = RdfOutput.defaultFormat,
) extends HasGenerateOptions

object Rdfs extends StreamGenerate[RdfsOptions] {
  override protected def generatorName: String = "rdfs"

  override protected[cli] def generate(options: RdfsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    if !RdfOutput.write(
        out,
        options.format,
        RdfsGenerator().generate(_, options.onlyClassesFromRootSchema),
      )
    then err(RdfOutput.unknownFormat(options.format))
}

/** Shared RDF serialization dispatch for the SHACL and RDFS generate commands. */
private object RdfOutput {
  val defaultFormat: String = "nt"

  val formatHelp: String =
    "RDF serialization format: 'nt' (N-Triples – fast, streamed, the default) or " +
      "'ttl' (Turtle – slower, but prefixed and pretty-printed). Default: nt"

  def unknownFormat(format: String): String =
    s"Unknown RDF format '$format'. Supported formats: nt, ttl."

  /** Stream the generator output (pushed via [[gen]]) to [[out]] in the requested format. Returns
    * `false` if the format is not recognized (nothing is written).
    */
  def write(out: OutputStream, format: String, gen: RdfSink => Unit): Boolean =
    format.toLowerCase match {
      case "nt" | "ntriples" =>
        RdfUtils.streamNTriples(out, gen)
        true
      case "ttl" | "turtle" =>
        RdfUtils.streamTurtle(out, gen)
        true
      case _ => false
    }
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

object LinkMl extends StringGenerate[LinkMlOptions] {
  override protected def generatorName: String = "linkml"

  override protected[cli] def generate(
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

// Table Schema

@HelpMessage("Generate a Frictionless Table Schema from a LinkML model.")
@ArgsName("<input-file>")
final case class TableSchemaOptions(
    @Recurse
    common: GenerateOptions,
    @HelpMessage("Tree root class name to use instead of the schema defined tree_root.")
    treeRoot: Option[String] = None,
) extends HasGenerateOptions

object TableSchema extends StringGenerate[TableSchemaOptions] {
  override protected def generatorName: String = "table-schema"

  override protected[cli] def generate(
      options: TableSchemaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    Seq(
      ("", TableSchemaGenerator().serialize(options.treeRoot)),
    )
}
