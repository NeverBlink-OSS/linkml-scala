package eu.neverblink.ohi.model

import eu.neverblink.ohi.model.generated.lock.Lock
import eu.neverblink.ohi.model.generated.lockerror.{LockErrorCode, LockOperation}
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.StandardCopyOption.{ATOMIC_MOVE, REPLACE_EXISTING}
import scala.util.Using
import scala.util.control.NonFatal

/** Reads explicit lock files and atomically replaces complete records. */
object LockFiles {

  /** Reads and validates UTF-8 JSON without a BOM. Throws [[LockException]] for read failures,
    * invalid records or unsupported revisions.
    */
  def read(path: Path): Lock = {
    val text = try Files.readString(path, UTF_8)
    catch {
      case cause: CharacterCodingException =>
        throw LockException.failure(
          LockErrorCode.InvalidLock,
          LockOperation.Read,
          Some(path.toString),
          s"Cannot decode '$path' as UTF-8. Save the lock as UTF-8 JSON without a BOM.",
          Some(cause),
        )
      case NonFatal(cause) =>
        throw LockException.failure(
          LockErrorCode.StorageError,
          LockOperation.Read,
          Some(path.toString),
          s"Cannot read '$path': ${cause.getMessage}. Check the file path and read permissions.",
          Some(cause),
        )
    }
    LockJson.decode(text, LockOperation.Read, Some(path.toString))
  }

  /** Validates before staging and atomically replaces the destination entry, including a symlink.
    * The parent must exist. On failure, preserves the previous destination and throws
    * [[LockException]], reporting any temporary file that could not be removed.
    */
  def write(path: Path, lock: Lock): Unit = write(
    path,
    lock,
    stage => Files.newOutputStream(stage),
    (stage, destination) => {
      Files.move(stage, destination, ATOMIC_MOVE, REPLACE_EXISTING)
      ()
    },
    stage => Files.delete(stage),
  )

  private[model] def write(
      path: Path,
      lock: Lock,
      open: Path => OutputStream,
      replace: (Path, Path) => Unit,
      delete: Path => Unit,
  ): Unit = {
    val bytes = LockJson.encode(lock, LockOperation.Write, Some(path.toString)).getBytes(UTF_8)
    val stage = try Files.createTempFile(path.toAbsolutePath.getParent, ".ohi-lock-", ".tmp")
    catch {
      case NonFatal(cause) =>
        throw LockException.failure(
          LockErrorCode.StorageError,
          LockOperation.Write,
          Some(path.toString),
          s"Cannot stage '$path': ${cause.getMessage}. Check that its parent exists and is writable.",
          Some(cause),
        )
    }
    try {
      Using.resource(open(stage))(_.write(bytes))
      replace(stage, path)
    } catch {
      case NonFatal(cause) =>
        val correction = try {
          delete(stage)
          "Check write permissions, available space and atomic replacement support."
        } catch {
          case NonFatal(cleanup) =>
            cause.addSuppressed(cleanup)
            s"Remove the temporary file '$stage' before retrying."
        }
        throw LockException.failure(
          LockErrorCode.StorageError,
          LockOperation.Write,
          Some(path.toString),
          s"Cannot replace '$path': ${cause.getMessage}. $correction",
          Some(cause),
        )
    }
  }
}
