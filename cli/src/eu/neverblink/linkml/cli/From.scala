package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.generator.ossie.OssieImporter
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
