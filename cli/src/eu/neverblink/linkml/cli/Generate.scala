package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.runtime.FastUtils.*

import java.io.OutputStream

final case class GenerateOptions(
    @HelpMessage(
      "Destination file or directory. If not specified, output will be written to stdout.",
    )
    to: Option[String] = None,
)
object GenerateOptions:
  given Parser[GenerateOptions] = Parser.derive
  given Help[GenerateOptions] = Help.derive

trait HasGenerateOptions:
  @Recurse
  val common: GenerateOptions

/** A `generate <name>` command. Concrete commands extend one of the two variants below depending on
  * how they produce output.
  */
sealed abstract class Generate[T <: HasGenerateOptions: {Parser, Help}] extends BaseCommand[T] {
  protected def generatorName: String

  override final def group = "generate"
  final override def names: List[List[String]] = List(
    List("generate", generatorName),
  )

  final override def run(options: T, remainingArgs: RemainingArgs): Unit =
    val inputs = remainingArgs.remaining
    if inputs.sizeIs > 1 then
      err(
        s"`generate $generatorName` takes a single input file, but ${inputs.size} were given: " +
          s"${inputs.mkString(", ")}.\n" +
          "Generate one schema at a time, or point at a schema that `imports` the others.",
      )
    val sv = loadSchema(inputs.headOption)
    val to = options.common.to
    this match {
      case g: ManyFilesGenerate[T @unchecked] =>
        writeManyFiles(to, g.generate(options)(using sv))
      case g: StreamGenerate[T @unchecked] =>
        writeToFileOrStdout(to, out => g.generate(options, out)(using sv))
      case g: SplitGenerate[T @unchecked] =>
        if g.writesDirectory(to) then writeManyFiles(to, g.generateFiles(options)(using sv))
        else writeToFileOrStdout(to, out => g.generateSingle(options, out)(using sv))
    }

  private def writeToFileOrStdout(file: Option[String], write: OutputStream => Unit): Unit =
    file.foldFast {
      // `out` is the command's stdout (redirected in tests). Flush but never close it.
      write(outStream)
      outStream.flush()
    } { value =>
      val stream = os.write.over.outputStream(os.Path(value, os.pwd))
      try write(stream)
      finally stream.close()
    }

  private def writeManyFiles(to: Option[String], files: Iterable[(String, String)]): Unit =
    if files.isEmpty then err("No files generated.")
    to.foldFast {
      files.foreach((k, v) => {
        printLine(s"//\n// FILE $k\n//")
        printLine(v)
      })
    } { dir =>
      val path = os.Path(dir, os.pwd)
      os.makeDir.all(path)
      files.foreach((k, v) => os.write.over(path / os.SubPath(k), v, createFolders = true))
    }
}

/** A generate command producing several named files, written into a destination directory.
  *
  * Most generators produce one document and use [[StreamGenerate]]. If a generator can do either it
  * should use [[SplitGenerate]].
  */
abstract class ManyFilesGenerate[T <: HasGenerateOptions: {Parser, Help}] extends Generate[T] {

  /** Returns pairs of (filename, content). */
  protected[cli] def generate(options: T)(using sv: SchemaView): Iterable[(String, String)]
}

/** A generate command that streams its single output straight to an [[OutputStream]], avoiding
  * building the whole document in memory.
  */
abstract class StreamGenerate[T <: HasGenerateOptions: {Parser, Help}] extends Generate[T] {

  /** Write the output to [[outStream]]. Must not close [[outStream]]. */
  protected[cli] def generate(options: T, out: OutputStream)(using sv: SchemaView): Unit
}

/** A generate command producing one self-contained document, or - when `--to` names a directory -
  * the same content spread over several files inside it.
  */
abstract class SplitGenerate[T <: HasGenerateOptions: {Parser, Help}] extends Generate[T] {

  /** The file extension in `--to` that implies that the user wants a single file on the output.
    * Anything else is assumed to be a directory.
    */
  protected def singleFileExtension: String

  /** Write the whole thing as one document. Must not close [[out]]. */
  protected[cli] def generateSingle(options: T, out: OutputStream)(using sv: SchemaView): Unit

  /** The same content as several files, keyed by path relative to the destination directory.
    */
  protected[cli] def generateFiles(options: T)(using sv: SchemaView): Iterable[(String, String)]

  /** Whether `--to` corresponds to a directory.
    */
  private[cli] final def writesDirectory(to: Option[String]): Boolean =
    to.exists(!_.toLowerCase.endsWith(singleFileExtension))
}
