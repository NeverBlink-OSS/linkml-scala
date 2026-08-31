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

  /** Both path separators, recognized everywhere regardless of the host OS. Import paths are not
    * always file system paths – they are also URLs and keys into a caller-supplied map, where `/`
    * is the separator whatever the host – so import resolution reads both and writes [[separator]].
    */
  private val separators = "/\\"

  /** The separator to fall back on when a path uses none. Windows file APIs accept `/` too, so it
    * is a safe default for file system paths, URLs, and map keys.
    */
  val separator: String = "/"

  private def isSeparator(c: Char): Boolean = separators.indexOf(c.toInt) >= 0

  /** The index of the last path separator in `path`, or -1 if it has none. */
  def lastSeparator(path: String): Int = path.lastIndexOf('/').max(path.lastIndexOf('\\'))

  /** Find the separator used in a path, or return the default [[separator]] if it has none.
    */
  def separatorFor(base: String): String = {
    val idx = lastSeparator(base)
    if (idx >= 0) base.substring(idx, idx + 1) else separator
  }

  /** Normalize a schema URI: drop a trailing separator, and add the `.yaml` extension unless the
    * URI already ends in `.yaml` or `.yml`.
    */
  def normalizeUri(uri: String): String = {
    val trimmed =
      if (uri.nonEmpty && isSeparator(uri.last)) uri.substring(0, uri.length - 1) else uri
    if (trimmed.endsWith(".yaml") || trimmed.endsWith(".yml")) trimmed
    else trimmed.concat(".yaml")
  }

  /** Build the lookup table of a map-backed importer, adding every key under its normalized form as
    * well, so a schema keyed `"core"` is found by the lookup of `"core.yaml"`.
    */
  def normalizedMap(entries: IterableOnce[(String, String)]): Map[String, String] = {
    val exact = entries.iterator.toMap
    exact.foldLeft(exact) { case (acc, (key, body)) =>
      val normalized = normalizeUri(key)
      if (acc.contains(normalized)) acc else acc.updated(normalized, body)
    }
  }

  /** Build a [[SchemaParseError]], pinning it to the position the parser or decoder reported. */
  def parseError(
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
  def codeRegionOf(error: YamlError): Option[CodeRegionImpl] = error match {
    case e: ParseError.ExpectedTokenKind => new Some(codeRegion(e.got.range))
    case e: ScannerError.Obtained => new Some(codeRegion(e.got.range))
    case e: ScannerError.AtRange => new Some(codeRegion(e.range))
    case e: ConstructError => e.node.flatMapFast(_.pos).mapFast(codeRegion)
    // ComposerError and NoRegisteredTagDirective carry no position.
    case _ => None
  }

  /** Convert a YAML [[Range]], whose lines and columns are 0-based, into a 1-based code region. */
  def codeRegion(range: Range): CodeRegionImpl =
    new CodeRegionImpl(
      startLine = range.start.line + 1,
      startColumn = range.start.column + 1,
      endLine = range.end.mapFast(_.line + 1),
      endColumn = range.end.mapFast(_.column + 1),
    )

  /** Build a [[SchemaImportError]] for a schema text that could not be obtained at all. */
  def importError(uri: String, reason: String): SchemaImportError =
    new SchemaImportErrorImpl(
      location = IssueLocationImpl(),
      importUri = uri,
      reason = reason,
    )

  /** Read a schema text that may throw, turning any failure into a [[SchemaImportError]]. */
  def readText(
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
  val mapping: Map[String, String] = Importer.normalizedMap(content)
  def read(path: String): String = mapping(path)
}
