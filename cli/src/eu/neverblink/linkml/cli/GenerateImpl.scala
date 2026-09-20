package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.ossie.OssieGenerator
import eu.neverblink.linkml.generator.rdf.RdfFormat
import eu.neverblink.linkml.generator.util.JsonOutputFormat
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator
import eu.neverblink.linkml.schemaview.SchemaView

import java.io.OutputStream

// Scala

@HelpMessage("Generate Scala classes from a LinkML model")
@ArgsName("<input-file>")
final case class ScalaOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.scala.fields
    @HelpMessage(
      "Package name for generated Scala classes. Default value: eu.neverblink.linkml.metamodel",
    )
    `package`: String = "eu.neverblink.linkml.metamodel",
    @HelpMessage(
      "Whether to generate a 'Prefixes' object with the model's emit_prefixes inside. Default value: true",
    )
    generateEmitPrefixes: Boolean = true,
    // END GENERATED OPTIONS cli.scala.fields
) extends HasGenerateOptions

object Scala extends ManyFilesGenerate[ScalaOptions] {
  override protected def generatorName: String = "scala"

  override protected[cli] def generate(
      options: ScalaOptions,
  )(using SchemaView): Iterable[(String, String)] =
    ScalaGenerator().generate(
      // BEGIN GENERATED OPTIONS cli.scala.options
      ScalaGenerator.Options(
        `package` = options.`package`,
        generateEmitPrefixes = options.generateEmitPrefixes,
      ),
      // END GENERATED OPTIONS cli.scala.options
    )
}

// JSON Schema

@HelpMessage("Generate JSON Schema from a LinkML model")
@ArgsName("<input-file>")
final case class JsonSchemaOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.json_schema.fields
    @HelpMessage(
      "Whether the generated JSON Schema should allow additionalProperties for classes. Default: false",
    )
    open: Boolean = false,
    @HelpMessage("If provided, override the schema tree_root with this class")
    treeRootOverride: Option[String] = None,
    @HelpMessage(
      "If provided, override the tree_root class' tree_root_as extension. One of: 'plain', 'optional', 'list', 'compact_dict', 'simple_dict'. If no extension or override is provided, the default behavior is 'plain'.See the documentation for more information.",
    )
    treeRootInlineTypeOverride: Option[String] = None,
    @HelpMessage("Allow null values for optional slots. Default: false")
    includeNull: Boolean = false,
    // END GENERATED OPTIONS cli.json_schema.fields
) extends HasGenerateOptions

object JsonSchema extends StreamGenerate[JsonSchemaOptions] {
  override protected def generatorName: String = "json-schema"

  override protected[cli] def generate(options: JsonSchemaOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    JsonSchemaGenerator().writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.json_schema.options
      JsonSchemaGenerator.Options(
        open = options.open,
        treeRoot = options.treeRootOverride,
        treeRootInlineType = options.treeRootInlineTypeOverride,
        includeNull = options.includeNull,
      ),
      // END GENERATED OPTIONS cli.json_schema.options
    )
}

// SHACL

@HelpMessage("Generate SHACL shapes from a LinkML model")
@ArgsName("<input-file>")
final case class ShaclOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.shacl.fields
    @HelpMessage(
      "Whether the generated SHACL should allow additional properties for classes. Default: false",
    )
    open: Boolean = false,
    @HelpMessage(
      "Whether to include only classes from the root schema. This is useful if you intend to generate SHACL shapes for each schema file separately, and you don't need the imported classes to be included in the generated SHACL shapes. Default: false",
    )
    onlyClassesFromRootSchema: Boolean = false,
    @HelpMessage(
      "RDF serialization format: 'ttl' (Turtle – prefixed and pretty-printed, the default) or 'nt' (N-Triples – one statement per line). Default: ttl",
    )
    format: String = "ttl",
    // END GENERATED OPTIONS cli.shacl.fields
) extends HasGenerateOptions

object Shacl extends StreamGenerate[ShaclOptions] {
  override protected def generatorName: String = "shacl"

  override protected[cli] def generate(options: ShaclOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    ShaclGenerator().writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.shacl.options
      ShaclGenerator.Options(
        open = options.open,
        onlyClassesFromRootSchema = options.onlyClassesFromRootSchema,
        format =
          RdfOutput.parse(options.format).getOrElse(err(RdfOutput.unknownFormat(options.format))),
      ),
      // END GENERATED OPTIONS cli.shacl.options
    )
}

