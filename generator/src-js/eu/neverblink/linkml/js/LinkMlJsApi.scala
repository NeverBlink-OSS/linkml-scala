package eu.neverblink.linkml.js

import eu.neverblink.linkml.generator.docs.DocsGenerator
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.pydantic.PydanticGenerator
import eu.neverblink.linkml.generator.typescript.TypeScriptGenerator
import eu.neverblink.linkml.generator.ossie.{OssieGenerator, OssieImporter}
import eu.neverblink.linkml.generator.owl.config.{OwlImportConfig, OwlImportConfigs}
import eu.neverblink.linkml.generator.owl.{OwlGenerator, OwlImporter}
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.stats.StatsGenerator
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.options.*
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.schemaview.{
  ImportMapImporter,
  Importer,
  SchemaIssues,
  SchemaValidator,
  SchemaView,
  StringImporter,
}
import eu.neverblink.linkml.schemaview.buildinfo.CurrentBuild
import eu.neverblink.linkml.rdf.RdfParseException
import eu.neverblink.linkml.runtime.SchemaConversionException
import eu.neverblink.linkml.validation.{Codec, SchemaFatal, SchemaIssue, SchemaValidationReport}
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.parseYaml

import scala.scalajs.js
import scala.scalajs.js.JSConverters.JSRichMap
import scala.scalajs.js.annotation.{JSExportAll, JSExportTopLevel}

/** Handle to a loaded, import-resolved LinkML schema.
  *
  * Wraps a Scala [[SchemaView]] so the schema is parsed once (with all `imports:` resolved) and can
  * then be reused across many generators, instead of re-parsing on every call. Create one with
  * [[LinkMlJsApi.loadFromString]] or [[LinkMlJsApi.loadFromPath]].
  *
  * Its methods are the functions of model/converter/all_generators.yaml, which also documents them.
  * Each takes the generator's options as a plain JS object, with their names in snake case. The
  * options left out take their defaults.
  */
@JSExportAll
final class SchemaViewJs private[js] (private[js] val underlying: SchemaView) {
  import SchemaViewJs.{*, given}
  private given SchemaView = underlying

  def jsonSchema(params: Params = js.undefined): String =
    JsonSchemaGenerator().serialize(decode[JsonSchemaGeneratorOptions](params))

  def shacl(params: Params = js.undefined): String =
    ShaclGenerator().serialize(decode[ShaclGeneratorOptions](params))

  def rdfs(params: Params = js.undefined): String =
    RdfsGenerator().serialize(decode[RdfsGeneratorOptions](params))

  def linkml(params: Params = js.undefined): String =
    LinkMlJsApi.converting(LinkMlGenerator().serialize(decode[LinkMlGeneratorOptions](params)))

  def frictionless(params: Params = js.undefined): js.Dictionary[String] =
    FrictionlessGenerator().generateFiles(decode[FrictionlessGeneratorOptions](params))
      .toMap.toJSDictionary

  def graphql(params: Params = js.undefined): String =
    GraphQlGenerator().serialize(decode[GraphQlGeneratorOptions](params))

  def typescript(params: Params = js.undefined): String =
    TypeScriptGenerator().serialize(decode[TypeScriptGeneratorOptions](params))

  def pydantic(params: Params = js.undefined): String =
    PydanticGenerator().serialize(decode[PydanticGeneratorOptions](params))

  def erDiagram(params: Params = js.undefined): String =
    ErDiagramGenerator().serialize(decode[ErDiagramGeneratorOptions](params))

  def docs(params: Params = js.undefined): js.Dictionary[String] =
    DocsGenerator().generateFiles(decode[DocsGeneratorOptions](params))
      .map(_.toTuple).toMap.toJSDictionary

  def ossie(params: Params = js.undefined): String =
    LinkMlJsApi.converting(OssieGenerator().serialize(decode[OssieGeneratorOptions](params)))

  def owl(params: Params = js.undefined): String =
    LinkMlJsApi.converting(OwlGenerator().serialize(decode[OwlGeneratorOptions](params)))

  def scala(params: Params = js.undefined): js.Dictionary[String] =
    ScalaGenerator().generate(decode[ScalaGeneratorOptions](params)).toMap.toJSDictionary

  def stats(params: Params = js.undefined): js.Any = {
    val stats = StatsGenerator().generate(decode[StatsGeneratorOptions](params))
    js.JSON.parse(JsonUtil.yamlToJson(StatsGenerator.codec.encode(stats)))
  }

