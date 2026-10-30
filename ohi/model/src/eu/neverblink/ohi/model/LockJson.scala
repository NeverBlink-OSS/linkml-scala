package eu.neverblink.ohi.model

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.model.generated.lock.*
import eu.neverblink.ohi.model.generated.lockerror.{LockErrorCode, LockOperation}
import org.virtuslab.yaml.{Node, Tag}

/** Reads and writes complete supplied file locks with the generated classes, preserving exact URL
  * strings and array order.
  */
object LockJson {
  private val codec: LinkmlYamlCodec[Lock] = LinkmlYamlCodec.derived

  /** Decodes JSON. Throws [[LockException]] for invalid records or unsupported revisions. */
  def decode(text: String): Lock = decode(text, LockOperation.Decode, None)

  /** Encodes the supplied record. Throws [[LockException]] for invalid records or unsupported
    * revisions.
    */
  def encode(lock: Lock): String = encode(lock, LockOperation.Encode, None)

  private[model] def decode(text: String, operation: LockOperation, path: Option[String]): Lock = {
    val node = try ModelJson.parse(text)
    catch {
      case cause: IllegalArgumentException =>
        throw invalid(operation, path, s"Cannot parse the lock: ${cause.getMessage}", Some(cause))
    }
    // Report a revision this reader does not know before the fields that depend on it.
    node match {
      case mapping: Node.MappingNode =>
        LinkmlYamlCodec.getFields(mapping).get("format_version") match {
          case revision: Node.ScalarNode if revision.tag eq Tag.int =>
            revision.value.toIntOption match {
              case Some(value) => checkRevision(value, operation, path)
              case None => throw unsupported(revision.value, operation, path)
            }
          case _ =>
        }
      case _ =>
    }
    val lock = try codec.decode(node)
    catch {
      case cause: Exception => throw invalid(operation, path, cause.getMessage, Some(cause))
    }
    checkRevision(lock.formatVersion, operation, path)
    check(lock, operation, path)
    lock
  }

  private[model] def encode(lock: Lock, operation: LockOperation, path: Option[String]): String = {
    checkRevision(lock.formatVersion, operation, path)
    check(lock, operation, path)
    ModelJson.encode(codec, lock)
  }

  private def checkRevision(revision: Int, operation: LockOperation, path: Option[String]): Unit =
    if revision != 1 && revision != 2 then throw unsupported(revision.toString, operation, path)

  private def unsupported(revision: String, operation: LockOperation, path: Option[String]) =
    LockException.failure(
      LockErrorCode.UnsupportedVersion,
      operation,
      path,
      s"Lock revision $revision is unsupported. Supply revision 1 or 2, or use a reader that supports this revision.",
    )

  private def invalid(
      operation: LockOperation,
      path: Option[String],
      message: String,
      cause: Option[Throwable] = None,
  ) = LockException.failure(
    LockErrorCode.InvalidLock,
    operation,
    path,
    s"Invalid lock: $message. Correct the supplied lock record.",
    cause,
  )

  private def check(lock: Lock, operation: LockOperation, path: Option[String]): Unit = {
    def pairs(values: Seq[ArtifactSelection]): Seq[(String, String)] =
      values.map(value => (value.baseUrl.original, value.url.original))
    val selections = pairs(lock.selections)
    val files = pairs(lock.files)
    val problem =
      if lock.files.exists(_.sha256.isDefined != (lock.formatVersion == 2)) then
        Some("revision 1 files must omit sha256 and revision 2 files must supply it")
      else if selections.distinct.size != selections.size then
        Some("each selection pair must occur once")
      else if files.distinct.size != files.size then Some("each file pair must occur once")
      else if !selections.forall(files.toSet.contains) then
        Some("every selection must have a matching file pair")
      else None
    problem.foreach(message => throw invalid(operation, path, message))
  }
}
