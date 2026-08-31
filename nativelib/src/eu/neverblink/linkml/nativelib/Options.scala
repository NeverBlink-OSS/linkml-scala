package eu.neverblink.linkml.nativelib

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.rdf.RdfFormat
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator
import eu.neverblink.linkml.generator.util.PruningMode

import scala.util.control.NonFatal

/** Something the caller got wrong: an unknown handle, an option that does not exist, a value out of
  * range.
  */
private final case class BadRequest(reason: String) extends RuntimeException(reason)

/** Reads the options JSON of the C API into the generators' own option types.
  *
  * A null or empty string means "all defaults".
  */
private object Options {

  private given pruningModeCodec: JsonValueCodec[PruningMode] = new JsonValueCodec[PruningMode] {
    override def decodeValue(in: JsonReader, default: PruningMode): PruningMode =
      if in.isNextToken('{') then {
        val mode =
          if in.isCharBufEqualsTo(in.readKeyAsCharBuf(), "treeRoot") then
            PruningMode.treeRoot(Some(in.readString(null)))
          else in.decodeError("the only pruning mode taking a value is 'treeRoot'")
        if !in.isNextToken('}') then in.decodeError("expected a single-field object")
        mode
      } else {
        in.rollbackToken()
        named(in.readString(null), in)
      }

    override def encodeValue(x: PruningMode, out: JsonWriter): Unit = x match {
      case PruningMode.treeRoot(Some(root)) =>
        out.writeObjectStart()
        out.writeKey("treeRoot")
        out.writeVal(root)
        out.writeObjectEnd()
      case PruningMode.treeRoot(None) => out.writeVal("treeRoot")
      case PruningMode.schemaRoot => out.writeVal("schema")
      case PruningMode.skip => out.writeVal("skip")
    }

    override def nullValue: PruningMode = null

    private def named(value: String, in: JsonReader): PruningMode = value match {
      case "treeRoot" | "tree_root" | "tree-root" => PruningMode.treeRoot(None)
      case "schema" => PruningMode.schemaRoot
      case "skip" => PruningMode.skip
      case other =>
        in.decodeError(s"unknown pruning mode '$other', expected treeRoot, schema or skip")
    }
  }

  private given outputFormatCodec: JsonValueCodec[LinkMlGenerator.OutputFormat] =
    new JsonValueCodec[LinkMlGenerator.OutputFormat] {
      override def decodeValue(
          in: JsonReader,
          default: LinkMlGenerator.OutputFormat,
      ): LinkMlGenerator.OutputFormat = in.readString(null) match {
        case "yaml" | "yml" => LinkMlGenerator.OutputFormat.yaml
        case "json" => LinkMlGenerator.OutputFormat.json
        case other => in.decodeError(s"unknown output format '$other', expected yaml or json")
      }

      override def encodeValue(x: LinkMlGenerator.OutputFormat, out: JsonWriter): Unit =
        out.writeVal(x.toString)

      override def nullValue: LinkMlGenerator.OutputFormat = null
    }

  private given rdfFormatCodec: JsonValueCodec[RdfFormat] = new JsonValueCodec[RdfFormat] {
    override def decodeValue(in: JsonReader, default: RdfFormat): RdfFormat =
      in.readString(null) match {
        case "nt" | "ntriples" => RdfFormat.nt
        case "ttl" | "turtle" => RdfFormat.ttl
        case other => in.decodeError(s"unknown RDF format '$other', expected nt or ttl")
      }

    override def encodeValue(x: RdfFormat, out: JsonWriter): Unit = out.writeVal(x.toString)

    override def nullValue: RdfFormat = null
  }

  // Unknown fields are rejected rather than skipped.
  private given jsonSchemaOptions: JsonValueCodec[JsonSchemaGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given shaclOptions: JsonValueCodec[ShaclGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given rdfsOptions: JsonValueCodec[RdfsGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given linkmlOptions: JsonValueCodec[LinkMlGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given tableSchemaOptions: JsonValueCodec[TableSchemaGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given graphQlOptions: JsonValueCodec[GraphQlGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given erDiagramOptions: JsonValueCodec[ErDiagramGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given scalaOptions: JsonValueCodec[ScalaGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given loadOptions: JsonValueCodec[LoadOptions] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private val readable = ReaderConfig.withAppendHexDumpToParseException(false)

  /** Parse an options JSON, or return [[defaults]] when there is nothing to parse. */
  def apply[T](json: String, defaults: T)(using JsonValueCodec[T]): T =
    if (json eq null) || json.isEmpty then defaults
    else
      try readFromString(json, readable)
      catch {
        case ex if NonFatal(ex) => throw BadRequest(s"malformed options: ${ex.getMessage}")
      }

  def jsonSchema(json: String): JsonSchemaGenerator.Options =
    apply(json, JsonSchemaGenerator.Options())

  def shacl(json: String): ShaclGenerator.Options = apply(json, ShaclGenerator.Options())

  def rdfs(json: String): RdfsGenerator.Options = apply(json, RdfsGenerator.Options())

  def linkml(json: String): LinkMlGenerator.Options = apply(json, LinkMlGenerator.Options())

  def tableSchema(json: String): TableSchemaGenerator.Options =
    apply(json, TableSchemaGenerator.Options())

  def graphQl(json: String): GraphQlGenerator.Options = apply(json, GraphQlGenerator.Options())

  def erDiagram(json: String): ErDiagramGenerator.Options =
    apply(json, ErDiagramGenerator.Options())

  def scala(json: String): ScalaGenerator.Options = apply(json, ScalaGenerator.Options())

  def load(json: String): LoadOptions = apply(json, LoadOptions())
}

/** @param inferMessages
  *   Whether to fill in each issue's human-readable `message` and `details`.
  */
private final case class LoadOptions(
    inferMessages: Boolean = true,
)