// RDFS

@HelpMessage("Generate RDF schema from a LinkML model")
@ArgsName("<input-file>")
final case class RdfsOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.rdfs.fields
    @HelpMessage(
      "Whether to include only classes from the root schema. This is useful if you intend to generate RDFS for each schema file separately, and you don't need the imported classes to be included in the RDFS. Default: false",
    )
    onlyClassesFromRootSchema: Boolean = false,
    @HelpMessage(
      "RDF serialization format: 'ttl' (Turtle – prefixed and pretty-printed, the default) or 'nt' (N-Triples – one statement per line). Default: ttl",
    )
    format: String = "ttl",
    // END GENERATED OPTIONS cli.rdfs.fields
) extends HasGenerateOptions

object Rdfs extends StreamGenerate[RdfsOptions] {
  override protected def generatorName: String = "rdfs"

  override protected[cli] def generate(options: RdfsOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    RdfsGenerator().writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.rdfs.options
      RdfsGenerator.Options(
        onlyClassesFromRootSchema = options.onlyClassesFromRootSchema,
        format =
          RdfOutput.parse(options.format).getOrElse(err(RdfOutput.unknownFormat(options.format))),
      ),
      // END GENERATED OPTIONS cli.rdfs.options
    )
}

/** The `--format` flag the SHACL and RDFS generate commands share. */
private object RdfOutput {

  /** The format named on the command line, or None if it is not one this tool writes. */
  def parse(format: String): Option[RdfFormat] = format.toLowerCase match {
    case "nt" | "ntriples" => Some(RdfFormat.nt)
    case "ttl" | "turtle" => Some(RdfFormat.ttl)
    case _ => None
  }

  def unknownFormat(format: String): String =
    s"Unknown RDF format '$format'. Supported formats: nt, ttl."
}

private val outputFormatHelp: String =
  "Serialization format: 'yaml' or 'json'. Default: yaml"

// LinkML -> LinkML

@HelpMessage(
  "Materialize a derived LinkML schema from a LinkML model.",
)
@ArgsName("<input-file>")
final case class LinkMlOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.linkml.fields
    @HelpMessage("Whether to skip the class derivation. Default: false.")
    skipDerivation: Boolean = false,
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage("Serialization format: 'yaml' or 'json'. Default: yaml")
    format: String = "yaml",
    // END GENERATED OPTIONS cli.linkml.fields
) extends HasGenerateOptions

object LinkMl extends StreamGenerate[LinkMlOptions] {
  override protected def generatorName: String = "linkml"

  override protected[cli] def generate(options: LinkMlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    LinkMlGenerator().writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.linkml.options
      LinkMlGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        skipClassDerivation = options.skipDerivation,
        outputFormat = JsonOutputFormat.parse(options.format).getOrElse(
          err(JsonOutputFormat.unknownFormat(options.format)),
        ),
      ),
      // END GENERATED OPTIONS cli.linkml.options
    )
}

// Table Schema

@HelpMessage(
  "Generate a Frictionless Data Package from a LinkML model. " +
    "If --to is a directory, the package is written as a datapackage.json file plus one " +
    "schemas/<table>.json per table. If --to is a .json file, or with no --to at all, it is " +
    "written as a single file with table schemas inlined.",
)
@ArgsName("<input-file>")
final case class FrictionlessOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.frictionless.fields
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(
      "Whether to skip classes that have no identifier slot. Such a table gets no primary key and nothing can reference it, so it is often not useful. Default: false",
    )
    skipClassesWithoutIdentifier: Boolean = false,
    // END GENERATED OPTIONS cli.frictionless.fields
) extends HasGenerateOptions

object Frictionless extends SplitGenerate[FrictionlessOptions] {
  override protected def generatorName: String = "frictionless"

  override protected def singleFileExtension: String = ".json"

  private def generator(options: FrictionlessOptions): FrictionlessGenerator.Options =
    // BEGIN GENERATED OPTIONS cli.frictionless.options
    FrictionlessGenerator.Options(
      pruningMode = options.pruning.resolvedPruningMode,
      skipClassesWithoutIdentifier = options.skipClassesWithoutIdentifier,
    )
    // END GENERATED OPTIONS cli.frictionless.options

