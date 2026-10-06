package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.ossie.OssieImporter
import eu.neverblink.linkml.generator.owl.OwlImporter
import eu.neverblink.linkml.generator.owl.config.{OwlImportConfigImpl, OwlImportConfigs}
import eu.neverblink.linkml.generator.util.JsonOutputFormat

import java.io.{InputStream, OutputStream}

final case class FromOptions(
    @HelpMessage(
      "Destination file. If not specified, output will be written to stdout.",
    )
    to: Option[String] = None,
)
object FromOptions:
  given Parser[FromOptions] = Parser.derive
  given Help[FromOptions] = Help.derive

trait HasFromOptions:
  @Recurse
  val common: FromOptions

/** A `from <format>` command: reads a document in some other format and returns the corresponding
  * LinkML schema. The opposite of `generate <name>`.
  */
abstract class From[T <: HasFromOptions: {Parser, Help}] extends BaseCommand[T] {
  protected def formatName: String

  /** Read the document from `in` and write the LinkML schema to `out`. */
  protected def convert(options: T, in: InputStream, out: OutputStream): Unit

  override final def group = "from"

  final override def names: List[List[String]] = List(
    List("from", formatName),
  )

  final override def run(options: T, remainingArgs: RemainingArgs): Unit =
    val inputs = remainingArgs.remaining
    if inputs.sizeIs > 1 then
      err(
        s"`from $formatName` takes a single input file, but ${inputs.size} were given: " +
          s"${inputs.mkString(", ")}.",
      )
    val input = inputs.headOption.getOrElse(err("Input file is required."))
    val path = os.Path(input, os.pwd)
    if !os.exists(path) then err(s"No such file: $input")
    val in = os.read.inputStream(path)
    try writeToFileOrStdout(options.common.to, out => convert(options, in, out))
    finally in.close()
}

// Ossie

@HelpMessage(
  "Read an Apache Ossie ontology and produce a corresponding LinkML schema.",
)
@ArgsName("<input-file>")
final case class FromOssieOptions(
    @Recurse
    common: FromOptions,
    @HelpMessage(
      "The `id` of the schema to produce. The default " +
        "is a placeholder built from the ontology's name.",
    )
    schemaId: Option[String] = None,
    @HelpMessage(outputFormatHelp)
    format: String = "yaml",
) extends HasFromOptions

object FromOssie extends From[FromOssieOptions] {
  override protected def formatName: String = "ossie"

  override protected def convert(
      options: FromOssieOptions,
      in: InputStream,
      out: OutputStream,
  ): Unit =
    OssieImporter().writeTo(
      in,
      out,
      OssieImporter.Options(
        schemaId = options.schemaId,
        outputFormat = JsonOutputFormat.parse(options.format)
          .getOrElse(err(JsonOutputFormat.unknownFormat(options.format))),
      ),
    )
}

// OWL

@HelpMessage(
  "Read an OWL ontology in Turtle or N-Triples and produce a corresponding LinkML schema.",
)
@ArgsName("<input-file>")
final case class FromOwlOptions(
    @Recurse
    common: FromOptions,
    @HelpMessage(
      "Path to a YAML file that changes how the ontology is mapped: names, prefixes, which " +
        "annotation properties fill which metaslots, and more. See docs/owl.md.",
    )
    config: Option[String] = None,
    @HelpMessage("The `id` of the schema to produce. The default is the ontology IRI.")
    schemaId: Option[String] = None,
    @HelpMessage("List what could not be imported, on stderr. Default: false")
    listNotImported: Boolean = false,
    @HelpMessage(
      "RDF syntax of the ontology: 'ttl' (Turtle, the default) or 'nt' (N-Triples).",
    )
    inputFormat: String = "ttl",
    @HelpMessage(outputFormatHelp)
    format: String = "yaml",
) extends HasFromOptions

object FromOwl extends From[FromOwlOptions] {
  override protected def formatName: String = "owl"

  override protected def convert(
      options: FromOwlOptions,
      in: InputStream,
      out: OutputStream,
  ): Unit = {
    val config = options.config.fold(OwlImportConfigImpl()) { file =>
      val path = os.Path(file, os.pwd)
      if !os.exists(path) then err(s"No such file: $file")
      try OwlImportConfigs.parse(os.read(path))
      catch case e: Exception => err(s"Cannot read the config $file: ${e.getMessage}")
    }
    val format = JsonOutputFormat.parse(options.format)
      .getOrElse(err(JsonOutputFormat.unknownFormat(options.format)))
    val inputFormat = RdfOutput.parse(options.inputFormat)
      .getOrElse(err(RdfOutput.unknownFormat(options.inputFormat)))
    // Relative imports are relative to where the schema is written.
    val base = options.common.to.fold("")(to => (os.Path(to, os.pwd) / os.up).toString)
    val result = OwlImporter().importWithWarnings(
      in,
      OwlImporter.Options(
        config = options.schemaId.fold(config)(id => config.copy(schemaId = Some(id))),
        base = base,
        inputFormat = inputFormat,
      ),
    )
    if options.listNotImported then
      result.warnings.foreach(w => printLine(s"Not imported: $w", toStderr = true))
    OwlImporter().writeSchema(result.schema, out, format)
  }
}
