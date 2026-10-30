package eu.neverblink.linkml.nativelib

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.options.*
import eu.neverblink.linkml.generator.ossie.OssieImporter
import eu.neverblink.linkml.generator.owl.OwlImporter
import eu.neverblink.linkml.generator.owl.config.{OwlImportConfig, OwlImportConfigs}
import eu.neverblink.linkml.schemaview.MapImporter
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.{Node, parseYaml}

import scala.util.control.NonFatal

/** Something the caller got wrong: an unknown handle, an option that does not exist, a value out of
  * range.
  */
private final case class BadRequest(reason: String) extends RuntimeException(reason)

/** Reads the options JSON of the C API into the generators' own option types.
  *
  * The options of the generators follow `model/generator-options.yaml`, so they are read with the
  * same codec as every other LinkML model. A null or empty string means "all defaults".
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

  def translation(json: String): TranslationGeneratorOptions =
    model(json, TranslationGeneratorOptions())(using LinkmlYamlCodec.derived)

  // The options below are not part of the model, and are read with jsoniter.

  /** A generated enum as its name in the schema, such as `"yaml"`. */
  private def enumCodec[E](codec: LinkmlYamlCodec[E]): JsonValueCodec[E] =
    new JsonValueCodec[E] {
      override def decodeValue(in: JsonReader, default: E): E = {
        val name = in.readString(null)
        try codec.decode(Node.ScalarNode(name))
        catch { case ex if NonFatal(ex) => in.decodeError(ex.getMessage) }
      }

      override def encodeValue(x: E, out: JsonWriter): Unit =
        codec.encode(x) match {
          case Node.ScalarNode(value, _) => out.writeVal(value)
          case other => out.encodeError(s"not a scalar: $other")
        }

      override def nullValue: E = null.asInstanceOf[E]
    }

  private given JsonValueCodec[JsonOutputFormat] = enumCodec(LinkmlYamlCodec.derived)

  private given JsonValueCodec[RdfFormat] = enumCodec(LinkmlYamlCodec.derived)

  // Unknown fields are rejected rather than skipped, and the names are in snake case like the
  // generator options. The macro takes the config only as a literal.
  private given fromOssieOptions: JsonValueCodec[OssieImporter.Options] = JsonCodecMaker.make(
    CodecMakerConfig
      .withSkipUnexpectedFields(false)
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case),
  )

  private given fromOwlOptions: JsonValueCodec[FromOwlOptions] = JsonCodecMaker.make(
    CodecMakerConfig
      .withSkipUnexpectedFields(false)
      .withFieldNameMapper(JsonCodecMaker.enforce_snake_case),
  )

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

  def fromOssie(json: String): OssieImporter.Options = apply(json, OssieImporter.Options())

  /** The importer's options, and whether to list what could not be imported. The mapping config is
    * the YAML text of a config file, not a path to one.
    */
  def fromOwl(json: String): (OwlImporter.Options, Boolean) = {
    val options = apply(json, FromOwlOptions())
    val config =
      try options.config.fold(OwlImportConfig())(OwlImportConfigs.parse)
      catch { case ex if NonFatal(ex) => throw BadRequest(s"malformed config: ${ex.getMessage}") }
    val importerOptions = OwlImporter.Options(
      config = options.schemaId.fold(config)(id => config.copy(schemaId = Some(id))),
      outputFormat = options.outputFormat,
      inputFormat = options.inputFormat,
      fullUris = options.fullUris,
    )
    val withImports = options.imports.fold(importerOptions)(m =>
      importerOptions.copy(schemas = MapImporter(m.toSeq*)),
    )
    (withImports, options.listNotImported)
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

/** @param config
  *   How to map the ontology: the YAML text of the config itself, not a path to a file (see
  *   https://linkml.ohi.dev/docs/owl/).
  * @param schemaId
  *   The `id` of the schema, instead of the ontology IRI.
  * @param fullUris
  *   Write URIs in full, instead of compacting them to CURIEs with the schema's prefixes.
  */
private final case class FromOwlOptions(
    config: Option[String] = None,
    schemaId: Option[String] = None,
    outputFormat: JsonOutputFormat = JsonOutputFormat.Yaml,
    listNotImported: Boolean = false,
    imports: Option[Map[String, String]] = None,
    inputFormat: RdfFormat = RdfFormat.Ttl,
    fullUris: Boolean = false,
)
