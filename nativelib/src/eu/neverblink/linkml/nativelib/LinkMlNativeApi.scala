package eu.neverblink.linkml.nativelib

import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.schemaview.{SchemaValidator, SchemaView, StringImporter}
import eu.neverblink.linkml.schemaview.buildinfo.CurrentBuild
import eu.neverblink.linkml.validation.{Codec, SchemaIssue, SchemaValidationReportImpl}
import org.virtuslab.yaml.{Node, StringNode}

import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Methods that [[LinkMlCApi]] calls: one method per exported C function.
  *
  * Failures are thrown. [[LinkMlCApi]] turns them into the C error convention.
  *
  * Loaded schemas live in a table here and are handed out as integer handles.
  */
object LinkMlNativeApi {

  /** Bumped whenever a change to the exported functions or to the options JSON breaks existing
    * callers.
    *
    *   - 2 added `linkml_build_info`.
    */
  final val abiVersion: Int = 2

  private val schemas = new ConcurrentHashMap[java.lang.Long, SchemaView]()

  private val handleSeq = new AtomicLong(0L)

  /** A loaded schema: the handle to use for it, and the validation report from loading it. */
  final class Loaded private[nativelib] (val handle: Long, val report: String)

  // Loading

  /** Load a schema from the file system, resolving its imports the same way the CLI does.
    *
    * @return
    *   a handle and the report, or handle 0 and a report saying why the schema could not be loaded
    */
  def loadFile(path: String, optionsJson: String): Loaded =
    loaded(SchemaView.loadSchemaViewFromUri(path), Some(path), Options.load(optionsJson))

  /** Load a schema from YAML text, resolving its imports against a caller-supplied map.
    *
    * @param path
    *   the root schema's own path within the import map, or null to load [[schema]] directly.
    *   Giving a path makes loading immune to an import that references the root back, because the
    *   root is then tracked from the start.
    * @param schema
    *   the root schema as YAML. Ignored when [[path]] is given, since the root is read from the
    *   map.
    * @param importNames
    *   import map keys, positionally matched with [[importBodies]]
    */
  def loadString(
      path: String,
      schema: String,
      importNames: Array[String],
      importBodies: Array[String],
      optionsJson: String,
  ): Loaded = {
    if importNames.length != importBodies.length then
      throw BadRequest(
        s"the import map has ${importNames.length} names but ${importBodies.length} bodies",
      )
    val importer = ImportMap(importNames.zip(importBodies).toMap)
    val options = Options.load(optionsJson)
    if path ne null then
      loaded(SchemaView.loadSchemaViewFromUri(path, importer), Some(path), options)
    else {
      if schema eq null then throw BadRequest("loading needs either a path or the schema text")
      loaded(SchemaView.loadSchemaViewFromString(schema, importer), None, options)
    }
  }

  /** Drop a schema handle. Closing one that is already gone is not an error, so a caller can close
    * from a finalizer without tracking whether it already did.
    */
  def close(handle: Long): Unit = {
    schemas.remove(handle)
    ()
  }

  // Generators writing a single document
  //
  // These generators write directly into off-heap buffers.

  def jsonSchema(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    JsonSchemaGenerator().writeTo(out, Options.jsonSchema(optionsJson))
  }

  /** SHACL as N-Triples. Turtle is not available here: it would pull in RDF4J, which the shared
    * library deliberately leaves out.
    */
  def shacl(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    ShaclGenerator().writeTo(out, Options.shacl(optionsJson))
  }

  /** RDFS as N-Triples, for the same reason as [[shacl]]. */
  def rdfs(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    RdfsGenerator().writeTo(out, Options.rdfs(optionsJson))
  }

  def linkml(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    LinkMlGenerator().writeTo(out, Options.linkml(optionsJson))
  }

  def tableSchema(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    TableSchemaGenerator().writeTo(out, Options.tableSchema(optionsJson))
  }

  def graphQl(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    GraphQlGenerator().writeTo(out, Options.graphQl(optionsJson))
  }

  def erDiagram(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    ErDiagramGenerator().writeTo(out, Options.erDiagram(optionsJson))
  }

  // Results that are structured, and so come back as JSON

  /** Generate Scala sources, as a JSON object mapping filename to source. */
  def scalaFiles(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    given SchemaView = view(handle)
    val generated = ScalaGenerator().generate(Options.scala(optionsJson))
    JsonUtil.writeJson(
      Node.MappingNode(generated.map((name, text) => entry(name, StringNode(text))).toMap),
      out,
    )
  }

  /** Version and build metadata for this library, as a `BuildInfo` in JSON.
    *
    * The ABI version is added here rather than in the shared code, because it is a fact about the
    * shared library and means nothing in the other distributions.
    */
  def buildInfo(out: OutputStream): Unit =
    JsonUtil.writeJson(
      CurrentBuild.node(CurrentBuild.info.copy(abiVersion = Some(abiVersion))),
      out,
    )

  /** Lint a loaded schema, as a `SchemaValidationReport` in JSON. */
  def lint(handle: Long, optionsJson: String, out: OutputStream): Unit = {
    val sv = view(handle)
    JsonUtil.writeJson(
      reportNode(
        SchemaValidator(using sv).lintProblems,
        None,
        Options.load(optionsJson).inferMessages,
      ),
      out,
    )
  }

  /** Turn an exception into the message that goes into the C error out-param.
    *
    * Lives here rather than in [[LinkMlCApi]] so it can recognize [[BadRequest]] by type. Caller
    * mistakes come through as just their message, since those are already written for a human to
    * read; anything else gets its type attached, because it means a bug in here.
    */
  def describe(t: Throwable): String = t match {
    case BadRequest(reason) => reason
    case _ =>
      val msg = t.getMessage
      if msg eq null then t.getClass.getSimpleName
      // The generators report the caller's mistakes as a plain RuntimeException.
      else if t.getClass eq classOf[RuntimeException] then msg
      else s"${t.getClass.getSimpleName}: $msg"
  }

  // Internals

  private def loaded(
      result: Either[Seq[SchemaIssue], SchemaView],
      runId: Option[String],
      options: LoadOptions,
  ): Loaded =
    result match {
      case Right(sv) =>
        val handle = handleSeq.incrementAndGet()
        schemas.put(handle, sv)
        // Lint straight away, so the report covers errors and warnings too, not just the fatals that
        // would have blocked loading.
        val issues = SchemaValidator(using sv).lintProblems
        new Loaded(handle, report(issues, runId, options.inferMessages))
      case Left(fatals) =>
        new Loaded(0L, report(fatals, runId, options.inferMessages))
    }

  private def view(handle: Long): SchemaView = {
    val sv = schemas.get(handle)
    if (sv eq null) throw BadRequest(s"schema handle $handle is unknown or already closed")
    sv
  }

  private def reportNode(
      issues: Seq[SchemaIssue],
      runId: Option[String],
      inferMessages: Boolean,
  ): Node =
    Codec.codec.encode(
      SchemaValidationReportImpl(
        issues = if inferMessages then issues.map(_.infer()) else issues,
        validationRunId = runId,
      ),
    )

  private def report(
      issues: Seq[SchemaIssue],
      runId: Option[String],
      inferMessages: Boolean,
  ): String =
    JsonUtil.yamlToJson(reportNode(issues, runId, inferMessages))

  private def entry(name: String, value: Node): (Node, Node) = StringNode(name) -> value

  /** A schema importer backed by the caller-supplied filename to YAML map. */
  private final case class ImportMap(map: Map[String, String]) extends StringImporter {
    override def read(path: String): String =
      map.getOrElse(path, throw BadRequest(s"could not read from the import map: $path"))
  }

}
