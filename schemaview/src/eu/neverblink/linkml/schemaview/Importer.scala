package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{Codec, SchemaDefinition}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.validation.*
import eu.neverblink.linkml.yaml.DecodeError
import org.virtuslab.yaml.{ConstructError, ParseError, Range, ScannerError, YamlError, parseYaml}

import scala.util.control.NonFatal

/** Failure of reading or parsing a schema, as a structured issue from the validation model. */
type ImportFailure = SchemaParseError | SchemaImportError

/** Interface for schema importers, which can read a schema from a given path and produce a raw,
  * unprocessed SchemaDefinition.
  *
  * Importers must support both reading a schema from a path and parsing a schema from a string,
  * because the loading process starts from a schema text and may also include built-imports that
  * are provided as strings.
  *
  * Unless you need to preprocess the schema text in some way, or cache the parsed
  * SchemaDefinitions, you can usually just implement the [[StringImporter]] trait instead, which
  * only requires you to implement a method to read the schema text as a string.
  */
trait Importer {

  /** Read a schema from the given path and return it as a raw, unprocessed SchemaDefinition.
    * @param path
    *   The path to the schema to read.
    * @return
    *   The raw, unprocessed SchemaDefinition, or the issue describing why it could not be supplied.
    */
  def readSchema(path: String): Either[ImportFailure, SchemaDefinition]

  /** Decode a SchemaDefinition directly from a string.
    *
    * @param yaml
    *   Schema definition as a serialized YAML
    * @param uri
    *   Optional parameter to use for an error message hint
    * @return
    *   The decoded SchemaDefinition, or a [[SchemaParseError]] describing the YAML or decoding
    *   failure
    */
  def parseSchema(yaml: String, uri: String = ""): Either[SchemaParseError, SchemaDefinition] =
    new Left(parseYaml(yaml) match {
      case Right(node) =>
        // The metamodel decoder signals structural problems by throwing DecodeError, which carries
        // the offending node's position.
        try return new Right(Codec.codec.decode(node))
        catch {
          case ex: DecodeError =>
            Importer.parseError(ex.getMessage, uri, ex.position.map(Importer.codeRegion))
          case ex if NonFatal(ex) =>
            Importer.parseError(ex.getMessage, uri, None)
        }
      case Left(err) => Importer.parseError(err.msg, uri, Importer.codeRegionOf(err))
    })
}

object Importer {

  /** Build a [[SchemaParseError]], pinning it to the position the parser or decoder reported. */
  private[schemaview] def parseError(
      parserMessage: String,
      uri: String,
      codeRegion: Option[CodeRegionImpl],
  ): SchemaParseError =
    new SchemaParseErrorImpl(
      location = new IssueLocationImpl(codeRegion = codeRegion),
      parserMessage = parserMessage,
      sourceUri = uri,
    )

  /** Extract the position a [[YamlError]] reported, if it carries one.
    */
  private def codeRegionOf(error: YamlError): Option[CodeRegionImpl] = error match {
    case e: ParseError.ExpectedTokenKind => new Some(codeRegion(e.got.range))
    case e: ScannerError.Obtained => new Some(codeRegion(e.got.range))
    case e: ScannerError.AtRange => new Some(codeRegion(e.range))
    case e: ConstructError => e.node.flatMapFast(_.pos).mapFast(codeRegion)
    // ComposerError and NoRegisteredTagDirective carry no position.
    case _ => None
  }

  /** Convert a YAML [[Range]], whose lines and columns are 0-based, into a 1-based code region. */
  private[schemaview] def codeRegion(range: Range): CodeRegionImpl =
    new CodeRegionImpl(
      startLine = range.start.line + 1,
      startColumn = range.start.column + 1,
      endLine = range.end.mapFast(_.line + 1),
      endColumn = range.end.mapFast(_.column + 1),
    )

  /** Build a [[SchemaImportError]] for a schema text that could not be obtained at all. */
  private[schemaview] def importError(uri: String, reason: String): SchemaImportError =
    new SchemaImportErrorImpl(
      location = IssueLocationImpl(),
      importUri = uri,
      reason = reason,
    )

  /** Read a schema text that may throw, turning any failure into a [[SchemaImportError]]. */
  private[schemaview] def readText(
      uri: String,
  )(read: => String): Either[SchemaImportError, String] =
    try new Right(read)
    catch {
      case ex if NonFatal(ex) => new Left(importError(uri, ex.getMessage))
    }
}

/** A simple Importer that reads the schema text as a string and then parses it into a
  * SchemaDefinition using the standard SchemaView loading mechanism. This is suitable for most use
  * cases.
  */
trait StringImporter extends Importer {
  final override def readSchema(path: String): Either[ImportFailure, SchemaDefinition] =
    Importer.readText(path)(read(path)) match {
      case Right(text) => parseSchema(text, path)
      case err => err.asInstanceOf[Either[ImportFailure, SchemaDefinition]]
    }

  /** Read the schema text from the given path and return it as a string.
    * @param path
    *   The path to the schema to read.
    */
  def read(path: String): String
}

/** An Importer implementation that reads the schema text from a file path. This is the default
  * importer used by SchemaView.
  */
object FileSystemImporter extends StringImporter {
  def read(path: String): String = PlatformSpecificUtils.readFile(path)
}

/** A basic importer implementation which delegates the read operation to a mapping
  */
final class MapImporter(content: (String, String)*) extends StringImporter {
  val mapping: Map[String, String] = content.toMap
  def read(path: String): String = mapping(path)
}
