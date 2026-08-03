package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.{Codec, SchemaDefinition}
import org.virtuslab.yaml.parseYaml

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
    *   The raw, unprocessed SchemaDefinition read from the given path.
    */
  def readSchema(path: String): SchemaDefinition

  /** Decode a SchemaDefinition directly from a string.
    *
    * @param yaml
    *   Schema definition as a serialized YAML
    * @param uri
    *   Optional parameter to use for an error message hint
    * @return
    *   The decoded SchemaDefinition
    * @throws RuntimeException
    *   If the schema couldn't be parsed or decoded
    */
  def parseSchema(yaml: String, uri: String = ""): SchemaDefinition = parseYaml(yaml) match {
    case Right(node) => Codec.codec.decode(node)
    case Left(err) =>
      val locHint = if uri.isEmpty then uri else s" '$uri'"
      sys.error(s"Cannot decode schema$locHint: $err")
  }
}

/** A simple Importer that reads the schema text as a string and then parses it into a
  * SchemaDefinition using the standard SchemaView loading mechanism. This is suitable for most use
  * cases.
  */
trait StringImporter extends Importer {
  final override def readSchema(path: String): SchemaDefinition = parseSchema(read(path))

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
