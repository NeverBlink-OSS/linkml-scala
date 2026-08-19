package eu.neverblink.linkml.nativelib

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.rdf.NTriplesRdfSink
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.tableschema.TableSchemaGenerator
import eu.neverblink.linkml.generator.util.{JsonUtil, PruningMode, StringSink}
import eu.neverblink.linkml.schemaview.{Case, SchemaValidator, SchemaView, StringImporter}
import eu.neverblink.linkml.validation.{Codec, SchemaIssue, SchemaValidationReportImpl}
import org.virtuslab.yaml.{Node, StringNode}

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** The whole LinkML API behind a single JSON-in, JSON-out function.
  *
  * [[LinkMlCApi]] exposes [[call]] to C (and therefore to Python) as `linkml_call`. Keeping the C
  * surface down to one function means the ABI does not change when a generator gains an option:
  * only the JSON inside it does.
  *
  * A request is a JSON object with an `op` field, deserialized into a [[Request]]. A response is a
  * JSON object with an `ok` field, plus `error` when `ok` is false. The recognized ops:
  *
  *   - `{"op": "version"}` -> `{"abiVersion": 1}`
  *   - `{"op": "load", "schema"|"path": "...", "imports": {...}, "inferMessages": true}` ->
  *     `{"handle": 7, "report": {...}}`, with `handle` left out if the schema had fatal problems
  *   - `{"op": "generate", "handle": 7, "generator": "shacl", "options": {...}}` ->
  *     `{"output": "..."}`, or `{"files": {"Foo.scala": "..."}}` for the Scala generator
  *   - `{"op": "lint", "handle": 7, "inferMessages": true}` -> `{"report": {...}}`
  *   - `{"op": "close", "handle": 7}` -> `{}`
  *
  * Loaded schemas live in a table here rather than being handed to the caller as pointers, so a
  * stale or made-up handle is a plain error response instead of a crash.
  */
object LinkMlNativeApi {

  /** Bumped whenever a change to the request or response shape breaks existing callers. */
  final val abiVersion: Int = 1

  private val schemas = new ConcurrentHashMap[java.lang.Long, SchemaView]()

  private val handleSeq = new AtomicLong(0L)

  /** Handle one request. Never throws: everything that goes wrong comes back as an error response.
    */
  def call(request: String): String =
    try {
      val req =
        try readFromString(request)(using Request.codec)
        catch {
          case ex if NonFatal(ex) => throw BadRequest(s"malformed request: ${ex.getMessage}")
        }
      req.op match {
        case "version" => ok(field("abiVersion", numberNode(abiVersion)))
        case "load" => load(req)
        case "generate" => generate(req)
        case "lint" => lint(req)
        case "close" => close(req)
        case other => throw BadRequest(s"unknown op '$other'")
      }
    } catch {
      case ex: BadRequest => error(ex.getMessage)
      case ex if NonFatal(ex) => error(describe(ex))
    }

  /** Last-resort response for something [[call]] could not catch itself, such as a
    * [[StackOverflowError]]. Called from [[LinkMlCApi]], which has its own fallback in case even
    * this fails.
    */
  def fatalResponse(t: Throwable): String = error(describe(t))

  private def load(req: Request): String = {
    val (loaded, runId) = (req.schema, req.path) match {
      case (Some(yaml), _) =>
        (
          SchemaView.loadSchemaViewFromString(yaml, ImportMap(req.imports.getOrElse(Map.empty))),
          None,
        )
      case (None, Some(path)) =>
        // No import map at all means "read imports off the file system", which is what the CLI
        // does. An empty one is still a map, and fails on the first import.
        val loaded = req.imports.fold(SchemaView.loadSchemaViewFromUri(path))(map =>
          SchemaView.loadSchemaViewFromUri(path, ImportMap(map)),
        )
        (loaded, Some(path))
      case (None, None) =>
        throw BadRequest("load needs either a 'schema' (YAML text) or a 'path' field")
    }

    loaded match {
      case Right(sv) =>
        val handle = handleSeq.incrementAndGet()
        schemas.put(handle, sv)
        // Lint straight away, so the report covers errors and warnings too, not just the fatals that
        // would have blocked loading.
        val issues = SchemaValidator(using sv).lintProblems
        ok(
          field("handle", numberNode(handle)),
          field("report", reportNode(issues, runId, req.inferMessages)),
        )
      case Left(fatals) =>
        ok(field("report", reportNode(fatals, runId, req.inferMessages)))
    }
  }

  private def generate(req: Request): String = {
    given SchemaView = view(req)
    val opts = req.options
    req.generator.getOrElse(throw BadRequest("generate needs a 'generator' field")) match {
      case "json-schema" =>
        output(
          JsonSchemaGenerator().serialize(
            opts.open,
            opts.treeRoot,
            treeRootInlineTypeOverride = opts.treeRootInlineType,
          ),
        )

      case "shacl" =>
        output(nTriples(ShaclGenerator().generate(_, opts.open, opts.onlyClassesFromRootSchema)))

      case "rdfs" =>
        output(nTriples(RdfsGenerator().generate(_, opts.onlyClassesFromRootSchema)))

      case "linkml" =>
        output(
          LinkMlGenerator().serialize(
            skipClassDerivation = opts.skipDerivation,
            pruningMode = opts.resolvedPruningMode,
            outputFormat = opts.format.toLowerCase match {
              case "yaml" | "yml" => LinkMlGenerator.OutputFormat.yaml
              case "json" => LinkMlGenerator.OutputFormat.json
              case other => throw BadRequest(s"unknown output format '$other', expected yaml|json")
            },
          ),
        )

      case "table-schema" => output(TableSchemaGenerator().serialize(opts.treeRoot))

      case "graphql" => output(GraphQlGenerator().serialize(opts.resolvedPruningMode))

      case "er-diagram" =>
        output(ErDiagramGenerator().serialize(opts.resolvedPruningMode, opts.optionalMarker))

      case "scala" =>
        files(ScalaGenerator().generate(opts.`package`, opts.generateEmitPrefixes))

      case other => throw BadRequest(s"unknown generator '$other'")
    }
  }

