package eu.neverblink.linkml.nativelib

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.typescript.TypeScriptGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.ossie.{OssieGenerator, OssieImporter}
import eu.neverblink.linkml.generator.owl.config.{OwlImportConfigImpl, OwlImportConfigs}
import eu.neverblink.linkml.generator.owl.{OwlGenerator, OwlImporter}
import eu.neverblink.linkml.generator.RdfGeneratorBase.RdfFormat
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, PruningMode}
import eu.neverblink.linkml.schemaview.MapImporter

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
        val value = in.readString(null)
        PruningMode.parse(value).getOrElse(in.decodeError(PruningMode.unknownMode(value)))
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
  }

  private given outputFormatCodec: JsonValueCodec[JsonOutputFormat] =
    new JsonValueCodec[JsonOutputFormat] {
      override def decodeValue(in: JsonReader, default: JsonOutputFormat): JsonOutputFormat = {
        val value = in.readString(null)
        JsonOutputFormat.parse(value).getOrElse(
          in.decodeError(JsonOutputFormat.unknownFormat(value)),
        )
      }

      override def encodeValue(x: JsonOutputFormat, out: JsonWriter): Unit =
        out.writeVal(x.toString)

      override def nullValue: JsonOutputFormat = null
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

  /** A Scala enum as its value's name, such as `"rdfs"`. */
  private def enumCodec[E](values: Array[E], what: String): JsonValueCodec[E] =
    new JsonValueCodec[E] {
      override def decodeValue(in: JsonReader, default: E): E = {
        val name = in.readString(null)
        values.find(_.toString == name).getOrElse(
          in.decodeError(s"unknown $what '$name', expected ${values.mkString(" or ")}"),
        )
      }

      override def encodeValue(x: E, out: JsonWriter): Unit = out.writeVal(x.toString)

      override def nullValue: E = null.asInstanceOf[E]
    }

  private given metadataProfileCodec: JsonValueCodec[OwlGenerator.MetadataProfile] =
    enumCodec(OwlGenerator.MetadataProfile.values, "metadata profile")

  private given permissibleValueKindCodec: JsonValueCodec[OwlGenerator.PermissibleValueKind] =
    enumCodec(OwlGenerator.PermissibleValueKind.values, "permissible value kind")

  // Unknown fields are rejected rather than skipped.
  private given fromOssieOptions: JsonValueCodec[OssieImporter.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given jsonSchemaOptions: JsonValueCodec[JsonSchemaGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given shaclOptions: JsonValueCodec[ShaclGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given rdfsOptions: JsonValueCodec[RdfsGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given linkmlOptions: JsonValueCodec[LinkMlGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given frictionlessOptions: JsonValueCodec[FrictionlessGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given graphQlOptions: JsonValueCodec[GraphQlGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given typeScriptOptions: JsonValueCodec[TypeScriptGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given erDiagramOptions: JsonValueCodec[ErDiagramGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given ossieOptions: JsonValueCodec[OssieGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given owlOptions: JsonValueCodec[OwlGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given fromOwlOptions: JsonValueCodec[FromOwlOptions] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given scalaOptions: JsonValueCodec[ScalaGenerator.Options] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))

  private given translationOptions: JsonValueCodec[TranslationGenerator.Options] =
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

  def frictionless(json: String): FrictionlessGenerator.Options =
    apply(json, FrictionlessGenerator.Options())

  def graphQl(json: String): GraphQlGenerator.Options = apply(json, GraphQlGenerator.Options())

  def typeScript(json: String): TypeScriptGenerator.Options =
    apply(json, TypeScriptGenerator.Options())

  def erDiagram(json: String): ErDiagramGenerator.Options =
    apply(json, ErDiagramGenerator.Options())

  def ossie(json: String): OssieGenerator.Options = apply(json, OssieGenerator.Options())

  def fromOssie(json: String): OssieImporter.Options = apply(json, OssieImporter.Options())

  def owl(json: String): OwlGenerator.Options = apply(json, OwlGenerator.Options())

  /** The importer's options, and whether to list what could not be imported. The mapping config is
    * the YAML text of a config file, not a path to one.
    */
  def fromOwl(json: String): (OwlImporter.Options, Boolean) = {
    val options = apply(json, FromOwlOptions())
    val config =
      try options.config.fold(OwlImportConfigImpl())(OwlImportConfigs.parse)
      catch { case ex if NonFatal(ex) => throw BadRequest(s"malformed config: ${ex.getMessage}") }
    val importerOptions = OwlImporter.Options(
      config = options.schemaId.fold(config)(id => config.copy(schemaId = Some(id))),
      outputFormat = options.outputFormat,
      inputFormat = options.inputFormat,
    )
    val withImports = options.imports.fold(importerOptions)(m =>
      importerOptions.copy(schemas = MapImporter(m.toSeq*)),
    )
    (withImports, options.listNotImported)
  }

  def scala(json: String): ScalaGenerator.Options = apply(json, ScalaGenerator.Options())

  def translation(json: String): TranslationGenerator.Options =
    apply(json, TranslationGenerator.Options())

  def load(json: String): LoadOptions = apply(json, LoadOptions())
}

/** @param inferMessages
  *   Whether to fill in each issue's human-readable `message` and `details`.
  */
private final case class LoadOptions(
    inferMessages: Boolean = true,
)

/** @param config
  *   How to map the ontology: the YAML text of the config itself, not a path to a file (see
  *   `docs/owl.md`).
  * @param schemaId
  *   The `id` of the schema, instead of the ontology IRI.
  */
private final case class FromOwlOptions(
    config: Option[String] = None,
    schemaId: Option[String] = None,
    outputFormat: JsonOutputFormat = JsonOutputFormat.yaml,
    listNotImported: Boolean = false,
    imports: Option[Map[String, String]] = None,
    inputFormat: RdfFormat = RdfFormat.ttl,
)
