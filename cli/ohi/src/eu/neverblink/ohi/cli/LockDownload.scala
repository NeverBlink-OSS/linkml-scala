package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.generated.fileerror.CliFileErrorCode
import eu.neverblink.ohi.cli.generated.projectresult.{LockFileResult, LockFileStatus}
import eu.neverblink.ohi.model.generated.lock.LockedFile

import java.io.OutputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, NoSuchFileException, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.{DigestInputStream, MessageDigest}
import java.util.HexFormat
import scala.collection.mutable
import scala.util.Using
import scala.util.control.NonFatal

private[cli] object LockDownload {
  private case class PlannedFile(url: String, relative: Path, sha256: String)
  private case class PreparedFile(file: PlannedFile, path: Path, verified: Boolean)

  final class Plan private[LockDownload] (
      private[LockDownload] val files: Vector[PreparedFile],
      private[LockDownload] val directory: String,
      private[LockDownload] val managed: Boolean,
  )

  final class InstallationException(
      val cause: CatalogException | CliFileException,
      val files: Seq[LockFileResult],
  ) extends RuntimeException(cause)

  /** Requires validated version-2 files. Checks every target and the managed ignore file without
    * requests or writes. Invalid targets throw [[CliFileException]], so callers can save project
    * records only after successful preflight.
    */
  def prepare(
      files: Seq[LockedFile],
      directory: String,
      managed: Boolean,
  ): Plan = {
    val actualNames = mutable.Map.empty[String, String]
    val prepared = plan(files, directory).map { file =>
      if managed && file.relative.startsWith(".gitignore") then
        throw FileDelivery.failure(
          CliFileErrorCode.OutputConflict,
          "The ontology namespace conflicts with the managed .gitignore file. Use get --to with a dedicated directory.",
          file.url,
          directory,
        )
      inspect(file, directory, actualNames)
    }
    if managed then prepared.headOption.foreach(entry => ignoreExists(entry.file, directory))
    new Plan(prepared, directory, managed)
  }

  /** Creates `*` plus a newline for a nonempty managed plan, preserving an existing regular ignore
    * file. Call after project writes and before downloading. Throws [[CliFileException]] on
    * failure.
    */
  def ensureIgnore(plan: Plan): Unit =
    if plan.managed then
      plan.files.headOption.foreach { entry =>
        if !ignoreExists(entry.file, plan.directory) then
          FileDelivery.save(
            entry.file.url,
            plan.directory,
            Path.of(".gitignore"),
            _ => "*\n".getBytes(UTF_8),
          )
      }

  /** Executes a prepared plan using its exact URLs. [[InstallationException]] preserves the
    * original failure and completed outputs. The caller owns project and ignore-file writes before
    * this step.
    */
  def install(plan: Plan, fetch: String => Array[Byte]): Seq[LockFileResult] = {
    var completed = Vector.empty[LockFileResult]
    try {
      plan.files.foreach { entry =>
        val status = if entry.verified then LockFileStatus.Verified
        else {
          FileDelivery.save(
            entry.file.url,
            plan.directory,
            entry.file.relative,
            url => {
              val bytes = fetch(url)
              verify(
                entry.file,
                plan.directory,
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
      completed
    } catch {
      case cause: (CatalogException | CliFileException) =>
        throw new InstallationException(cause, completed)
    }
  }

  private def plan(files: Seq[LockedFile], directory: String): Vector[PlannedFile] = {
    val names = mutable.LinkedHashMap.empty[Path, PlannedFile]
    files.foreach { file =>
      val url = file.url.original
      val relative = HubArtifactPath.relative(file.baseUrl.original, url, file.format, directory)
      val expected = file.sha256.get
      names.get(relative) match {
        case Some(first) =>
          if first.sha256 != expected then
            throw FileDelivery.failure(
              CliFileErrorCode.OutputConflict,
              s"Lock entries '${first.url}' and '$url' require different hashes for '$relative'. Regenerate the lock with distinct outputs or matching hashes.",
              url,
              directory,
            )
        case None =>
          names(relative) = PlannedFile(url, relative, expected)
      }
    }
    names.values.toVector
  }

  private def inspect(
      file: PlannedFile,
      directory: String,
      actualNames: mutable.Map[String, String],
  ): PreparedFile = {
    val path = FileDelivery.resolve(file.url, directory, file.relative)
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
            s"Outputs '$first' and '${file.relative}' refer to one local entry. Choose a destination that supports both paths.",
            file.url,
            directory,
            Some(path),
          )
        }
        actualNames(actualName) = file.relative.toString
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

  private def ignoreExists(file: PlannedFile, directory: String): Boolean = {
    val path = FileDelivery.resolve(file.url, directory, Path.of(".gitignore"))
    val attributes = reading(file, directory, path) {
      try Some(Files.readAttributes(path, classOf[BasicFileAttributes], NOFOLLOW_LINKS))
      catch { case _: NoSuchFileException => None }
    }
    if attributes.exists(value => !value.isRegularFile) then
      throw FileDelivery.exists(file.url, directory, path)
    attributes.nonEmpty
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
