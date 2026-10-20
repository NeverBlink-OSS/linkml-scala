package eu.neverblink.ohi.model

import com.networknt.schema.{ExecutionConfig, ExecutionContext, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.runtime.{Uri, stringify}
import eu.neverblink.ohi.model.generated.lock.*
import eu.neverblink.ohi.model.generated.lockerror.{LockErrorCode, LockOperation}
import scala.jdk.CollectionConverters.*
import tools.jackson.core.JacksonException
import tools.jackson.databind.{DeserializationFeature, JsonNode}
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

/** Validates complete supplied file locks and preserves exact URL strings and array order. */
object LockJson {
  private val mapper = JsonMapper.builder()
    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build()
  private val schema = SchemaRegistry.withDialect(Dialects.getDraft202012).getSchema(
    mapper.readTree(getClass.getResourceAsStream("/lock.schema.json")),
  )
  private val validationConfig = ExecutionConfig.builder().formatAssertionsEnabled(true).build()
  private val formats = Seq[Format](
    Format.OwlTurtle,
    Format.OwlRdfxml,
    Format.RdfsTurtle,
    Format.LinkmlYaml,
    Format.OssieYaml,
  ).map(value => stringify(value) -> value).toMap

  /** Decodes and validates JSON. Throws [[LockException]] for invalid records or unsupported
    * revisions.
    */
  def decode(text: String): Lock = decode(text, LockOperation.Decode, None)

  /** Validates and encodes the supplied record. Throws [[LockException]] for invalid records or
    * unsupported revisions.
    */
  def encode(lock: Lock): String = encode(lock, LockOperation.Encode, None)

  private[model] def decode(text: String, operation: LockOperation, path: Option[String]): Lock = {
    val data = try mapper.readTree(text)
    catch {
      case cause: JacksonException =>
        throw LockException.failure(
          LockErrorCode.InvalidLock,
          operation,
          path,
          s"Cannot parse the lock: ${cause.getMessage}. Supply one complete JSON record.",
          Some(cause),
        )
    }
    validate(data, operation, path)
    Lock(
      formatVersion = data.get("format_version").intValue(),
      selections = data.get("selections").iterator().asScala.map { selection =>
        ArtifactSelectionImpl(
          baseUrl = Uri(selection.get("base_url").stringValue()),
          url = Uri(selection.get("url").stringValue()),
        )
      }.toSeq,
      files = data.get("files").iterator().asScala.map { file =>
        LockedFile(
          baseUrl = Uri(file.get("base_url").stringValue()),
          url = Uri(file.get("url").stringValue()),
          format = formats(file.get("format").stringValue()),
        )
      }.toSeq,
    )
  }

  private[model] def encode(lock: Lock, operation: LockOperation, path: Option[String]): String = {
    val data = mapper.createObjectNode().put("format_version", lock.formatVersion)
    val selections = data.putArray("selections")
    lock.selections.foreach(value => selections.add(selectionNode(value)))
    val files = data.putArray("files")
    lock.files.foreach(value =>
      files.add(selectionNode(value).put("format", stringify(value.format))),
    )
    validate(data, operation, path)
    mapper.writeValueAsString(data)
  }

  private def selectionNode(selection: ArtifactSelection): ObjectNode =
    mapper.createObjectNode()
      .put("base_url", selection.baseUrl.original)
      .put("url", selection.url.original)

  private def validate(data: JsonNode, operation: LockOperation, path: Option[String]): Unit = {
    val revision = data.path("format_version")
    if revision.isNumber && revision.decimalValue().stripTrailingZeros().scale() <= 0 &&
      revision.decimalValue().compareTo(java.math.BigDecimal.ONE) != 0
    then
      throw LockException.failure(
        LockErrorCode.UnsupportedVersion,
        operation,
        path,
        s"Lock revision $revision is unsupported. Supply revision 1 or use a reader that supports this revision.",
      )
    val errors = schema.validate(
      data,
      (context: ExecutionContext) => context.setExecutionConfig(validationConfig),
    )
    if !errors.isEmpty then
      throw LockException.failure(
        LockErrorCode.InvalidLock,
        operation,
        path,
        s"Invalid lock: ${errors.asScala.mkString(", ")}. Correct the supplied lock record.",
      )
    def pairs(field: String): Seq[(String, String)] =
      data.get(field).iterator().asScala.map(value =>
        (value.get("base_url").stringValue(), value.get("url").stringValue()),
      ).toSeq
    val selections = pairs("selections")
    val files = pairs("files")
    val problem =
      if selections.distinct.size != selections.size then
        Some("Each selection pair must occur once")
      else if files.distinct.size != files.size then Some("Each file pair must occur once")
      else if !selections.forall(files.toSet.contains) then
        Some("Every selection must have a matching file pair")
      else None
    problem.foreach(message =>
      throw LockException.failure(
        LockErrorCode.InvalidLock,
        operation,
        path,
        s"$message. Correct the supplied lock record.",
      ),
    )
  }
}
