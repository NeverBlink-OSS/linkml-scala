package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.schemaview.SchemaView

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

abstract class Generate[T <: HasGenerateOptions: {Parser, Help}] extends BaseCommand[T] {
  protected def generatorName: String

  /** Returns an iterable of pairs of (filename, content) to be generated.
    *
    * Leave the filename empty if filenames are not relevant for the generator (e.g. if it generates
    * a single file or writes to stdout).
    */
  protected def generate(options: T)(using sv: SchemaView): Iterable[(String, String)]

  override final def group = "generate"
  final override def names: List[List[String]] = List(
    List("generate", generatorName),
  )

  final override def run(options: T, remainingArgs: RemainingArgs): Unit =
    val sv = loadSchema(remainingArgs.remaining.headOption)
    val result = generate(options)(using sv)
    if result.isEmpty then err("No files generated.")
    else if result.size == 1 && result.head._1.isEmpty then
      // If there's only one file and the filename is empty, write to stdout
      printToFileOrStdout(options.common.to, result.head._2)
    else printManyFiles(options.common.to, result)

  private def printToFileOrStdout(file: Option[String], content: String): Unit = {
    file match {
      case Some(value) => os.write(os.Path(value, os.pwd), content)
      case None => println(content)
    }
  }

  private def printManyFiles(to: Option[String], files: Iterable[(String, String)]): Unit = {
    to match {
      case Some(dir) =>
        val path = os.Path(dir, os.pwd)
        os.makeDir.all(path)
        files.foreach((k, v) => {
          os.write.over(path / k, v)
        })
      case None =>
        files.foreach((k, v) => {
          println(s"//\n// FILE $k\n//")
          println(v)
        })
    }
  }
}
