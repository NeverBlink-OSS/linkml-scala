package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.cli.options.{FromOssieCliOptions, FromOwlCliOptions}
import eu.neverblink.linkml.generator.ossie.OssieImporter
import eu.neverblink.linkml.generator.owl.OwlImporter

import java.io.{ByteArrayOutputStream, InputStream, OutputStream}
import scala.util.control.NonFatal

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
    // The schema is made in memory first, so a document that can't be imported prints only the
    // reason, and leaves no empty or partially written file behind.
    val schema = new ByteArrayOutputStream()
    val in = os.read.inputStream(path)
    try convert(options, in, schema)
    catch { case NonFatal(e) => err(s"Cannot import $input", e) }
    finally in.close()
    writeToFileOrStdout(options.common.to, out => schema.writeTo(out))
}

// Ossie

@HelpMessage("Convert an Apache Ossie ontology\nThe result is a LinkML schema.")
@ArgsName("<input-file>")
final case class FromOssieOptions(
    @Recurse
    common: FromOptions,
    @Recurse
    importer: FromOssieCliOptions = FromOssieCliOptions(),
) extends HasFromOptions

object FromOssie extends From[FromOssieOptions] {
  override protected def formatName: String = "ossie"

  override protected def convert(
      options: FromOssieOptions,
      in: InputStream,
      out: OutputStream,
  ): Unit =
    OssieImporter().writeTo(in, out, options.importer.toOptions)
}

// OWL

@HelpMessage(
  "Convert an OWL ontology or RDFS vocabulary\n" +
    "The result is a LinkML schema. The input is read as Turtle or N-Triples.",
)
@ArgsName("<input-file>")
final case class FromOwlOptions(
    @Recurse
    common: FromOptions,
    @Recurse
    importer: FromOwlCliOptions = FromOwlCliOptions(),
    @HelpMessage("List what could not be imported, on stderr. Default: false")
    listNotImported: Boolean = false,
) extends HasFromOptions

object FromOwl extends From[FromOwlOptions] {
  override protected def formatName: String = "owl"

  override protected def convert(
      options: FromOwlOptions,
      in: InputStream,
      out: OutputStream,
  ): Unit = {
    // Relative imports are relative to where the schema is written.
    val base = options.common.to.fold("")(to => (os.Path(to, os.pwd) / os.up).toString)
    val importer = OwlImporter(base = base)
    val importerOptions =
      try options.importer.toOptions
      catch { case e: IllegalArgumentException => err(e.getMessage) }
    val result = importer.importWithWarnings(in, importerOptions)
    if options.listNotImported then
      result.warnings.foreach(w => printLine(s"Not imported: $w", toStderr = true))
    importer.writeSchema(result.schema, out, importerOptions.format, importerOptions.fullUris)
  }
}
