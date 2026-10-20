package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.cli.generated.delivery.DeliveryResult
import eu.neverblink.ohi.cli.generated.fileerror.{CliFileErrorCode, CliFileError}

import java.io.OutputStream
import java.net.{URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{FileAlreadyExistsException, Files, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.{CREATE_NEW, WRITE}
import scala.util.Using
import scala.util.control.NonFatal

private[cli] object FileDelivery {

  /** Returns an absolute normalized target, rejecting unusable basenames and existing entries.
    * Performs no writes or reservation. Throws [[CliFileException]] on preflight failure.
    */
  def target(url: String, directory: String): Path = {
    val filename = try {
      val rawPath = Option(URI.create(url).getRawPath).getOrElse("")
      val segment = rawPath.substring(rawPath.lastIndexOf('/') + 1)
      // URLDecoder handles forms, so protect a literal '+' from becoming a space.
      val name = URLDecoder.decode(segment.replace("+", "%2B"), UTF_8)
      val path = Path.of(name)
      if name.isEmpty || name == "." || name == ".." ||
        name.exists(c => c == '/' || c == '\\' || c == '\u0000') ||
        path.getRoot != null || path.getNameCount != 1 || path.getFileName.toString != name
      then throw new IllegalArgumentException("The URL does not name one local file")
      path
    } catch {
      case NonFatal(cause) =>
        throw failure(
          CliFileErrorCode.UnsupportedFilename,
          s"Cannot derive a local filename from '$url'. Choose a URL ending in a usable filename.",
          url,
          directory,
          cause = Some(cause),
        )
    }
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
    val path = destination.resolve(filename)
    if Files.exists(destination, NOFOLLOW_LINKS) && !Files.isDirectory(destination) then
      throw failure(
        CliFileErrorCode.InvalidDestination,
        s"Destination '$directory' is not a directory. Choose another directory.",
        url,
        directory,
        Some(path),
      )
    if Files.exists(path, NOFOLLOW_LINKS) then throw exists(url, directory, path)
    path
  }

  /** Fetches before creating directories and writes exclusively, returning after the file closes.
    * On write or close failure, deletes the new file or reports its path in [[CliFileException]].
    * Fetch failures propagate unchanged.
    */
  def save(url: String, directory: String, fetch: String => Array[Byte]): DeliveryResult =
    save(
      url,
      directory,
      fetch,
      path => Files.newOutputStream(path, CREATE_NEW, WRITE),
      path => Files.delete(path),
    )

  private[cli] def save(
      url: String,
      directory: String,
      fetch: String => Array[Byte],
      open: Path => OutputStream,
      delete: Path => Unit,
  ): DeliveryResult = {
    val path = target(url, directory)
    val bytes = fetch(url)
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
    DeliveryResult(url = Uri(url), path = path.toString)
  }

  private def exists(
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

  private def failure(
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
      url = url,
      directory = directory,
      path = path.map(_.toString),
      partialPath = partialPath.map(_.toString),
    ),
    cause,
  )
}