  def translation(params: Params = js.undefined): String =
    TranslationGenerator().serialize(decode[TranslationGeneratorOptions](params))
}

private object SchemaViewJs {

  /** The options of a generator, as a plain JS object. */
  type Params = js.UndefOr[js.Object]

  /** The options of a generator, from the plain JS object the caller passed. */
  def decode[T](params: Params)(using codec: LinkmlYamlCodec[T]): T =
    parseYaml(params.fold("{}")(js.JSON.stringify(_))) match {
      case Right(node) =>
        try codec.decode(node)
        catch { case e: Exception => throw RuntimeException(s"Invalid options: ${e.getMessage}") }
      case Left(error) => throw RuntimeException(s"Invalid options: ${error.msg}")
    }

  given LinkmlYamlCodec[JsonSchemaGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[ShaclGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[ScalaGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[RdfsGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[OwlGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[LinkMlGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[FrictionlessGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[GraphQlGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[TypeScriptGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[PydanticGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[ErDiagramGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[StatsGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[DocsGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[TranslationGeneratorOptions] = LinkmlYamlCodec.derived
  given LinkmlYamlCodec[OssieGeneratorOptions] = LinkmlYamlCodec.derived
}

/** A `SchemaValidationReport` (model/validation-report.yaml) as a plain JS object. Only a name for
  * the TypeScript declarations. In TS it is the type generated from model/issue-types.yaml.
  */
type SchemaValidationReport = js.Any

/** A `BuildInfo` (model/build-info.yaml) as a plain JS object. Only a name for the TypeScript
  * declarations: there, it is the type generated from model/build-info.yaml.
  */
type BuildInfo = js.Any

/** What loading a schema produced: always a validation report, and a usable handle if the schema
  * could be loaded at all.
  */
@JSExportAll
final class LoadResult private[js] (
    val view: js.UndefOr[SchemaViewJs],
    val report: SchemaValidationReport,
)

@JSExportTopLevel("LinkML")
@JSExportAll
object LinkMlJsApi {
  private case class JsImporter(map: js.Dictionary[String]) extends StringImporter {
    private val lookup = Importer.normalizedMap(map)

    override def read(path: String): String =
      lookup.getOrElse(path, sys.error(s"Could not read from the imports: $path"))
  }

  /** Version and build metadata of this copy of ohi LinkML: which version it is, which LinkML
    * metamodel it was built against, and what it is running on.
    *
    * Useful in bug reports, and for checking that the version you loaded is the one you meant to.
    *
    * @return
    *   A `BuildInfo` object, as described by https://linkml.ohi.dev/model/build-info
    */
  def buildInfo(): BuildInfo =
    js.JSON.parse(JsonUtil.yamlToJson(CurrentBuild.node()))

  /** Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from the
    * schema's YAML text.
    *
    * The main schema is parsed directly from `mainSchema`, so it has no path of its own. If one of
    * its imports (transitively) imports the main schema back by filename, that import cannot be
    * matched against the root and the main schema will be loaded a second time. Use
    * [[loadFromPath]] instead when the root schema takes part in an import cycle.
    *
    * See [[loadFromPath]] for the correct key format.
    *
    * @param mainSchema
    *   Main LinkML model in YAML format. It may import other models using LinkML `imports`, but all
    *   imports must be made available in the [[imports]].
    * @param imports
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @param inferMessages
    *   Whether to fill in each issue's human-readable `message` and `details`.
    * @param importMap
    *   Optional JS dictionary from an import to the location to load it from, which is a key of the
    *   [[imports]], such as `{"https://example.org/units/": "deps/units/"}`. Keys are full URIs or
    *   paths, not CURIEs. A key that ends with `/` or `#` matches every import that starts with it.
    *   CURIE imports are expanded with the prefixes of the schema that contains them before they
    *   are matched.
    * @return
    *   The validation report, and a handle with the generators unless the schema had fatal
    *   problems.
    */
  def loadFromString(
      mainSchema: String,
      imports: js.Dictionary[String],
      inferMessages: Boolean = true,
      importMap: js.UndefOr[js.Dictionary[String]] = js.undefined,
  ): LoadResult =
    loadResult(
      withImportMap(imports, importMap)(SchemaView.loadSchemaViewFromString(mainSchema, _)),
      runId = None,
      inferMessages,
    )

  /** Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from a path
    * into the [[imports]].
    *
    * Unlike [[loadFromString]], the main schema is read through the imports by its own path, so it
    * is tracked from the start of import resolution. This makes it immune to cyclic imports
    * involving the root schema: an import that (transitively) references the root back by path
    * resolves to the already-loaded root instead of loading it again.
    *
    * Keys in the [[imports]] are import paths as seen from the root schema. The `.yaml` and `.yml`
    * extensions are optional and interchangeable, and each imported schema is loaded only once,
    * even when it is reached through different relative paths.
    *
    * @param path
    *   Path of the main LinkML model within the [[imports]] (e.g. `"model.yaml"`).
    * @param imports
    *   JS dictionary (object) containing a mapping from path to LinkML models (in YAML format),
    *   including the main schema itself under [[path]].
    * @param inferMessages
    *   Whether to fill in each issue's human-readable `message` and `details`.
    * @param importMap
    *   Optional JS dictionary from an import to the location to load it from, which is a key of the
    *   [[imports]], such as `{"https://example.org/units/": "deps/units/"}`. Keys are full URIs or
    *   paths, not CURIEs. A key that ends with `/` or `#` matches every import that starts with it.
    *   CURIE imports are expanded with the prefixes of the schema that contains them before they
    *   are matched.
    * @return
    *   The validation report, and a handle with the generators unless the schema had fatal
    *   problems.
    */
  def loadFromPath(
      path: String,
      imports: js.Dictionary[String],
      inferMessages: Boolean = true,
      importMap: js.UndefOr[js.Dictionary[String]] = js.undefined,
  ): LoadResult =
    loadResult(
      withImportMap(imports, importMap)(SchemaView.loadSchemaViewFromUri(path, _)),
      runId = Some(path),
      inferMessages,
    )

  /** Load through the [[imports]], with the `importMap` applied to every import, or report an entry
    * of it that is not allowed.
    */
  private def withImportMap(
      imports: js.Dictionary[String],
      importMap: js.UndefOr[js.Dictionary[String]],
  )(
      load: Importer => Either[Seq[SchemaFatal], SchemaView],
  ): Either[Seq[SchemaFatal], SchemaView] =
    ImportMapImporter.fromEntries(
      importMap.fold(Map.empty[String, String])(_.toMap),
      JsImporter(imports),
    ) match {
      case Right(importer) => load(importer)
      case Left(issue) => new Left(Seq(issue))
    }

  /** Turn a load outcome into a [[LoadResult]]. A schema that loaded is linted straight away, so
    * that the report covers errors and warnings too - not just the fatals that blocked loading.
    */
  private def loadResult(
      loaded: Either[Seq[SchemaFatal], SchemaView],
      runId: Option[String],
      inferMessages: Boolean,
  ): LoadResult =
    loaded match {
      case Right(sv) =>
        val issues = SchemaValidator(using sv).lintProblems
        new LoadResult(new SchemaViewJs(sv), reportJson(issues, runId, inferMessages))
      case Left(problems) =>
        new LoadResult(js.undefined, reportJson(problems, runId, inferMessages))
    }

  /** Serialize issues as a `SchemaValidationReport`, as a plain JS object. */
  private def reportJson(
      issues: Seq[SchemaIssue],
      runId: Option[String],
      inferMessages: Boolean,
  ): SchemaValidationReport = {
    val report = SchemaValidationReport(
      issues = if inferMessages then issues.map(_.infer()) else issues,
      validationRunId = runId,
    )
    js.JSON.parse(JsonUtil.yamlToJson(Codec.codec.encode(report)))
  }

  /** Run a generator or importer. A problem with the input that the user can fix, a
    * [[SchemaConversionException]] or an [[RdfParseException]], is thrown as a JS `Error` with a
    * `SchemaConversionError` issue in its `issue` property, so callers can tell it from a bug.
    */
  private[js] def converting[A](body: => A): A =
    try body
    catch {
      case e @ (_: SchemaConversionException | _: RdfParseException) =>
        val report = reportJson(Seq(SchemaIssues.conversionError(e.getMessage)), None, true)
        val error = new js.Error(e.getMessage)
        error.asInstanceOf[js.Dynamic].issue =
          report.asInstanceOf[js.Dynamic].issues.asInstanceOf[js.Array[js.Any]](0)
        throw js.JavaScriptException(error)
    }

  /** Read an OWL ontology and produce the LinkML schema it describes.
    *
    * The opposite of [[SchemaViewJs.owl]]. Feed the result to [[loadFromString]] if you want to run
    * a generator over it.
    *
    * @param ontology
    *   The ontology, as Turtle (or N-Triples, which is Turtle too).
    * @param config
    *   How to map the ontology: the text of the config itself, as YAML or JSON, not a path to a
    *   file (see https://linkml.ohi.dev/docs/owl/). By default, settings that suit most ontologies.
    * @param outFormat
    *   Output serialization format to use. One of yaml|json. Default: yaml
    * @param listNotImported
    *   Whether to list what could not be imported, in comments at the top of the YAML. Default:
    *   false
    * @param imports
    *   The LinkML schemas that the config maps `owl:imports` to, keyed as in [[loadFromString]].
    *   The schema then uses their terms by name rather than copying them in.
    * @param inputFormat
    *   RDF syntax of the ontology. One of ttl|nt. Default: ttl, which also reads N-Triples.
    * @param fullUris
    *   Write URIs in full, instead of compacting them to CURIEs with the schema's prefixes.
    *   Default: false
    * @return
    *   The LinkML schema, serialized in the specified format.
    */
  def fromOwl(
      ontology: String,
      config: js.UndefOr[String] = js.undefined,
      outFormat: String = "yaml",
      listNotImported: Boolean = false,
      imports: js.UndefOr[js.Dictionary[String]] = js.undefined,
      inputFormat: String = "ttl",
      fullUris: Boolean = false,
  ): String = {
    val format = outputFormat(outFormat)
    val importer = OwlImporter()
    val result = converting(
      importer.importWithWarnings(
        java.io.ByteArrayInputStream(ontology.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        OwlImporter.Options(
          config = config.toOption.fold(OwlImportConfig())(OwlImportConfigs.parse),
          schemas = JsImporter(imports.getOrElse(js.Dictionary())),
          inputFormat = rdfFormat(inputFormat),
        ),
      ),
    )
    val schema = converting(importer.serializeSchema(result.schema, format, fullUris))
    if listNotImported && format == JsonOutputFormat.Yaml then
      result.warnings.map(w => s"# Not imported: $w\n").mkString + schema
    else schema
  }

  /** The YAML-or-JSON format the caller named. */
  private def outputFormat(format: String): JsonOutputFormat = format.toLowerCase match {
    case "yaml" | "yml" => JsonOutputFormat.Yaml
    case "json" => JsonOutputFormat.Json
    case other =>
      throw RuntimeException(s"Unknown output format: $other. Supported formats: yaml, json.")
  }

  /** The RDF format the caller named. */
  private def rdfFormat(format: String): RdfFormat = format.toLowerCase match {
    case "nt" | "ntriples" => RdfFormat.Nt
    case "ttl" | "turtle" => RdfFormat.Ttl
    case other => throw RuntimeException(s"Unknown RDF format: $other. Supported formats: nt, ttl.")
  }

  /** Read an Apache Ossie ontology and produce the LinkML schema it describes.
    *
    * The opposite of [[SchemaViewJs.ossie]]. Feed the result to [[loadFromString]] if you want to
    * run a generator over it.
    *
    * @param ontology
    *   The ontology document, as YAML or JSON.
    * @param schemaId
    *   The `id` of the schema to produce. An Ossie ontology has none of its own, so by default it
    *   is a placeholder built from the ontology's name.
    * @param outFormat
    *   Output serialization format to use. One of yaml|json. Default: yaml
    * @param fullUris
    *   Write URIs in full, instead of compacting them to CURIEs with the schema's prefixes.
    *   Default: false
    * @return
    *   The LinkML schema, serialized in the specified format.
    */
  def fromOssie(
      ontology: String,
      schemaId: js.UndefOr[String] = js.undefined,
      outFormat: String = "yaml",
      fullUris: Boolean = false,
  ): String = {
    val options = OssieImporter.Options(
      schemaId = schemaId.toOption,
      outputFormat = outputFormat(outFormat),
      fullUris = fullUris,
    )
    converting(OssieImporter().serializeFromString(ontology, options))
  }

  /** Lint a loaded LinkML schema, finding problems that may cause issues when using the model. This
    * method returns a structured JSON that follows the validation-report.yaml model.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param inferMessages
    *   Whether to fill in each issue's human-readable `message` and `details` from the model's
    *   `equals_expression`s. Turn it off to get only the structured fields.
    * @return
    *   A `SchemaValidationReport` as a plain JS object. `issues` is empty if the schema is clean.
    */
  def lint(schema: SchemaViewJs, inferMessages: Boolean = true): SchemaValidationReport =
    reportJson(SchemaValidator(using schema.underlying).lintProblems, None, inferMessages)
}
