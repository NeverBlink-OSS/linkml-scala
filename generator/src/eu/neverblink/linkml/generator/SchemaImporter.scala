package eu.neverblink.linkml.generator

import eu.neverblink.linkml.generator.util.{JsonOutputFormat, JsonUtil}
import eu.neverblink.linkml.metamodel.{Codec, SchemaDefinitionImpl}

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Reads a document in some other format and produces a LinkML schema - more-or-less the opposite
  * of a [[DocumentGenerator]].
  *
  * The result is a [[SchemaDefinitionImpl]].
  *
  * @tparam O
  *   the importer's own `Options` type
  */
trait SchemaImporter[O <: SchemaImporter.Options] {

  /** Options to use when the caller does not pass any. */
  protected def defaultOptions: O

  /** Read a document from `in` and build the LinkML schema it describes.
    *
    * Does not close the input stream, because the caller may have more to read from it.
    */
  def importSchema(in: InputStream, options: O = defaultOptions): SchemaDefinitionImpl

  /** Read a document that is already in memory. */
  final def importSchemaFromString(
      input: String,
      options: O = defaultOptions,
  ): SchemaDefinitionImpl =
    importSchema(ByteArrayInputStream(input.getBytes(UTF_8)), options)

  /** The schema as a LinkML document. Prefer [[writeTo]] where an output stream exists. */
  final def serialize(in: InputStream, options: O = defaultOptions): String =
    JsonUtil.write(Codec.codec.encode(importSchema(in, options)), options.outputFormat)

  /** Write the schema to `out`. Flushes, but closes neither stream. */
  final def writeTo(in: InputStream, out: OutputStream, options: O = defaultOptions): Unit =
    JsonUtil.write(Codec.codec.encode(importSchema(in, options)), options.outputFormat, out)

  protected final def readUtf8(in: InputStream): String = {
    val buffer = ByteArrayOutputStream()
    val chunk = new Array[Byte](8 * 1024)
    var n = in.read(chunk)
    while n >= 0 do {
      buffer.write(chunk, 0, n)
      n = in.read(chunk)
    }
    String(buffer.toByteArray, UTF_8)
  }
}

object SchemaImporter {
  trait Options {
    def outputFormat: JsonOutputFormat
  }
}
