package eu.neverblink.linkml.nativelib

import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.rdf.NTriplesRdfSink
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator
import eu.neverblink.linkml.generator.util.{JsonUtil, StringSink}
import eu.neverblink.linkml.schemaview.{SchemaValidator, SchemaView, StringImporter}
import eu.neverblink.linkml.validation.{Codec, SchemaIssue, SchemaValidationReportImpl}
import org.virtuslab.yaml.{Node, StringNode}

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
    */
  final val abiVersion: Int = 1

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

  // Generators returning a single document

  def jsonSchema(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    JsonSchemaGenerator().serialize(Options.jsonSchema(optionsJson))
  }

  def shacl(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    nTriples(ShaclGenerator().generate(_, Options.shacl(optionsJson)))
  }

  def rdfs(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    nTriples(RdfsGenerator().generate(_, Options.rdfs(optionsJson)))
  }

  def linkml(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    LinkMlGenerator().serialize(Options.linkml(optionsJson))
  }

  def tableSchema(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    TableSchemaGenerator().serialize(Options.tableSchema(optionsJson))
  }

  def graphQl(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    GraphQlGenerator().serialize(Options.graphQl(optionsJson))
  }

  def erDiagram(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    ErDiagramGenerator().serialize(Options.erDiagram(optionsJson))
  }

  // Results that are structured, and so come back as JSON

  /** Generate Scala sources, as a JSON object mapping filename to source. */
  def scalaFiles(handle: Long, optionsJson: String): String = {
    given SchemaView = view(handle)
    val generated = ScalaGenerator().generate(Options.scala(optionsJson))
    JsonUtil.yamlToJson(
      Node.MappingNode(generated.map((name, text) => entry(name, StringNode(text))).toMap),
    )
  }

  /** Lint a loaded schema, as a `SchemaValidationReport` in JSON. */
  def lint(handle: Long, optionsJson: String): String = {
    val sv = view(handle)
    report(SchemaValidator(using sv).lintProblems, None, Options.load(optionsJson).inferMessages)
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

  /** Run an RDF generator into an N-Triples string. Turtle is not available here: it would pull in
    * RDF4J, which the shared library deliberately leaves out.
    */
  private def nTriples(generate: NTriplesRdfSink => Unit): String = {
    val sink = new StringSink
    generate(NTriplesRdfSink(sink))
    sink.result
  }

  private def report(
      issues: Seq[SchemaIssue],
      runId: Option[String],
      inferMessages: Boolean,
  ): String =
    JsonUtil.yamlToJson(
      Codec.codec.encode(
        SchemaValidationReportImpl(
          issues = if inferMessages then issues.map(_.infer()) else issues,
          validationRunId = runId,
        ),
      ),
    )

  private def entry(name: String, value: Node): (Node, Node) = StringNode(name) -> value

  /** A schema importer backed by the caller-supplied filename to YAML map. */
  private final case class ImportMap(map: Map[String, String]) extends StringImporter {
    override def read(path: String): String =
      map.getOrElse(path, throw BadRequest(s"could not read from the import map: $path"))
  }

}