  override protected[cli] def generateSingle(options: FrictionlessOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    FrictionlessGenerator().writeTo(out, generator(options))

  override protected[cli] def generateFiles(options: FrictionlessOptions)(using
      SchemaView,
  ): Iterable[(String, String)] =
    FrictionlessGenerator().generateFiles(generator(options))
}

// GraphQL

@HelpMessage(
  "Generate a GraphQL Schema from a LinkML model. " +
    "Only generates types/interfaces/scalar/enums, queries must be added manually.",
)
@ArgsName("<input-file>")
final case class GraphQlOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.graphql.fields
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    // END GENERATED OPTIONS cli.graphql.fields
) extends HasGenerateOptions

object GraphQl extends StreamGenerate[GraphQlOptions] {
  override protected def generatorName: String = "graphql"

  override protected[cli] def generate(options: GraphQlOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    GraphQlGenerator().writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.graphql.options
      GraphQlGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
      ),
      // END GENERATED OPTIONS cli.graphql.options
    )
}

// Apache Ossie ontology

@HelpMessage(
  "Generate an Apache Ossie ontology from a LinkML model.",
)
@ArgsName("<input-file>")
final case class OssieOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.ossie.fields
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage("Serialization format: 'yaml' or 'json'. Default: yaml")
    format: String = "yaml",
    // END GENERATED OPTIONS cli.ossie.fields
) extends HasGenerateOptions

object Ossie extends StreamGenerate[OssieOptions] {
  override protected def generatorName: String = "ossie"

  override protected[cli] def generate(options: OssieOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    OssieGenerator().writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.ossie.options
      OssieGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        outputFormat = JsonOutputFormat.parse(options.format).getOrElse(
          err(JsonOutputFormat.unknownFormat(options.format)),
        ),
      ),
      // END GENERATED OPTIONS cli.ossie.options
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
    // BEGIN GENERATED OPTIONS cli.er_diagram.fields
    @Recurse
    pruning: PruningOptions = PruningOptions(),
    @HelpMessage(
      "Whether to mark optional attributes with a trailing '?' on their type. Mermaid understands this from version 11.16 onwards, and older renderers reject the whole diagram rather than just the marker, so pass --optional-marker=false when the diagram is headed somewhere that pins an older Mermaid. Default value: true",
    )
    optionalMarker: Boolean = true,
    // END GENERATED OPTIONS cli.er_diagram.fields
) extends HasGenerateOptions

object ErDiagram extends StreamGenerate[ErDiagramOptions] {
  override protected def generatorName: String = "er-diagram"

  override protected[cli] def generate(options: ErDiagramOptions, out: OutputStream)(using
      SchemaView,
  ): Unit =
    ErDiagramGenerator().writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.er_diagram.options
      ErDiagramGenerator.Options(
        pruningMode = options.pruning.resolvedPruningMode,
        optionalMarker = options.optionalMarker,
      ),
      // END GENERATED OPTIONS cli.er_diagram.options
    )
}

@HelpMessage(
  "Generate translation dictionaries (in JSON), " +
    "from the original names used in the schema to the names used in generated outputs.",
)
@ArgsName("<input-file>")
final case class TranslationOptions(
    @Recurse
    common: GenerateOptions,
    // BEGIN GENERATED OPTIONS cli.translation.fields
    @HelpMessage(
      "Framework name to generate a translation dict for. One of: " + TranslationGenerator.availableValues,
    )
    target: String = "base",
    // END GENERATED OPTIONS cli.translation.fields
) extends HasGenerateOptions

object Translation extends StreamGenerate[TranslationOptions] {
  override protected def generatorName: String = "translation"

  override protected[cli] def generate(options: TranslationOptions, out: OutputStream)(using
      sv: SchemaView,
  ): Unit = {
    TranslationGenerator(using sv).writeTo(
      out,
      // BEGIN GENERATED OPTIONS cli.translation.options
      TranslationGenerator.Options(
        to = options.target,
      ),
      // END GENERATED OPTIONS cli.translation.options
    )
  }
}
