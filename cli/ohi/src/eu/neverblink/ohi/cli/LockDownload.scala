package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.generated.fileerror.CliFileErrorCode
import eu.neverblink.ohi.cli.generated.lockdownload.{
  LockDownloadError,
  LockDownloadResult,
  LockFileResult,
  LockFileStatus,
}
import eu.neverblink.ohi.model.generated.lock.LockedFile

import java.io.OutputStream
import java.nio.file.{Files, NoSuchFileException, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.{DigestInputStream, MessageDigest}
import java.util.HexFormat
import scala.collection.mutable
import scala.util.Using
import scala.util.control.NonFatal

private[cli] object LockDownload {
  private case class PlannedFile(url: String, name: String, sha256: String)
  private case class PreparedFile(file: PlannedFile, path: Path, verified: Boolean)

  /** Requires validated version-2 files. Failures retain and report completed outputs.
    */
  def download(
      files: Seq[LockedFile],
      directory: String,
      fetch: String => Array[Byte],
  ): Either[LockDownloadError, LockDownloadResult] = {
    var completed = Vector.empty[LockFileResult]
    try {
      val actualNames = mutable.Map.empty[String, String]
      val prepared = plan(files, directory).map { file =>
        prepare(file, directory, actualNames)
      }
      prepared.foreach { entry =>
        val status = if entry.verified then LockFileStatus.Verified
        else {
          FileDelivery.save(
            entry.file.url,
            directory,
            url => {
              val bytes = fetch(url)
              verify(
                entry.file,
                directory,
                entry.path,
                sha256(bytes),
                "Correct the lock or remote file before retrying.",
              )
              bytes
            },
          )
          LockFileStatus.Downloaded
        }
        completed :+= LockFileResult(entry.path.toString, status, Uri(entry.file.url))
      }
      Right(LockDownloadResult(completed))
    } catch {
      case cause: (CatalogException | CliFileException) =>
        Left(LockDownloadOutput.failure(completed, cause))
    }
  }

  private def plan(files: Seq[LockedFile], directory: String): Vector[PlannedFile] = {
    val names = mutable.LinkedHashMap.empty[String, PlannedFile]
    files.foreach { file =>
      val url = file.url.original
      val name = FileDelivery.filename(url, directory)
      val expected = file.sha256.get
      val asciiName = name.map(c => if c >= 'A' && c <= 'Z' then (c + 32).toChar else c)
      names.get(asciiName) match {
        case Some(first) =>
          if first.name != name then
            throw FileDelivery.failure(
              CliFileErrorCode.OutputConflict,
              s"Filenames '${first.name}' and '$name' differ only by ASCII letter case. Regenerate the lock with distinct filenames.",
              url,
              directory,
            )
          if first.sha256 != expected then
            throw FileDelivery.failure(
              CliFileErrorCode.OutputConflict,
              s"Lock entries '${first.url}' and '$url' require different hashes for '$name'. Regenerate the lock with distinct filenames or matching hashes.",
              url,
              directory,
            )
        case None =>
          names(asciiName) = PlannedFile(url, name, expected)
      }
    }
    names.values.toVector
  }

  private def prepare(
      file: PlannedFile,
      directory: String,
      actualNames: mutable.Map[String, String],
  ): PreparedFile = {
    val path = FileDelivery.resolve(file.url, directory, file.name)
    val attributes = reading(file, directory, path) {
      try Some(Files.readAttributes(path, classOf[BasicFileAttributes], NOFOLLOW_LINKS))
      catch { case _: NoSuchFileException => None }
    }
    attributes match {
      case None => PreparedFile(file, path, verified = false)
      case Some(value) =>
        if !value.isRegularFile then throw FileDelivery.exists(file.url, directory, path)
        val actualName = reading(file, directory, path)(path.toRealPath().toString)
        actualNames.get(actualName).foreach { first =>
          throw FileDelivery.failure(
            CliFileErrorCode.OutputConflict,
            s"Filenames '$first' and '${file.name}' refer to one local entry. Choose a destination that supports both filenames.",
            file.url,
            directory,
            Some(path),
          )
        }
        actualNames(actualName) = file.name
        verify(
          file,
          directory,
          path,
          reading(file, directory, path)(sha256(path)),
          "Move or remove the local file before retrying.",
        )
        PreparedFile(file, path, verified = true)
    }
  }

  private def reading[A](file: PlannedFile, directory: String, path: Path)(read: => A): A =
    try read
    catch {
      case NonFatal(cause) =>
        throw FileDelivery.failure(
          CliFileErrorCode.FileReadFailed,
          s"Cannot verify '$path': ${cause.getMessage}. Check its permissions and retry.",
          file.url,
          directory,
          Some(path),
          cause = Some(cause),
        )
    }

  private def verify(
      file: PlannedFile,
      directory: String,
      path: Path,
      observed: String,
      correction: String,
  ): Unit =
    if observed != file.sha256 then
      throw FileDelivery.failure(
        CliFileErrorCode.HashMismatch,
        s"SHA-256 mismatch for '$path': expected ${file.sha256}, observed $observed. $correction",
        file.url,
        directory,
        Some(path),
      )

  private def sha256(bytes: Array[Byte]): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  private def sha256(path: Path): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    Using.resource(new DigestInputStream(Files.newInputStream(path, NOFOLLOW_LINKS), digest)) {
      _.transferTo(OutputStream.nullOutputStream())
    }
    HexFormat.of().formatHex(digest.digest())
  }
}