  private def lint(req: Request): String = {
    val sv = view(req)
    ok(
      field(
        "report",
        reportNode(SchemaValidator(using sv).lintProblems, runId = None, req.inferMessages),
      ),
    )
  }

  /** Drop a schema handle. Closing a handle that is already gone is not an error, so a caller can
    * close from a finalizer without tracking whether it already did.
    */
  private def close(req: Request): String = {
    schemas.remove(req.requiredHandle)
    ok()
  }

  private def view(req: Request): SchemaView = {
    val handle = req.requiredHandle
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

  // Response building. The validation report is already a YAML node, so responses are built as
  // nodes too and serialized with the same JSON writer the JS bindings use.

  private def ok(fields: (Node, Node)*): String =
    JsonUtil.yamlToJson(Node.MappingNode((field("ok", trueNode) +: fields)*))

  private def error(message: String): String =
    JsonUtil.yamlToJson(
      Node.MappingNode(field("ok", falseNode), field("error", StringNode(message))),
    )

  private def output(text: String): String = ok(field("output", StringNode(text)))

  private def files(generated: Iterable[(String, String)]): String =
    ok(field("files", Node.MappingNode(generated.map((n, t) => field(n, StringNode(t))).toMap)))

  private def field(name: String, value: Node): (Node, Node) = StringNode(name) -> value

  private def numberNode(value: Long): Node = Node.ScalarNode(value.toString)

  private val trueNode: Node = Node.ScalarNode("true")

  private val falseNode: Node = Node.ScalarNode("false")

  /** Turn an exception into an error message.
    *
    * The generators report things the caller did wrong by throwing a plain `RuntimeException`, and
    * those messages are already written for a human, so they go through as they are. Anything else
    * is a bug in here, and gets its type attached to say so.
    */
  private def describe(t: Throwable): String = {
    val msg = t.getMessage
    if (msg eq null) t.getClass.getSimpleName
    else if (t.getClass eq classOf[RuntimeException]) msg
    else s"${t.getClass.getSimpleName}: $msg"
  }

  /** A schema importer backed by a caller-supplied filename -> YAML map. */
  private final case class ImportMap(map: Map[String, String]) extends StringImporter {
    override def read(path: String): String =
      map.getOrElse(path, throw BadRequest(s"could not read from the import map: $path"))
  }
}

/** A request that is missing a field, or names something that does not exist. Reported back to the
  * caller as an error response.
  */
private final case class BadRequest(reason: String) extends RuntimeException(reason)

/** One request, as sent over the C boundary.
  *
  * Fields that only some ops use are optional or defaulted, so callers send just what they mean. A
  * field the codec does not know about is an error rather than something silently dropped, which
  * turns a typo on the Python side into a message instead of a mysteriously ignored option.
  */
private final case class Request(
    op: String,
    schema: Option[String] = None,
    path: Option[String] = None,
    imports: Option[Map[String, String]] = None,
    inferMessages: Boolean = true,
    handle: Long = 0L,
    generator: Option[String] = None,
    options: GeneratorOptions = GeneratorOptions(),
) {

  /** The schema handle this request works on. Handles start at 1, so 0 means it was left out. */
  def requiredHandle: Long =
    if handle <= 0L then throw BadRequest(s"'${op}' needs a 'handle' field") else handle
}

private object Request {
  given codec: JsonValueCodec[Request] =
    JsonCodecMaker.make(CodecMakerConfig.withSkipUnexpectedFields(false))
}

/** Every generator option there is, in one flat object. Each generator reads the ones it knows and
  * ignores the rest; the Python bindings only ever send the relevant ones.
  */
private final case class GeneratorOptions(
    open: Boolean = false,
    treeRoot: Option[String] = None,
    treeRootInlineType: Option[String] = None,
    onlyClassesFromRootSchema: Boolean = false,
    skipDerivation: Boolean = false,
    pruningMode: String = "skip",
    format: String = "yaml",
    optionalMarker: Boolean = true,
    `package`: String = "eu.neverblink.linkml.metamodel",
    generateEmitPrefixes: Boolean = true,
) {

  /** The pruning mode, with the tree root override folded in. Accepts camel, kebab and snake case
    * alike, as the CLI's `--pruning-mode` does.
    */
  def resolvedPruningMode: PruningMode = {
    val normalized = Case.camelCase(pruningMode)
    if !GeneratorOptions.pruningModes.contains(normalized) then
      throw BadRequest(
        s"unknown pruning mode '$pruningMode', expected one of: " +
          GeneratorOptions.pruningModes.mkString(", "),
      )
    PruningMode(normalized, treeRoot)
  }
}

private object GeneratorOptions {
  private val pruningModes = Seq("treeRoot", "schema", "skip")
}
