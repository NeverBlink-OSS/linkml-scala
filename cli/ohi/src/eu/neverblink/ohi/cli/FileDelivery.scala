package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.fileerror.{CliFileErrorCode, CliFileError}

import java.io.OutputStream
import java.nio.file.{FileAlreadyExistsException, Files, NoSuchFileException, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.{CREATE_NEW, WRITE}
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal

private[cli] object FileDelivery {

  /** Resolves a relative output beneath the selected root without writing. Rejects symlinks and
    * non-directory ancestors from that root downward through [[CliFileException]].
    */
  def resolve(url: String, directory: String, relative: Path): Path = {
    val destination = try Path.of(directory).toAbsolutePath.normalize()
    catch {
      case NonFatal(cause) =>
        throw failure(
          CliFileErrorCode.InvalidDestination,
          s"Cannot use destination directory '$directory'. Choose a valid directory path.",
          url,
          directory,
          cause = Some(cause),
        )
    }
    val path = destination.resolve(relative).normalize()
    if relative.isAbsolute || !path.startsWith(destination) || path == destination then
      throw failure(
        CliFileErrorCode.UnsupportedFilename,
        s"Output '$relative' is outside destination '$directory'. Choose a relative artifact path within the destination.",
        url,
        directory,
        Some(path),
      )
    checkParents(url, directory, destination, path)
    path
  }

  private def checkParents(url: String, directory: String, root: Path, path: Path): Unit = {
    val parents = root.relativize(path.getParent).iterator().asScala.scanLeft(root)(_.resolve(_))
    parents.foreach { parent =>
      val attributes = try
        Some(Files.readAttributes(parent, classOf[BasicFileAttributes], NOFOLLOW_LINKS))
      catch {
        case _: NoSuchFileException => None
        case NonFatal(cause) =>
          throw failure(
            CliFileErrorCode.InvalidDestination,
            s"Cannot inspect destination ancestor '$parent': ${cause.getMessage}. Check its parent paths and permissions.",
            url,
            directory,
            Some(path),
            cause = Some(cause),
          )
      }
      if attributes.exists(value => !value.isDirectory) then
        throw failure(
          CliFileErrorCode.InvalidDestination,
          s"Destination ancestor '$parent' is not a regular directory. Remove the symlink or non-directory entry before retrying.",
          url,
          directory,
          Some(path),
        )
    }
  }

  /** Fetches before creating files or directories. Never overwrites. Reports `partial_path` if
    * failed-write cleanup fails. Fetch failures propagate.
    */
  def save(
      url: String,
      directory: String,
      relative: Path,
      fetch: String => Array[Byte],
  ): Unit =
    save(
      url,
      directory,
      relative,
      fetch,
      path => Files.newOutputStream(path, CREATE_NEW, WRITE),
      path => Files.delete(path),
    )

  private[cli] def save(
      url: String,
      directory: String,
      relative: Path,
      fetch: String => Array[Byte],
      open: Path => OutputStream,
      delete: Path => Unit,
  ): Unit = {
    val path = resolve(url, directory, relative)
    if Files.exists(path, NOFOLLOW_LINKS) then throw exists(url, directory, path)
    val bytes = fetch(url)
    // Fetching can take time. Recheck ancestors before creating directories or opening the file.
    resolve(url, directory, relative)
    try Files.createDirectories(path.getParent)
    catch {
      case NonFatal(cause) =>
        throw failure(
          CliFileErrorCode.InvalidDestination,
          s"Cannot create destination directory '$directory': ${cause.getMessage}. Check its parent paths and permissions.",
          url,
          directory,
          Some(path),
          cause = Some(cause),
        )
    }
    val stream = try open(path)
    catch {
      case cause: FileAlreadyExistsException => throw exists(url, directory, path, Some(cause))
      case NonFatal(cause) =>
        throw failure(
          CliFileErrorCode.FileWriteFailed,
          s"Cannot create '$path': ${cause.getMessage}. Check the destination permissions and available space.",
          url,
          directory,
          Some(path),
          cause = Some(cause),
        )
    }
    try Using.resource(stream)(_.write(bytes))
    catch {
      case NonFatal(cause) =>
        val partialPath = try {
          delete(path)
          None
        } catch {
          case NonFatal(cleanup) =>
            cause.addSuppressed(cleanup)
            Some(path)
        }
        val correction = partialPath.fold("Check the destination permissions and available space.")(
          partial => s"Remove the incomplete file '$partial' before retrying.",
        )
        throw failure(
          CliFileErrorCode.FileWriteFailed,
          s"Cannot finish writing '$path': ${cause.getMessage}. $correction",
          url,
          directory,
          Some(path),
          partialPath,
          Some(cause),
        )
    }
  }

  private[cli] def exists(
      url: String,
      directory: String,
      path: Path,
      cause: Option[Throwable] = None,
  ): CliFileException = failure(
    CliFileErrorCode.DestinationExists,
    s"Destination '$path' already exists. Choose another directory or move the existing entry.",
    url,
    directory,
    Some(path),
    cause = cause,
  )

  private[cli] def failure(
      code: CliFileErrorCode,
      message: String,
      url: String,
      directory: String,
      path: Option[Path] = None,
      partialPath: Option[Path] = None,
      cause: Option[Throwable] = None,
  ): CliFileException = new CliFileException(
    CliFileError(
      code = code,
      message = message,
      targetUrl = url,
      directory = directory,
      path = path.map(_.toString),
      partialPath = partialPath.map(_.toString),
    ),
    cause,
  )
}
