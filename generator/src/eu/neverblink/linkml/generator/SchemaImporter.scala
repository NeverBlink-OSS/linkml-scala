package eu.neverblink.linkml.generator

import eu.neverblink.linkml.generator.options.{ImportResult, JsonOutputFormat, SchemaOutputOptions}
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.metamodel.SchemaDefinition
import eu.neverblink.linkml.schemaview.SchemaCodec

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, InputStream, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8

/** Reads a document in some other format and produces a LinkML schema - more-or-less the opposite
  * of a [[DocumentGenerator]].
  *
  * The result is a [[SchemaDefinition]].
  *
  * @tparam O
  *   the importer's options, generated from `model/converter/importer`
  */
trait SchemaImporter[O <: SchemaOutputOptions] {

  /** Options to use when the caller does not pass any. */
  protected def defaultOptions: O

  /** Read a document from `in` and build the LinkML schema it describes.
    *
    * Does not close the input stream, because the caller may have more to read from it.
    */
  def importSchema(in: InputStream, options: O = defaultOptions): SchemaDefinition

  /** Read a document that is already in memory. */
  final def importSchemaFromString(
      input: String,
      options: O = defaultOptions,
  ): SchemaDefinition =
    importSchema(ByteArrayInputStream(input.getBytes(UTF_8)), options)

  /** The schema as a LinkML document. Prefer [[writeTo]] where an output stream exists. */
  final def serialize(in: InputStream, options: O = defaultOptions): String =
    JsonUtil.write(
      SchemaCodec.encode(importSchema(in, options), options.fullUris),
      options.format,
    )

  /** The same, for a document that is already in memory. */
  final def serializeFromString(input: String, options: O = defaultOptions): String =
    JsonUtil.write(
      SchemaCodec.encode(importSchemaFromString(input, options), options.fullUris),
      options.format,
    )

  /** The schema as a document, with what could not be imported, as the bindings return them. */
  def importResult(in: InputStream, options: O = defaultOptions): ImportResult =
    ImportResult(serialize(in, options), warnings = Nil)

  /** Write the schema to `out`. Flushes, but closes neither stream. */
  final def writeTo(in: InputStream, out: OutputStream, options: O = defaultOptions): Unit =
    JsonUtil.write(
      SchemaCodec.encode(importSchema(in, options), options.fullUris),
      options.format,
      out,
    )

  /** A schema this importer made, as a document, for callers that look at more than the schema.
    * With `fullUris`, URIs are written in full instead of as CURIEs.
    */
  final def serializeSchema(
      schema: SchemaDefinition,
      format: JsonOutputFormat,
      fullUris: Boolean = false,
  ): String =
    JsonUtil.write(SchemaCodec.encode(schema, fullUris), format)

  /** Write a schema this importer made, for callers that look at more than the schema first. With
    * `fullUris`, URIs are written in full instead of as CURIEs.
    */
  final def writeSchema(
      schema: SchemaDefinition,
      out: OutputStream,
      format: JsonOutputFormat,
      fullUris: Boolean = false,
  ): Unit =
    JsonUtil.write(SchemaCodec.encode(schema, fullUris), format, out)

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
