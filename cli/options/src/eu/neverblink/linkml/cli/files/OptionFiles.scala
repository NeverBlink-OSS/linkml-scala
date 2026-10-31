package eu.neverblink.linkml.cli.files

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.parseYaml

import java.nio.file.{Files, NoSuchFileException, Path}
import scala.util.control.NonFatal

/** Reads the options whose value is an object, such as `--config`, from files. Not generated, so it
  * lives outside the package that `generator.optionsgen` writes.
  */
object OptionFiles {

  /** Read a value from a YAML or JSON file. Throws an [[IllegalArgumentException]] that says what
    * is wrong with the file.
    */
  def read[T](path: String, codec: LinkmlYamlCodec[T]): T = {
    def invalid(reason: String) = IllegalArgumentException(s"Cannot read $path: $reason")
    val text =
      try Files.readString(Path.of(path))
      catch {
        case _: NoSuchFileException => throw IllegalArgumentException(s"No such file: $path")
        case NonFatal(e) => throw invalid(e.getMessage)
      }
    parseYaml(text) match {
      case Right(node) =>
        try codec.decode(node)
        catch { case NonFatal(e) => throw invalid(e.getMessage) }
      case Left(error) => throw invalid(error.msg)
    }
  }
}
