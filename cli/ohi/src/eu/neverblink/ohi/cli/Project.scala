package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.declaration.{
  Declaration,
  Format as DeclarationFormat,
  ProjectDependency,
}
import eu.neverblink.ohi.cli.generated.projectresult.LockFileResult
import eu.neverblink.ohi.cli.generated.projecterror.{
  ProjectError,
  ProjectErrorCode,
  ProjectOperation,
}
import eu.neverblink.ohi.cli.generated.projectresult
import eu.neverblink.ohi.model.{LockException, LockFiles, LockJson}
import eu.neverblink.ohi.model.generated.{Artifact, Publication}
import eu.neverblink.ohi.model.generated.lock
import eu.neverblink.ohi.model.generated.lockerror.{LockError, LockErrorCode, LockOperation}
import eu.neverblink.linkml.runtime.stringify

import java.nio.file.{Files, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import scala.util.control.NonFatal

/** The ohi project in `directory` with command effects and recovery information. Use a new instance
  * per command, because `failure` reports the files this instance wrote.
  */
private[cli] final class Project(
    val directory: Path,
    val operation: ProjectOperation,
    createDeclaration: (Path, Declaration) => Unit = DeclarationFiles.create,
    writeDeclaration: (Path, Declaration) => Unit = DeclarationFiles.write,
    writeLock: (Path, lock.Lock) => Unit = LockFiles.write,
) {
  private val declarationPath = directory.resolve("ohi.yaml")
  private val lockPath = directory.resolve("ohi.lock.json")
  private var declarationChanged = false
  private var declarationCreated = false
  private var lockChanged = false
  private var destination: Option[DownloadDestination] = None
  private var added: Option[ProjectDependency] = None

  def downloadDirectory: Option[Path] = destination.map(value => Path.of(value.directory))
  def addedDependency: Option[ProjectDependency] = added

  /** Creates an empty declaration with the supplied defaults, preserving an existing valid file.
    * `downloadDir` must be project-relative. Does not create the download directory.
    */
  def init(
      createParents: Boolean,
      defaultFormat: DeclarationFormat = DeclarationFormat.LinkmlYaml,
      downloadDir: String = ".ohi",
  ): projectresult.ProjectResult = {
    if createParents then
      storage(s"Cannot create project directory '$directory'") {
        Files.createDirectories(directory)
      }
    requireDirectory()
    refuseSymlink(declarationPath)
    val declaration = if exists(declarationPath) then DeclarationFiles.read(declarationPath)
    else {
      val declaration = Declaration(
        defaultFormat = defaultFormat,
        downloadDir = downloadDir,
        dependencies = Seq.empty,
        formatVersion = 1,
      )
      createDeclaration(declarationPath, declaration)
      declarationChanged = true
      declarationCreated = true
      declaration
    }
    destination = Some(DownloadDestination.fromProject(directory, declaration))
    result()
  }

  /** Resolves and preflights all files before saving the declaration and lock, then installs the
    * complete graph. The caller validates mutually exclusive selector options. Failures retain
    * completed writes and expose recovery through [[failure]]. An artifact URL requires publication
    * URL input and an exact pair reproducible through its catalog reference and format.
    */
  def add(
      input: String,
      artifact: Option[String],
      format: Option[String],
      inspect: String => Publication,
      fetch: String => Array[Byte],
  ): projectresult.ProjectResult = {
    val previous = read(allowMissing = true)
    val missing = !exists(declarationPath)
    val metadata = new ProjectLock.Inspection(inspect)
    val (requirement, publication, selected) = artifact match {
      case Some(url) =>
        val publication = metadata(input)
        val selected = publication.artifacts.find(_.url.original == url).getOrElse {
          invalidSelection(s"Publication '$input' does not advertise artifact '$url'.")
        }
        val requirement = dependency(publication, selected)
        val resolved = resolve(requirement.ontology, Some(stringify(selected.format)), metadata)
        if resolved._1.baseUrl.original != input || resolved._2.url.original != url then
          invalidSelection(
            s"Requirement '${requirement.ontology}' in ${stringify(selected.format)} does not select the supplied publication and file URLs.",
          )
        (requirement, resolved._1, resolved._2)
      case None =>
        val resolved = resolve(
          input,
          format,
          metadata,
          Some(ProjectFormat.toModel(previous.defaultFormat)),
        )
        (dependency(resolved._1, resolved._2), resolved._1, resolved._2)
    }
    added = Some(requirement)
    val changed = !previous.dependencies.contains(requirement)
    val declaration =
      if changed then previous.copy(dependencies = previous.dependencies :+ requirement)
      else previous
    val pair = lock.ArtifactSelectionImpl(publication.baseUrl, selected.url)
    val complete = build(declaration, metadata, Some(requirement -> pair))
    val plan = prepare(DownloadDestination.fromProject(directory, declaration), complete)
    if changed then {
      writeDeclaration(declarationPath, declaration)
      declarationChanged = true
      declarationCreated = missing
    }
    writeLock(lockPath, complete)
    lockChanged = true
    LockDownload.ensureIgnore(plan)
    result(LockDownload.install(plan, fetch))
  }

  /** Resolves the recorded formats strictly and replaces the lock without editing the declaration
    * or downloading files. Invalid existing records fail before catalog requests.
    */
  def refresh(inspect: String => Publication): projectresult.ProjectResult = {
    val declaration = read(allowMissing = false)
    val complete = build(declaration, new ProjectLock.Inspection(inspect))
    writeLock(lockPath, complete)
    lockChanged = true
    result()
  }

  /** Installs a valid revision-2 lock, resolving only missing or changed direct requirements.
    * Preserves existing declaration and reused lock bytes. `init` creates a missing declaration
    * from direct selections after preflight. Reports completed writes and files through
    * [[failure]].
    */
  def sync(
      inspect: String => Publication,
      fetch: String => Array[Byte],
      init: Boolean = false,
  ): projectresult.ProjectResult = {
    val (declaration, existing) = readRecords()
    existing.foreach { complete =>
      if complete.formatVersion != 2 then
        throw new LockException(
          LockError(
            code = LockErrorCode.UnsupportedVersion,
            operation = LockOperation.Read,
            path = Some(lockPath.toString),
            message = s"Lock '$lockPath' requires format version 2 for sync. " +
              s"Rebuild it with 'ohi lock --project ${CliFormatting.quote(directory.toString)}' when ohi.yaml exists, or regenerate it with its originating application.",
          ),
        )
    }
    val current = declaration match {
      case Some(value) =>
        existing.filter { complete =>
          ProjectLock.requirements(complete).sortBy(dependency =>
            (dependency.ontology, stringify(dependency.format)),
          ) == value.dependencies.sortBy(dependency =>
            (dependency.ontology, stringify(dependency.format)),
          )
        }
      case None => existing
    }
    val complete = current.getOrElse {
      val requirements = declaration.getOrElse {
        throw new ProjectException(
          ProjectErrorCode.InvalidProject,
          s"Project '$directory' has neither ohi.yaml nor ohi.lock.json. Run ohi init or ohi add to start a project.",
        )
      }
      build(requirements, new ProjectLock.Inspection(inspect))
    }
    val selected = declaration
      .map(DownloadDestination.fromProject(directory, _))
      .getOrElse(DownloadDestination.standalone(directory))
    val plan = prepare(selected, complete)
    if init && declaration.isEmpty then {
      createDeclaration(
        declarationPath,
        Declaration(
          defaultFormat = DeclarationFormat.LinkmlYaml,
          downloadDir = ".ohi",
          dependencies = ProjectLock.requirements(complete),
          formatVersion = 1,
        ),
      )
      declarationChanged = true
      declarationCreated = true
    }
    if current.isEmpty then {
      writeLock(lockPath, complete)
      lockChanged = true
    }
    LockDownload.ensureIgnore(plan)
    result(LockDownload.install(plan, fetch))
  }

  private def prepare(selected: DownloadDestination, complete: lock.Lock): LockDownload.Plan = {
    destination = Some(selected)
    LockDownload.prepare(complete.files, selected.directory, selected.managed)
  }

  private def dependency(publication: Publication, artifact: Artifact): ProjectDependency = {
    val reference = s"${publication.namespace}/${publication.name}@${publication.version}"
    OntologyReference.parse(reference) match {
      case Right((_, _, Some(_))) =>
        ProjectDependency(
          format = ProjectFormat.toDeclaration(artifact.format),
          ontology = reference,
        )
      case _ =>
        invalidSelection(
          s"Publication '${publication.baseUrl.original}' has no representable complete ontology reference.",
        )
    }
  }

  private def resolve(
      reference: String,
      format: Option[String],
      inspect: String => Publication,
      preferredFormat: Option[eu.neverblink.ohi.model.generated.Format] = None,
  ): (Publication, Artifact) =
    OntologyReference.resolve(reference, format, inspect, preferredFormat)
      .fold(problem => invalidSelection(problem.message), identity)

  private def invalidSelection(message: String): Nothing =
    throw new ProjectException(
      ProjectErrorCode.InvalidSelection,
      s"$message Correct the catalog reference or format, or use standalone ohi get for an exact file URL.",
    )

  /** Reports completed project writes and installation files, retaining the original failure. */
  def failure(cause: Throwable, retry: String): ProjectError = {
    val (original, files) = cause match {
      case failure: LockDownload.InstallationException => failure.cause -> failure.files
      case _ => cause -> Seq.empty
    }
    ProjectOutput.failure(
      operation = operation,
      directory = directory.toString,
      declarationChanged = declarationChanged,
      declarationCreated = declarationCreated,
      lockChanged = lockChanged,
      files = files,
      cause = original,
      recovery = s"Correct the reported cause, then run ${recoveryCommand(cause, retry)}.",
    )
  }

  /** Retries the original invocation until records and ignore handling succeed. Artifact failures
    * recover from the saved complete lock at the resolved installation directory.
    */
  def recoveryCommand(cause: Throwable, retry: String): String = cause match {
    case _: LockDownload.InstallationException =>
      s"ohi sync --project ${CliFormatting.quote(directory.toString)}"
    case _ => retry
  }

  private def read(allowMissing: Boolean): Declaration = {
    val (declaration, complete) = readRecords()
    if allowMissing && declaration.isEmpty && complete.nonEmpty then
      throw new ProjectException(
        ProjectErrorCode.InvalidProject,
        s"Lock '$lockPath' has no declaration. Restore or author '$declarationPath' before continuing.",
      )
    if !allowMissing && declaration.isEmpty then
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        s"Declaration '$declarationPath' is missing. Restore or author it before refreshing the lock.",
      )
    declaration.getOrElse(
      Declaration(
        defaultFormat = DeclarationFormat.LinkmlYaml,
        downloadDir = ".ohi",
        dependencies = Seq.empty,
        formatVersion = 1,
      ),
    )
  }

  private def readRecords(): (Option[Declaration], Option[lock.Lock]) = {
    requireDirectory()
    refuseSymlink(declarationPath)
    refuseSymlink(lockPath)
    val declaration = DeclarationFiles.readIfPresent(directory)
    val complete = if exists(lockPath) then Some(LockFiles.read(lockPath)) else None
    declaration -> complete
  }

  private def build(
      declaration: Declaration,
      metadata: ProjectLock.Inspection,
      requested: Option[(ProjectDependency, lock.ArtifactSelectionImpl)] = None,
  ): lock.Lock = {
    val selections = declaration.dependencies.map { requirement =>
      requested.filter(_._1 == requirement).map(_._2).getOrElse {
        val (publication, artifact) = resolve(
          requirement.ontology,
          Some(stringify(requirement.format)),
          metadata,
        )
        lock.ArtifactSelectionImpl(publication.baseUrl, artifact.url)
      }
    }
    val complete = ProjectLock.build(selections, metadata)
    LockJson.encode(complete)
    complete
  }

  private def result(files: Seq[LockFileResult] = Seq.empty): projectresult.ProjectResult =
    projectresult.ProjectResult(
      operation = operation match {
        case ProjectOperation.Init => projectresult.ProjectOperation.Init
        case ProjectOperation.Add => projectresult.ProjectOperation.Add
        case ProjectOperation.Lock => projectresult.ProjectOperation.Lock
        case ProjectOperation.Sync => projectresult.ProjectOperation.Sync
      },
      projectDirectory = directory.toString,
      declarationChanged = declarationChanged,
      declarationCreated = declarationCreated,
      lockChanged = lockChanged,
      files = files,
    )

  private def exists(path: Path): Boolean = Files.exists(path, NOFOLLOW_LINKS)

  private def requireDirectory(): Unit =
    if !Files.isDirectory(directory) then {
      val label = if operation == ProjectOperation.Init then "Project directory" else "--project"
      throw new ProjectException(
        ProjectErrorCode.InvalidProject,
        s"$label '$directory' is not an existing directory. Run: ohi init ${CliFormatting.quote(directory.toString)}",
      )
    }

  private def refuseSymlink(path: Path): Unit =
    if Files.isSymbolicLink(path) then
      throw new ProjectException(
        ProjectErrorCode.InvalidProject,
        s"Project record '$path' is a symbolic link. Replace it with a regular record before continuing.",
      )

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
