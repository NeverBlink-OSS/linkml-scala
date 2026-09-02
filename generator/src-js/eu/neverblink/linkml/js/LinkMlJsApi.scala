package eu.neverblink.linkml.js

import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.rdf.RdfFormat
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.util.{JsonUtil, PruningMode}
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.schemaview.{Importer, SchemaValidator, SchemaView, StringImporter}
import eu.neverblink.linkml.schemaview.buildinfo.CurrentBuild
import eu.neverblink.linkml.validation.{Codec, SchemaFatal, SchemaIssue, SchemaValidationReportImpl}

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

/** What loading a schema produced: always a validation report, and a usable handle if the schema
  * could be loaded at all.
  */
@JSExportAll
final class LoadResult private[js] (
    val view: js.UndefOr[SchemaViewJs],
    val report: js.Any,
)

@JSExportTopLevel("LinkML")
@JSExportAll
object LinkMlJsApi {
  private case class JsImporter(map: js.Dictionary[String]) extends StringImporter {
    private val lookup = Importer.normalizedMap(map)

    override def read(path: String): String =
      lookup.getOrElse(path, sys.error(s"Could not read from import map: $path"))
  }

  /** Version and build metadata of this copy of LinkML-Scala: which version it is, which LinkML
    * metamodel it was built against, and what it is running on.
    *
    * Useful in bug reports, and for checking that the version you loaded is the one you meant to.
    *
    * @return
    *   A `BuildInfo` object, as described by https://linkml.neverblink.eu/model/build-info
    */
  def buildInfo(): js.Any =
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
    *   imports must be made available in the [[importMap]].
    * @param importMap
    *   JS dictionary (object) containing a mapping from filename to LinkML models (in YAML format)
    * @param inferMessages
    *   Whether to fill in each issue's human-readable `message` and `details`.
    * @return
    *   The validation report, and a handle to pass to the generator functions unless the schema had
    *   fatal problems.
    */
  def loadFromString(
      mainSchema: String,
      importMap: js.Dictionary[String],
      inferMessages: Boolean = true,
  ): LoadResult =
    loadResult(
      SchemaView.loadSchemaViewFromString(mainSchema, JsImporter(importMap)),
      runId = None,
      inferMessages,
    )

