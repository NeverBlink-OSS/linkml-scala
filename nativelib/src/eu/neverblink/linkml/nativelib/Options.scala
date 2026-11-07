package eu.neverblink.linkml.nativelib

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.options.*
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.parseYaml

import scala.util.control.NonFatal

/** Something the caller got wrong: an unknown handle, an option that does not exist, a value out of
  * range.
  */
private final case class BadRequest(reason: String) extends RuntimeException(reason)

/** Reads the options JSON of the C API into the generators' own option types.
  *
  * The options of the generators follow `model/converter/all_generators.yaml`, so they are read
  * with the same codec as every other LinkML model. A null or empty string means "all defaults".
  */
private object Options {

  /** Parse an options JSON, or return [[defaults]] when there is nothing to parse. */
  private def model[T](json: String, defaults: T)(using codec: LinkmlYamlCodec[T]): T =
    if (json eq null) || json.isEmpty then defaults
    else
      try
        parseYaml(json) match {
          case Right(node) => codec.decode(node)
          case Left(error) => throw BadRequest(s"malformed options: ${error.msg}")
        }
      catch {
        case e: BadRequest => throw e
        case ex if NonFatal(ex) => throw BadRequest(s"malformed options: ${ex.getMessage}")
      }

  def jsonSchema(json: String): JsonSchemaGeneratorOptions =
    model(json, JsonSchemaGeneratorOptions())(using LinkmlYamlCodec.derived)

  def shacl(json: String): ShaclGeneratorOptions =
    model(json, ShaclGeneratorOptions())(using LinkmlYamlCodec.derived)

  def rdfs(json: String): RdfsGeneratorOptions =
    model(json, RdfsGeneratorOptions())(using LinkmlYamlCodec.derived)

  def linkml(json: String): LinkMlGeneratorOptions =
    model(json, LinkMlGeneratorOptions())(using LinkmlYamlCodec.derived)

  def frictionless(json: String): FrictionlessGeneratorOptions =
    model(json, FrictionlessGeneratorOptions())(using LinkmlYamlCodec.derived)

  def graphQl(json: String): GraphQlGeneratorOptions =
    model(json, GraphQlGeneratorOptions())(using LinkmlYamlCodec.derived)

  def typeScript(json: String): TypeScriptGeneratorOptions =
    model(json, TypeScriptGeneratorOptions())(using LinkmlYamlCodec.derived)

  def pydantic(json: String): PydanticGeneratorOptions =
    model(json, PydanticGeneratorOptions())(using LinkmlYamlCodec.derived)

  def erDiagram(json: String): ErDiagramGeneratorOptions =
    model(json, ErDiagramGeneratorOptions())(using LinkmlYamlCodec.derived)

  def docs(json: String): DocsGeneratorOptions =
    model(json, DocsGeneratorOptions())(using LinkmlYamlCodec.derived)

  def ossie(json: String): OssieGeneratorOptions =
    model(json, OssieGeneratorOptions())(using LinkmlYamlCodec.derived)

  def stats(json: String): StatsGeneratorOptions =
    model(json, StatsGeneratorOptions())(using LinkmlYamlCodec.derived)

  def owl(json: String): OwlGeneratorOptions =
    model(json, OwlGeneratorOptions())(using LinkmlYamlCodec.derived)

  def scala(json: String): ScalaGeneratorOptions =
    model(json, ScalaGeneratorOptions())(using LinkmlYamlCodec.derived)

  def java(json: String): JavaGeneratorOptions =
    model(json, JavaGeneratorOptions())(using LinkmlYamlCodec.derived)

  def translation(json: String): TranslationGeneratorOptions =
    model(json, TranslationGeneratorOptions())(using LinkmlYamlCodec.derived)

  def fromOssie(json: String): OssieImporterOptions =
    model(json, OssieImporterOptions())(using LinkmlYamlCodec.derived)

  def fromOwl(json: String): OwlImporterOptions =
    model(json, OwlImporterOptions())(using LinkmlYamlCodec.derived)

  // The options below are not part of the model, and are read with jsoniter. Unknown fields are
  // rejected rather than skipped, and the names are in snake case like the generator options. The
  // macro takes the config only as a literal.
  private given loadOptions: JsonValueCodec[LoadOptions] = JsonCodecMaker.make(
    CodecMakerConfig
      .withSkipUnexpectedFields(false)
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case),
  )

  private val readable = ReaderConfig.withAppendHexDumpToParseException(false)

  /** Parse an options JSON, or return [[defaults]] when there is nothing to parse. */
  private def apply[T](json: String, defaults: T)(using JsonValueCodec[T]): T =
    if (json eq null) || json.isEmpty then defaults
    else
      try readFromString(json, readable)
      catch {
        case ex if NonFatal(ex) => throw BadRequest(s"malformed options: ${ex.getMessage}")
      }

  def load(json: String): LoadOptions = apply(json, LoadOptions())
}

/** @param inferMessages
  *   Whether to fill in each issue's human-readable `message` and `details`.
  * @param importMap
  *   Where to load imports from: each key is an import and each value is the location to load it
  *   from (see `ImportMap` in `schemaview`).
  */
private final case class LoadOptions(
    inferMessages: Boolean = true,
    importMap: Map[String, String] = Map.empty,
)
