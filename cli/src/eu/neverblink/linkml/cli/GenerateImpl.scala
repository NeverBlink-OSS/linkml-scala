package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.rdf.{RdfGenerator, RdfUtils}
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator
import eu.neverblink.linkml.schemaview.SchemaView

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

object Scala extends ManyFilesGenerate[ScalaOptions] {
  override protected def generatorName: String = "scala"

  override protected[cli] def generate(
      options: ScalaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    ScalaGenerator().generate(
      ScalaGenerator.Options(
        `package` = options.`package`,
        generateEmitPrefixes = options.generateEmitPrefixes,
      ),
    )
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
    @HelpMessage(
      "If provided, override the tree_root class' tree_root_as extension. " +
        "One of: 'plain', 'optional', 'list', 'compact_dict', 'simple_dict'. " +
        "If no extension or override is provided, the default behavior is 'plain'." +
        "See the documentation for more information.",
    )
    treeRootInlineTypeOverride: Option[String] = None,
) extends HasGenerateOptions

object JsonSchema extends StreamGenerate[JsonSchemaOptions] {
  override protected def generatorName: String = "json-schema"

  override protected[cli] def generate(options: JsonSchemaOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    JsonSchemaGenerator().writeTo(
      out,
      JsonSchemaGenerator.Options(
        options.open,
        options.treeRootOverride,
        options.treeRootInlineTypeOverride,
      ),
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
        ShaclGenerator(),
        ShaclGenerator.Options(
          open = options.open,
          onlyClassesFromRootSchema = options.onlyClassesFromRootSchema,
        ),
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
        RdfsGenerator(),
        RdfsGenerator.Options(onlyClassesFromRootSchema = options.onlyClassesFromRootSchema),
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

  /** Stream [[gen]]'s output to [[out]] in the requested format. Returns `false` if the format is
    * not recognized, in which case nothing is written.
    *
    * N-Triples is the generator's own business. Turtle is not: it needs RDF4J, which only this
    * module has, so it is layered on by handing the generator a different sink.
    */
  def write[O](out: OutputStream, format: String, gen: RdfGenerator[O], options: O): Boolean =
    format.toLowerCase match {
      case "nt" | "ntriples" =>
        gen.writeTo(out, options)
        true
      case "ttl" | "turtle" =>
        RdfUtils.streamTurtle(out, gen.generate(_, options))
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
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage("Format to serialize the model in. One of yaml|json. Default: yaml.")
    format: String = "yaml",
) extends HasGenerateOptions

object LinkMl extends StreamGenerate[LinkMlOptions] {
  override protected def generatorName: String = "linkml"

  override protected[cli] def generate(options: LinkMlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit = {
    val format = options.format.toLowerCase match {
      case "yaml" => LinkMlGenerator.OutputFormat.yaml
      case "yml" => LinkMlGenerator.OutputFormat.yaml
      case "json" => LinkMlGenerator.OutputFormat.json
      case s => err(s"Unknown output format: $s")
    }

    LinkMlGenerator().writeTo(
      out,
      LinkMlGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        skipClassDerivation = options.skipDerivation,
        outputFormat = format,
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

object TableSchema extends StreamGenerate[TableSchemaOptions] {
  override protected def generatorName: String = "table-schema"

  override protected[cli] def generate(options: TableSchemaOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    TableSchemaGenerator().writeTo(out, TableSchemaGenerator.Options(options.treeRoot))
}

// GraphQL

@HelpMessage(
  "Generate a GraphQL Schema from a LinkML model. " +
    "Provides a @linkml_uri directive for all elements with an URI. " +
    "Only generates types/interfaces/scalar/enums, queries must be added manually.",
)
@ArgsName("<input-file>")
final case class GraphQlOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    pruning: PruningOptions = PruningOptions(),
) extends HasGenerateOptions

object GraphQl extends StreamGenerate[GraphQlOptions] {
  override protected def generatorName: String = "graphql"

  override protected[cli] def generate(options: GraphQlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    GraphQlGenerator().writeTo(
      out,
      GraphQlGenerator.Options(options.pruning.resolvedPruningMode),
    )
}

// ER diagram

@HelpMessage(
  "Generate a Mermaid entity relationship diagram from a LinkML model. " +
    "Classes become entities, type- and enum-ranged slots become their attributes, and " +
    "class-ranged slots become relationship lines.",
)
@ArgsName("<input-file>")
final case class ErDiagramOptions(
    @Recurse
    common: GenerateOptions,
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(
      "Whether to mark optional attributes with a trailing '?' on their type. " +
        "Mermaid understands this from version 11.16 onwards, and older renderers reject the " +
        "whole diagram rather than just the marker, so pass --optional-marker=false when the " +
        "diagram is headed somewhere that pins an older Mermaid. Default value: true",
    )
    optionalMarker: Boolean = true,
) extends HasGenerateOptions

object ErDiagram extends StreamGenerate[ErDiagramOptions] {
  override protected def generatorName: String = "er-diagram"

  override protected[cli] def generate(options: ErDiagramOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    ErDiagramGenerator().writeTo(
      out,
      ErDiagramGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        optionalMarker = options.optionalMarker,
      ),
    )
}