  /** Load and resolve a LinkML schema into a reusable [[SchemaView]] handle, starting from a path
    * into the [[importMap]].
    *
    * Unlike [[loadFromString]], the main schema is read through the import map by its own path, so
    * it is tracked from the start of import resolution. This makes it immune to cyclic imports
    * involving the root schema: an import that (transitively) references the root back by path
    * resolves to the already-loaded root instead of loading it again.
    *
    * Keys in the ``imports`` parameter must match the expanded form of the ``imports`` entries in
    * the schema. In particular:
    *
    *   - A CURIE is expanded through the schema's prefix map, so ``imports: [ex:core]`` has to be
    *     keyed here by the full URI, such as ``"https://example.org/core.yaml"``.
    *   - A relative import is joined to the directory of the schema that imported it, so a ``core``
    *     imported by ``nested/model.yaml`` has to be keyed ``"nested/core.yaml"``. Keys are
    *     therefore paths as seen from the root.
    *   - ``.yaml`` is appended unless the path already ends in ``.yaml`` or ``.yml``. Therefore,
    *     ``"core"`` and ``"core.yaml"`` are interchangeable, and a key that ends in ``.yml`` is
    *     only found by an import that explicitly asks for ``.yml``.
    *
    * @param path
    *   Path of the main LinkML model within the [[importMap]] (e.g. `"model.yaml"`).
    * @param importMap
    *   JS dictionary (object) containing a mapping from path to LinkML models (in YAML format),
    *   including the main schema itself under [[path]].
    * @param inferMessages
    *   Whether to fill in each issue's human-readable `message` and `details`.
    * @return
    *   The validation report, and a handle to pass to the generator functions unless the schema had
    *   fatal problems.
    */
  def loadFromPath(
      path: String,
      importMap: js.Dictionary[String],
      inferMessages: Boolean = true,
  ): LoadResult =
    loadResult(
      SchemaView.loadSchemaViewFromUri(path, JsImporter(importMap)),
      runId = Some(path),
      inferMessages,
    )

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
  ): js.Any = {
    val report = SchemaValidationReportImpl(
      issues = if inferMessages then issues.map(_.infer()) else issues,
      validationRunId = runId,
    )
    js.JSON.parse(JsonUtil.yamlToJson(Codec.codec.encode(report)))
  }

  /** Generate JSON Schema from a loaded LinkML schema.
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param open
    *   Whether the JSON Schema should allow `additionalProperties` or not.
    * @param treeRootOverride
    *   Override for the LinkML `tree_root` class which will be at the root of the JSON Schema.
    * @return
    *   Serialized JSON Schema
    */
  def jsonSchema(
      schema: SchemaViewJs,
      open: Boolean = false,
      treeRootOverride: js.UndefOr[String] = js.undefined,
  ): String =
    JsonSchemaGenerator(using schema.underlying).serialize(
      JsonSchemaGenerator.Options(open = open, treeRoot = treeRootOverride.toOption),
    )

  /** Generate SHACL shapes from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param open
    *   Whether the SHACL shapes should be open (`_:b sh:closed false .`, allowing additional
    *   properties).
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    * @param format
    *   RDF serialization format: `ttl` for Turtle (the default), which is prefixed and
    *   pretty-printed, or `nt` for N-Triples.
    * @return
    *   SHACL shapes in the requested format
    */
  def shacl(
      schema: SchemaViewJs,
      open: Boolean = false,
      onlyClassesFromRootSchema: Boolean = false,
      format: String = "ttl",
  ): String =
    ShaclGenerator(using schema.underlying).serialize(
      ShaclGenerator.Options(
        open = open,
        onlyClassesFromRootSchema = onlyClassesFromRootSchema,
        format = rdfFormat(format),
      ),
    )

  /** Generate Scala code from a loaded LinkML schema. This is primarily used for the metamodel
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param `package`
    *   Package to generate the classes in
    * @return
    *   JS dictionary (object) containing a mapping from filename to the generated Scala code.
    */
  def scala(
      schema: SchemaViewJs,
      `package`: String,
  ): js.Dictionary[String] =
    ScalaGenerator(using schema.underlying)
      .generate(ScalaGenerator.Options(`package` = `package`))
      .toMap
      .toJSDictionary

  /** Generate RDFS from a loaded LinkML schema.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param onlyClassesFromRootSchema
    *   Whether to include only classes from the root schema (turned off by default). This is useful
    *   if you intend to generate SHACL shapes for each schema file separately, and you don't need
    *   the imported classes to be included in the generated SHACL shapes.
    * @param format
    *   RDF serialization format: `ttl` for Turtle (the default), which is prefixed and
    *   pretty-printed, or `nt` for N-Triples.
    * @return
    *   RDFS in the requested format
    */
  def rdfs(
      schema: SchemaViewJs,
      onlyClassesFromRootSchema: Boolean = false,
      format: String = "ttl",
  ): String =
    RdfsGenerator(using schema.underlying).serialize(
      RdfsGenerator.Options(
        onlyClassesFromRootSchema = onlyClassesFromRootSchema,
        format = rdfFormat(format),
      ),
    )

  /** The RDF format the caller named, as the generators spell it. */
  private def rdfFormat(format: String): RdfFormat = format.toLowerCase match {
    case "nt" | "ntriples" => RdfFormat.nt
    case "ttl" | "turtle" => RdfFormat.ttl
    case other => throw RuntimeException(s"Unknown RDF format: $other. Supported formats: nt, ttl.")
  }

  /** Materialize a derived LinkML schema from a loaded LinkML schema. Derives classes and prunes
    * unreachable elements.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param pruningMode
    *   Pruning mode to use for removing unused elements (classes, types, enums). One of
    *   treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class.
    *   schema - remove all elements unreachable from any of the classes defined in the root schema.
    *   skip - do not remove unused elements. Default: treeRoot
    * @param skipDerivation
    *   If true, will not derive classes and instead copy them as-is.
    * @param treeRoot
    *   Tree root class name to use instead of the schema defined tree_root. Does nothing if not in
    *   tree root pruning mode.
    * @param outFormat
    *   Output serialization format to use. One of yaml|json. Default: yaml
    * @return
    *   The derived [[SchemaDefinition]] serialized in the specified format.
    */
  def linkml(
      schema: SchemaViewJs,
      pruningMode: String = "treeRoot",
      skipDerivation: Boolean = false,
      treeRoot: js.UndefOr[String] = js.undefined,
      outFormat: String = "yaml",
  ): String = {
    val mode = PruningMode(pruningMode, treeRoot.toOption)

    val format = outFormat.toLowerCase match {
      case "yaml" => LinkMlGenerator.OutputFormat.yaml
      case "yml" => LinkMlGenerator.OutputFormat.yaml
      case "json" => LinkMlGenerator.OutputFormat.json
      case s => throw RuntimeException(s"Unknown output format: $s")
    }
    LinkMlGenerator(using schema.underlying).serialize(
      LinkMlGenerator.Options(
        pruningMode = mode,
        skipClassDerivation = skipDerivation,
        outputFormat = format,
      ),
    )
  }

  /** Generate a Frictionless Data Package from a loaded LinkML schema. Every class becomes a CSV
    * table, described by its own Table Schema, and references between classes become foreign keys
    * between the tables.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param pruningMode
    *   Pruning mode to use for choosing which classes become tables. One of treeRoot|schema|skip.
    *   treeRoot - only classes reachable from the tree_root class. schema - only classes reachable
    *   from any of the classes defined in the root schema. skip - every class. Default: skip
    * @param treeRoot
    *   Tree root class name to use instead of the schema defined tree_root. Does nothing if not in
    *   tree root pruning mode.
    * @param skipClassesWithoutIdentifier
    *   Whether to skip classes that have no identifier slot. Such a table gets no primary key and
    *   nothing can reference it, so it is often not useful. Default: false
    * @return
    *   JS dictionary (object) containing a mapping from filename to file content: a
    *   `datapackage.json` plus one `schemas/<table>.json` per table.
    */
  def frictionless(
      schema: SchemaViewJs,
      pruningMode: String = "skip",
      treeRoot: js.UndefOr[String] = js.undefined,
      skipClassesWithoutIdentifier: Boolean = false,
  ): js.Dictionary[String] =
    FrictionlessGenerator(using schema.underlying)
      .generateFiles(
        FrictionlessGenerator.Options(
          pruningMode = PruningMode(pruningMode, treeRoot.toOption),
          skipClassesWithoutIdentifier = skipClassesWithoutIdentifier,
        ),
      )
      .toMap
      .toJSDictionary

  /** Generate a GraphQL Schema from a loaded LinkML schema. Only types/interfaces/scalar/enums,
    * queries must be provided for a specific implementation.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param pruningMode
    *   Pruning mode to use for removing unused elements (classes, types, enums). One of
    *   treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class.
    *   schema - remove all elements unreachable from any of the classes defined in the root schema.
    *   skip - do not remove unused elements. Default: treeRoot
    * @param treeRoot
    *   Tree root class name to use instead of the schema defined tree_root.
    * @return
    *   Table Schema, serialized as a JSON
    */
  def graphQl(
      schema: SchemaViewJs,
      pruningMode: String = "treeRoot",
      treeRoot: js.UndefOr[String] = js.undefined,
  ): String =
    GraphQlGenerator(using schema.underlying).serialize(
      GraphQlGenerator.Options(PruningMode(pruningMode, treeRoot.toOption)),
    )

  /** Generate a Mermaid entity relationship diagram from a loaded LinkML schema. Classes become
    * entities, type- and enum-ranged slots become their attributes, and class-ranged slots become
    * relationship lines.
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param pruningMode
    *   Pruning mode to use for removing unused elements (classes, types, enums). One of
    *   treeRoot|schema|skip. treeRoot - remove all elements unreachable from the tree_root class.
    *   schema - remove all elements unreachable from any of the classes defined in the root schema.
    *   skip - do not remove unused elements. Default: treeRoot
    * @param treeRoot
    *   Tree root class name to use instead of the schema defined tree_root.
    * @param optionalMarker
    *   Whether to mark optional attributes with a trailing '?' on their type. Mermaid understands
    *   this from version 11.16 onwards, older renderers throw an error instead. Default: true
    * @return
    *   The ER diagram, serialized as Mermaid
    */
  def erDiagram(
      schema: SchemaViewJs,
      pruningMode: String = "treeRoot",
      treeRoot: js.UndefOr[String] = js.undefined,
      optionalMarker: Boolean = true,
  ): String =
    ErDiagramGenerator(using schema.underlying).serialize(
      ErDiagramGenerator.Options(
        pruningMode = PruningMode(pruningMode, treeRoot.toOption),
        optionalMarker = optionalMarker,
      ),
    )

  /** Lint a loaded LinkML schema, finding problems that may cause issues when using the model. This
    * method returns a structured JSON that follows the validation-report.yaml model.
    *
    * TODO: consider typing the return value in TypeScript using a TypeScript generator. See:
    * https://github.com/NeverBlink-OSS/linkml-scala/issues/127
    *
    * @param schema
    *   A [[SchemaView]] handle created with [[loadFromString]] or [[loadFromPath]].
    * @param inferMessages
    *   Whether to fill in each issue's human-readable `message` and `details` from the model's
    *   `equals_expression`s. Turn it off to get only the structured fields.
    * @return
    *   A `SchemaValidationReport` as a plain JS object. `issues` is empty if the schema is clean.
    */
  def lint(schema: SchemaViewJs, inferMessages: Boolean = true): js.Any =
    reportJson(SchemaValidator(using schema.underlying).lintProblems, None, inferMessages)
}
