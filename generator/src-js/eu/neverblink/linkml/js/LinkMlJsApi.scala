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
  */
final class SchemaViewJs private[js] (private[js] val underlying: SchemaView)

/** A `SchemaValidationReport` (model/validation-report.yaml) as a plain JS object. Only a name for
  * the TypeScript declarations. In TS it is the type generated from model/issue-types.yaml.
  */
type SchemaValidationReport = js.Any

/** A `BuildInfo` (model/build-info.yaml) as a plain JS object. Only a name for the TypeScript
  * declarations: there, it is the type generated from model/build-info.yaml.
  */
type BuildInfo = js.Any

/** A `SchemaStats` (model/schema-stats.yaml) as a plain JS object. Only a name for the TypeScript
  * declarations: there, it is the type generated from model/schema-stats.yaml.
  */
type SchemaStats = js.Any

/** The options of a generator as a plain JS object, with the fields of [[T]] in snake case. Only a
  * name for the TypeScript declarations: there, it is a `Partial` of the type generated from
  * model/generator-options.yaml.
  */
type GeneratorOptions[T] = js.Object

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
    *   The validation report, and a handle to pass to the generator functions unless the schema had
    *   fatal problems.
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
    *   The validation report, and a handle to pass to the generator functions unless the schema had
    *   fatal problems.
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
  private def converting[A](body: => A): A =
    try body
    catch {
      case e @ (_: SchemaConversionException | _: RdfParseException) =>
        val report = reportJson(Seq(SchemaIssues.conversionError(e.getMessage)), None, true)
        val error = new js.Error(e.getMessage)
        error.asInstanceOf[js.Dynamic].issue =
          report.asInstanceOf[js.Dynamic].issues.asInstanceOf[js.Array[js.Any]](0)
        throw js.JavaScriptException(error)
    }

  /** Generate JSON Schema from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `JsonSchemaGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   Serialized JSON Schema
    */
  def jsonSchema(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[JsonSchemaGeneratorOptions]] = js.undefined,
  ): String =
    JsonSchemaGenerator(using schema.underlying).serialize(decode(options))

  /** Generate SHACL shapes from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `ShaclGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   SHACL shapes in the requested format
    */
  def shacl(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[ShaclGeneratorOptions]] = js.undefined,
  ): String =
    ShaclGenerator(using schema.underlying).serialize(decode(options))

  /** Generate Scala code from a loaded LinkML schema. This is primarily used for the metamodel
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `ScalaGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   JS dictionary (object) containing a mapping from filename to the generated Scala code.
    */
  def scala(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[ScalaGeneratorOptions]] = js.undefined,
  ): js.Dictionary[String] =
    ScalaGenerator(using schema.underlying).generate(decode(options)).toMap.toJSDictionary

  /** Generate RDFS from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `RdfsGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   RDFS in the requested format
    */
  def rdfs(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[RdfsGeneratorOptions]] = js.undefined,
  ): String =
    RdfsGenerator(using schema.underlying).serialize(decode(options))

  /** Generate an OWL 2 ontology from a loaded LinkML schema. Classes, slots, enums and types become
    * OWL classes, properties, classes of individuals and datatypes, with the same IRIs as [[shacl]]
    * and [[rdfs]].
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `OwlGeneratorOptions` of `model/generator-options.yaml`,
    *   with their names in snake case. The options left out take their defaults.
    * @return
    *   The ontology in the requested format
    */
  def owl(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[OwlGeneratorOptions]] = js.undefined,
  ): String =
    converting(OwlGenerator(using schema.underlying).serialize(decode(options)))

  /** Read an OWL ontology and produce the LinkML schema it describes.
    *
    * The opposite of [[owl]]. Feed the result to [[loadFromString]] if you want to run a generator
    * over it.
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

  /** The options of a generator, from the plain JS object the caller passed. */
  private def decode[T](options: js.UndefOr[GeneratorOptions[T]])(using
      codec: LinkmlYamlCodec[T],
  ): T =
    parseYaml(options.fold("{}")(js.JSON.stringify(_))) match {
      case Right(node) =>
        try codec.decode(node)
        catch { case e: Exception => throw RuntimeException(s"Invalid options: ${e.getMessage}") }
      case Left(error) => throw RuntimeException(s"Invalid options: ${error.msg}")
    }

  private given LinkmlYamlCodec[JsonSchemaGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[ShaclGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[ScalaGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[RdfsGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[OwlGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[LinkMlGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[FrictionlessGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[GraphQlGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[TypeScriptGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[PydanticGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[ErDiagramGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[StatsGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[DocsGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[TranslationGeneratorOptions] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[OssieGeneratorOptions] = LinkmlYamlCodec.derived

  /** Materialize a derived LinkML schema from a loaded LinkML schema. Derives classes and prunes
    * unreachable elements.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `LinkMlGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   The derived [[SchemaDefinition]] serialized in the specified format.
    */
  def linkml(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[LinkMlGeneratorOptions]] = js.undefined,
  ): String =
    converting(LinkMlGenerator(using schema.underlying).serialize(decode(options)))

  /** Generate a Frictionless Data Package from a loaded LinkML schema. Every class becomes a CSV
    * table, described by its own Table Schema, and references between classes become foreign keys
    * between the tables.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `FrictionlessGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   JS dictionary (object) containing a mapping from filename to file content: a
    *   `datapackage.json` plus one `schemas/<table>.json` per table.
    */
  def frictionless(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[FrictionlessGeneratorOptions]] = js.undefined,
  ): js.Dictionary[String] =
    FrictionlessGenerator(using schema.underlying).generateFiles(
      decode(options),
    ).toMap.toJSDictionary

  /** Generate a GraphQL Schema from a loaded LinkML schema. Only types/interfaces/scalar/enums,
    * queries must be provided for a specific implementation.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `GraphQlGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   Table Schema, serialized as a JSON
    */
  def graphQl(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[GraphQlGeneratorOptions]] = js.undefined,
  ): String =
    GraphQlGenerator(using schema.underlying).serialize(decode(options))

  /** Generate TypeScript types from a loaded LinkML schema. The types describe the same JSON as
    * [[jsonSchema]], with no runtime code: load data with `JSON.parse(text) as X` and dump it with
    * `JSON.stringify(x)`.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `TypeScriptGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   TypeScript source code
    */
  def typeScript(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[TypeScriptGeneratorOptions]] = js.undefined,
  ): String =
    TypeScriptGenerator(using schema.underlying).serialize(decode(options))

  /** Generate Python classes based on pydantic from a loaded LinkML schema. The classes load and
    * dump the same JSON as [[jsonSchema]] describes: load data with `X.model_validate_json(text)`
    * and dump it with `x.model_dump_json()`.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `PydanticGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   Python source code
    */
  def pydantic(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[PydanticGeneratorOptions]] = js.undefined,
  ): String =
    PydanticGenerator(using schema.underlying).serialize(decode(options))

  /** Generate a Mermaid entity relationship diagram from a loaded LinkML schema. Classes become
    * entities, type- and enum-ranged slots become their attributes, and class-ranged slots become
    * relationship lines.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `ErDiagramGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   The ER diagram, serialized as Mermaid
    */
  def erDiagram(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[ErDiagramGeneratorOptions]] = js.undefined,
  ): String =
    ErDiagramGenerator(using schema.underlying).serialize(decode(options))

  /** Statistics about a loaded LinkML schema: how many elements of each kind it has, how many of
    * them are documented, deprecated or mapped, the strings in each language, and the imports.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `StatsGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   A `SchemaStats` object, as described by https://linkml.ohi.dev/model/schema-stats
    */
  def stats(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[StatsGeneratorOptions]] = js.undefined,
  ): SchemaStats = {
    val stats = StatsGenerator(using schema.underlying).generate(decode(options))
    js.JSON.parse(JsonUtil.yamlToJson(StatsGenerator.codec.encode(stats)))
  }

  /** Generate Markdown documentation from a loaded LinkML schema: an index page plus a page for
    * each class, slot, enum and type, which link to each other.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `DocsGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   JS dictionary (object) containing a mapping from filename to file content: `index.md` plus
    *   `classes/<name>.md`, `slots/<name>.md`, `enums/<name>.md`, `types/<name>.md` and
    *   `functions/<name>.md`, or only `docs.md` if [[singleFile]] is true.
    */
  def docs(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[DocsGeneratorOptions]] = js.undefined,
  ): js.Dictionary[String] = {
    val files = js.Dictionary.empty[String]
    DocsGenerator(using schema.underlying).generateFiles(decode(options)).foreach(f =>
      files(f.path) = f.content,
    )
    files
  }

  /** Generate JSON dictionaries that translate the LinkML name to specific frameworks. This is
    * useful when the framework symbols are significant and must be known, like when constructing a
    * query that is meant to be executed against a database conformant to a LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `TranslationGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   Translation dictionary for translating the linkml names to framework names.
    */
  def translation(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[TranslationGeneratorOptions]] = js.undefined,
  ): String =
    TranslationGenerator(using schema.underlying).serialize(decode(options))

  /** Generate an Apache Ossie ontology from a loaded LinkML schema. Classes become entity types,
    * enums and named types become value types, and slots become the relationships grouped under the
    * concept that plays their first role.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param options
    *   The options of the generator, as in `OssieGeneratorOptions` of
    *   `model/generator-options.yaml`, with their names in snake case. The options left out take
    *   their defaults.
    * @return
    *   The ontology, serialized in the specified format.
    */
  def ossie(
      schema: SchemaViewJs,
      options: js.UndefOr[GeneratorOptions[OssieGeneratorOptions]] = js.undefined,
  ): String =
    converting(OssieGenerator(using schema.underlying).serialize(decode(options)))

  /** Read an Apache Ossie ontology and produce the LinkML schema it describes.
    *
    * The opposite of [[ossie]]. Feed the result to [[loadFromString]] if you want to run a
    * generator over it.
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
