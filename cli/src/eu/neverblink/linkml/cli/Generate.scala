package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.schemaview.SchemaView

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
    val sv = loadSchema(remainingArgs.remaining.headOption)
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
    file match {
      case Some(value) =>
        val stream = os.write.outputStream(os.Path(value, os.pwd))
        try write(stream)
        finally stream.close()
      case None =>
        // `out` is the command's stdout (redirected in tests). Flush but never close it.
        write(out)
        out.flush()
    }

  private def writeToFileOrStdout(file: Option[String], content: String): Unit =
    file match {
      case Some(value) => os.write(os.Path(value, os.pwd), content)
      case None => println(content)
    }

  private def writeManyFiles(to: Option[String], files: Iterable[(String, String)]): Unit =
    to match {
      case Some(dir) =>
        val path = os.Path(dir, os.pwd)
        os.makeDir.all(path)
        files.foreach((k, v) => os.write.over(path / k, v))
      case None =>
        files.foreach((k, v) => {
          println(s"//\n// FILE $k\n//")
          println(v)
        })
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

  /** Write the output to [[out]]. Must not close [[out]]. */
  protected[cli] def generate(options: T, out: OutputStream)(using sv: SchemaView): Unit
}
