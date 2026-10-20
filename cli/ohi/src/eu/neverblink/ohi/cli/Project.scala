package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.declaration.{ArtifactSelection, Declaration}
import eu.neverblink.ohi.cli.generated.delivery.DeliveryResult
import eu.neverblink.ohi.cli.generated.projecterror.{
  ProjectError,
  ProjectErrorCode,
  ProjectOperation,
}
import eu.neverblink.ohi.cli.generated.projectresult
import eu.neverblink.ohi.model.{LockException, LockFiles, LockJson}
import eu.neverblink.ohi.model.generated.Publication
import eu.neverblink.ohi.model.generated.lock
import eu.neverblink.ohi.model.generated.lockerror.LockOperation
import eu.neverblink.linkml.runtime.Uri

import java.nio.file.{Files, NoSuchFileException, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.Locale
import scala.util.Try
import scala.util.control.NonFatal

/** The Ohi project in `directory`. Each public method except `failure` runs one project command.
  * Use a new instance per command, because `failure` reports the files this instance wrote.
  */
private[cli] final class Project(
    val directory: Path,
    val operation: ProjectOperation,
    createDeclaration: (Path, Declaration) => Unit = DeclarationFiles.create,
    writeDeclaration: (Path, Declaration) => Unit = DeclarationFiles.write,
    writeLock: (Path, lock.Lock) => Unit = LockFiles.write,
    resolveProbe: Path => Path = _.toRealPath(),
    deleteProbe: Path => Unit = path => Files.delete(path),
) {
  private val declarationPath = directory.resolve("ohi.yaml")
  private val lockPath = directory.resolve("ohi.lock.json")
  private var declarationChanged = false
  private var lockChanged = false
  private var download: Option[(String, String)] = None

  def init(createParents: Boolean): projectresult.ProjectResult = {
    if createParents then
      storage(s"Cannot create project directory '$directory'") {
        Files.createDirectories(directory)
      }
    requireDirectory()
    refuseSymlink(declarationPath)
    if exists(declarationPath) then DeclarationFiles.read(declarationPath)
    else {
      createDeclaration(declarationPath, Declaration(1, Seq.empty))
      declarationChanged = true
    }
    result()
  }

  def add(
      baseUrl: String,
      url: String,
      destination: String,
      inspect: String => Publication,
      fetch: String => Array[Byte],
  ): projectresult.ProjectResult = {
    download = Some(url -> destination)
    val previous = read(allowMissing = true)
    val target = FileDelivery.target(url, destination)
    val canonicalTarget = canonical(target)
    if Seq(declarationPath, lockPath).exists(path => conflicts(canonicalTarget, path)) then
      throw new ProjectException(
        ProjectErrorCode.InvalidProject,
        s"Download '$target' conflicts with a project record. Choose another download directory.",
      )
    val pair = ArtifactSelection(Uri(baseUrl), Uri(url))
    val changed = !exists(declarationPath) || !previous.selections.contains(pair)
    val declaration =
      if previous.selections.contains(pair) then previous
      else previous.copy(selections = previous.selections :+ pair)
    // Validate caller input before inspection. Existing declaration input was already validated.
    if changed then DeclarationYaml.encode(declaration)
    val complete = build(declaration, inspect)
    if changed then {
      writeDeclaration(declarationPath, declaration)
      declarationChanged = true
    }
    writeLock(lockPath, complete)
    lockChanged = true
    result(Some(FileDelivery.save(url, destination, fetch)))
  }

  def refresh(inspect: String => Publication): projectresult.ProjectResult = {
    val declaration = read(allowMissing = false)
    val complete = build(declaration, inspect)
    writeLock(lockPath, complete)
    lockChanged = true
    result()
  }

  /** Converts `cause` into this command's error record.
    *
    * @return
    *   the error, stating which project files this command wrote and the commands that complete the
    *   interrupted work
    */
  def failure(cause: Throwable): ProjectError = {
    def quoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"
    val get = download.map { (url, destination) =>
      s"ohi get ${quoted(url)} --to ${quoted(destination)}"
    }
    val lockWriteFailed = cause match {
      case failure: LockException => failure.error.operation == LockOperation.Write
      case _ => false
    }
    val recovery =
      if lockChanged then s"Correct the reported cause, then run ${get.get}."
      else if declarationChanged || lockWriteFailed || operation == ProjectOperation.Lock then
        s"Lock refresh did not complete. Correct the reported cause, then run ohi lock --project ${quoted(directory.toString)}" +
          get.fold(".")(command => s", then $command.")
      else s"Correct the reported cause and rerun ohi ${operation.toString.toLowerCase}."
    ProjectOutput.failure(
      operation,
      directory.toString,
      declarationChanged,
      lockChanged,
      cause,
      recovery,
    )
  }

  private def read(allowMissing: Boolean): Declaration = {
    requireDirectory()
    refuseSymlink(declarationPath)
    refuseSymlink(lockPath)
    if allowMissing && !exists(declarationPath) && exists(lockPath) then
      throw new ProjectException(
        ProjectErrorCode.InvalidProject,
        s"Lock '$lockPath' has no declaration. Restore or author '$declarationPath' before continuing.",
      )
    if !allowMissing && !exists(declarationPath) then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        s"Declaration '$declarationPath' is missing. Restore or author it before refreshing the lock.",
      )
    val declaration =
      if allowMissing && !exists(declarationPath) then Declaration(1, Seq.empty)
      else DeclarationFiles.read(declarationPath)
    if exists(lockPath) then LockFiles.read(lockPath)
    declaration
  }

  private def build(declaration: Declaration, inspect: String => Publication): lock.Lock = {
    val complete = ProjectLock.build(
      declaration.selections.map(value => lock.ArtifactSelectionImpl(value.baseUrl, value.url)),
      inspect,
    )
    LockJson.encode(complete)
    complete
  }

  private def result(delivery: Option[DeliveryResult] = None): projectresult.ProjectResult =
    projectresult.ProjectResult(
      operation = operation match {
        case ProjectOperation.Init => projectresult.ProjectOperation.Init
        case ProjectOperation.Add => projectresult.ProjectOperation.Add
        case ProjectOperation.Lock => projectresult.ProjectOperation.Lock
      },
      projectDirectory = directory.toString,
      declarationChanged = declarationChanged,
      lockChanged = lockChanged,
      delivery = delivery.map(value => projectresult.DeliveryResult(value.path, value.url)),
    )

  private def exists(path: Path): Boolean = Files.exists(path, NOFOLLOW_LINKS)

  private def requireDirectory(): Unit =
    if !Files.isDirectory(directory) then
      throw new ProjectException(
        ProjectErrorCode.InvalidProject,
        s"Project '$directory' is not an existing directory. Run ohi init with the intended directory first.",
      )

  private def refuseSymlink(path: Path): Unit =
    if Files.isSymbolicLink(path) then
      throw new ProjectException(
        ProjectErrorCode.InvalidProject,
        s"Project record '$path' is a symbolic link. Replace it with a regular record before continuing.",
      )

  private def conflicts(target: Path, record: Path): Boolean = {
    val resolved = canonical(record)
    val depth = resolved.getNameCount - 1
    // Compare actual ancestor spellings, including case-sensitive Windows directories.
    val sameParent = target.getNameCount > depth &&
      target.getRoot.toString == resolved.getRoot.toString &&
      (0 until depth).forall(index =>
        target.getName(index).toString == resolved.getName(index).toString,
      )
    if !sameParent then false
    else {
      val name = target.getName(depth).toString
      val reserved = resolved.getFileName.toString
      name == reserved || (name.forall(_ <= 127) && name.equalsIgnoreCase(reserved) &&
        !exists(record) && caseInsensitive(resolved.getParent))
    }
  }

  // Probe this directory because case sensitivity can vary within one filesystem.
  private def caseInsensitive(parent: Path): Boolean =
    storage(s"Cannot check filename case in '$parent'") {
      val probe = Files.createTempFile(parent, ".ohi-case-", ".tmp")
      val outcome = Try {
        val alternate = probe.resolveSibling(probe.getFileName.toString.toUpperCase(Locale.ROOT))
        try resolveProbe(alternate).toString == resolveProbe(probe).toString
        catch {
          case _: NoSuchFileException => false
        }
      }
      try deleteProbe(probe)
      catch {
        case NonFatal(cleanup) =>
          val cause = outcome.fold(
            failure => {
              failure.addSuppressed(cleanup)
              failure
            },
            _ => cleanup,
          )
          throw new ProjectException(
            ProjectErrorCode.StorageError,
            s"Filesystem case check failed: ${cause.getMessage}. " +
              s"Cannot remove temporary file '$probe': ${cleanup.getMessage}",
            Some(cause),
          )
      }
      outcome.get
    }

  // Resolve stable directory aliases even when a planned record or download does not exist yet.
  private def canonical(path: Path): Path = storage(s"Cannot resolve '$path'") {
    if exists(path) then path.toRealPath()
    else canonical(path.getParent).resolve(path.getFileName)
  }

  private def storage[T](message: String)(action: => T): T =
    try action
    catch {
      case failure: ProjectException => throw failure
      case NonFatal(cause) =>
        throw new ProjectException(
          ProjectErrorCode.StorageError,
          s"$message: ${cause.getMessage}",
          Some(cause),
        )
    }
}
