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
    this match {
      case g: StringGenerate[T @unchecked] =>
        val files = g.generate(options)(using sv)
        if files.isEmpty then err("No files generated.")
        else if files.size == 1 && files.head._1.isEmpty then
          // A single unnamed file goes to the destination file or stdout.
          writeToFileOrStdout(options.common.to, files.head._2)
        else writeManyFiles(options.common.to, files)
      case g: StreamGenerate[T @unchecked] =>
        writeToFileOrStdout(options.common.to, out => g.generate(options, out)(using sv))
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

  private def writeToFileOrStdout(file: Option[String], content: String): Unit =
    file.foldFast(printLine(content)) { value =>
      os.write.over(os.Path(value, os.pwd), content)
    }

  private def writeManyFiles(to: Option[String], files: Iterable[(String, String)]): Unit =
    to.foldFast {
      files.foreach((k, v) => {
        printLine(s"//\n// FILE $k\n//")
        printLine(v)
      })
    } { dir =>
      val path = os.Path(dir, os.pwd)
      os.makeDir.all(path)
      files.foreach((k, v) => os.write.over(path / k, v))
    }
}

/** A generate command producing one or more named string files (or a single unnamed one). */
abstract class StringGenerate[T <: HasGenerateOptions: {Parser, Help}] extends Generate[T] {

  /** Returns pairs of (filename, content). Leave the filename empty if filenames are not relevant
    * (a single file or stdout output).
    */
  protected[cli] def generate(options: T)(using sv: SchemaView): Iterable[(String, String)]
}

/** A generate command that streams its single output straight to an [[OutputStream]], avoiding
  * building the whole document in memory.
  */
abstract class StreamGenerate[T <: HasGenerateOptions: {Parser, Help}] extends Generate[T] {

  /** Write the output to [[outStream]]. Must not close [[outStream]]. */
  protected[cli] def generate(options: T, out: OutputStream)(using sv: SchemaView): Unit
}
