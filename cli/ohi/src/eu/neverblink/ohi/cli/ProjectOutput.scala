package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.cli.TerminalOutput
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.ErrorOutput.{as, given}
import eu.neverblink.ohi.cli.generated.projecterror.*
import eu.neverblink.ohi.cli.generated.projectresult.ProjectResult
import eu.neverblink.ohi.model.LockException

import java.nio.file.Path

import CliFormatting.{displayPath, styled}

private[cli] object ProjectOutput {
  private val resultCodec: LinkmlYamlCodec[ProjectResult] = LinkmlYamlCodec.derived
  private val errorCodec: LinkmlYamlCodec[ProjectError] = LinkmlYamlCodec.derived
  private given catalogError: LinkmlYamlCodec[CatalogError] = LinkmlYamlCodec.derived
  private given lockError: LinkmlYamlCodec[LockError] = LinkmlYamlCodec.derived
  private given downloadedFile: LinkmlYamlCodec[generated.projectresult.LockFileResult] =
    LinkmlYamlCodec.derived
  private given projectFile: LinkmlYamlCodec[LockFileResult] = LinkmlYamlCodec.derived
  private given fileError: LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived

  def failure(
      operation: ProjectOperation,
      directory: String,
      declarationChanged: Boolean,
      declarationCreated: Boolean,
      lockChanged: Boolean,
      files: Seq[generated.projectresult.LockFileResult],
      cause: Throwable,
      recovery: String,
  ): ProjectError = {
    val base = ProjectError(
      code = ProjectErrorCode.OperationFailed,
      operation = operation,
      projectDirectory = directory,
      declarationChanged = declarationChanged,
      declarationCreated = declarationCreated,
      lockChanged = lockChanged,
      files = files.map(_.as[LockFileResult]),
      message = s"${cause.getMessage}\n$recovery",
    )
    cause match {
      case failure: ProjectException => base.copy(code = failure.code)
      case failure: CatalogException =>
        base.copy(catalogError =
          Some(
            failure.error.as[CatalogError],
          ),
        )
      case failure: LockException =>
        base.copy(lockError = Some(failure.error.as[LockError]))
      case failure: CliFileException =>
        base.copy(fileError = Some(failure.error.as[CliFileError]))
    }
  }

  def result(
      result: ProjectResult,
      json: Boolean,
      colored: Boolean = false,
      downloadDirectory: Option[Path] = None,
      added: Option[generated.declaration.ProjectDependency] = None,
  ): String =
    if json then JsonUtil.yamlToJson(resultCodec.encode(result))
    else {
      val directory = Path.of(result.projectDirectory)
      val declaration = displayPath(directory.resolve("ohi.yaml"))
      result.operation match {
        case generated.projectresult.ProjectOperation.Init =>
          if result.declarationCreated then
            creationNotice(directory, downloadDirectory, colored) + "\n\nNext: ohi add --help"
          else styled(declaration, "36", colored) + " already exists. No changes made."
        case generated.projectresult.ProjectOperation.Lock => "Updated ohi.lock.json."
        case _ =>
          val location = downloadDirectory.filter(_ =>
            !result.declarationCreated && directory != Path.of("").toAbsolutePath.normalize(),
          ).map(_.toString)
          val completed = LockDownloadOutput.summary(
            result.files,
            colored,
            location,
          )
          val addition = added.map { dependency =>
            val action = if result.declarationChanged then "Added" else "Already exists:"
            s"${styled(action, "32", colored)} ${styled(dependency.ontology, "36", colored)} (${stringify(dependency.format)})"
          }
          val receipt = (addition.toSeq :+ completed).mkString("\n")
          if result.declarationCreated then
            creationNotice(directory, downloadDirectory, colored) + "\n\n" + receipt
          else receipt
      }
    }

  def error(
      error: ProjectError,
      json: Boolean,
      colored: Boolean = false,
      causeMessage: Option[String] = None,
      recoveryCommand: Option[String] = None,
      downloadDirectory: Option[Path] = None,
  ): String =
    if json then {
      JsonUtil.yamlToJson(errorCodec.encode(error))
    } else {
      val directory = Path.of(error.projectDirectory)
      val declaration = displayPath(directory.resolve("ohi.yaml"))
      val lock = displayPath(directory.resolve("ohi.lock.json"))
      val (diagnostic, recovery) = error.catalogError.map { cause =>
        val summary =
          if error.lockChanged then
            Some("The project was updated, but the ontology download failed.")
          else if error.operation == ProjectOperation.Lock then
            Some("Could not rebuild the lock because a release could not be inspected.")
          else None
        ErrorOutput.catalog(
          stringify(cause.code),
          stringify(cause.operation),
          cause.message,
          cause.url,
          cause.status,
          cause.responseJson,
          timeoutOption = error.operation == ProjectOperation.Sync,
          colored = colored,
          summaryOverride = summary,
        )
      }.orElse(error.fileError.map { cause =>
        ErrorOutput.file(
          stringify(cause.code),
          cause.message,
          cause.url,
          cause.directory,
          cause.path,
          cause.partialPath,
          colored,
        )
      }).orElse(error.lockError.map { cause =>
        ErrorOutput.lock(
          stringify(cause.code),
          stringify(cause.operation),
          cause.message,
          cause.path,
          colored,
        )
      }).getOrElse {
        val summary = error.operation match {
          case ProjectOperation.Init if error.code == ProjectErrorCode.InvalidDeclaration =>
            "Cannot initialize the project because ohi.yaml is invalid."
          case ProjectOperation.Init => "Could not initialize the project."
          case ProjectOperation.Add => "Could not update the project."
          case ProjectOperation.Lock => "Could not rebuild the lock."
          case ProjectOperation.Sync => "Could not synchronize the project."
        }
        val location = if error.code == ProjectErrorCode.InvalidDeclaration then declaration
        else displayPath(directory)
        (s"${CliFormatting.styled("Error:", "1;31", colored)} $summary\n" +
          s"Path: ${CliFormatting.styled(location, "36", colored)}\n" +
          s"Details: ${TerminalOutput.withoutAnsi(causeMessage.getOrElse(error.message))}") ->
          (if error.operation == ProjectOperation.Init && error.code == ProjectErrorCode.InvalidDeclaration
           then
             "Correct ohi.yaml using format_version: 2, default_format, download_dir and dependencies, then run:"
           else "Correct the reported cause and retry.")
      }
      val effects =
        if error.operation == ProjectOperation.Init && error.code == ProjectErrorCode.StorageError
        then ""
        else if !error.declarationChanged && !error.lockChanged &&
          (error.operation != ProjectOperation.Lock || error.code == ProjectErrorCode.InvalidDeclaration ||
            error.code == ProjectErrorCode.InvalidProject)
        then "No project files were written."
        else {
          def record(changed: Boolean, path: String, unchanged: String): String =
            "  " + (if changed then CliFormatting.action("Saved", path, colored)
                    else s"$unchanged ${CliFormatting.styled(path, "36", colored)}")
          val download =
            if error.operation != ProjectOperation.Add && error.operation != ProjectOperation.Sync
            then Nil
            else
              error.fileError.flatMap(_.partialPath) match {
                case Some(path) =>
                  Seq(
                    s"  Incomplete ontology file retained: ${CliFormatting.styled(displayPath(Path.of(path)), "36", colored)}",
                  )
                case None => Nil
              }
          (Seq(
            CliFormatting.styled("Project files", "1", colored),
            record(error.declarationChanged, declaration, "Unchanged"),
            record(error.lockChanged, lock, "Not written"),
          ) ++ download).mkString("\n")
        }
      val command =
        recoveryCommand.fold("")(value => s"\n  ${CliFormatting.styled(value, "36", colored)}")
      val docs = if error.operation == ProjectOperation.Init then
        "\n\nDocumentation: https://ohi.dev/docs/guides/projects/"
      else ""
      (Seq(diagnostic) ++
        Option.when(error.declarationCreated)(
          creationNotice(directory, downloadDirectory, colored),
        ) ++
        Option.when(error.files.nonEmpty) {
          val downloaded = error.files.count(_.status == LockFileStatus.Downloaded)
          LockDownloadOutput.summary(downloaded, error.files.size - downloaded, colored, None)
        } ++
        Seq(effects, recovery + command + docs)).filter(_.nonEmpty).mkString("\n\n")
    }

  private def creationNotice(
      directory: Path,
      downloadDirectory: Option[Path],
      colored: Boolean,
  ): String = {
    val location = displayPath(directory)
    val created = if location == "." then "Created an Ohi project."
    else s"Created an Ohi project in ${styled(location, "36", colored)}."
    created + "\n" +
      s"You can edit ${styled(displayPath(directory.resolve("ohi.yaml")), "36", colored)} to manage your ontologies and project settings.\n" +
      s"Downloaded ontologies go in ${styled(displayPath(downloadDirectory.getOrElse(directory.resolve(".ohi"))), "36", colored)}/.\n\n" +
      "New to ohi? Check out the guide on how to manage ontology projects:\n  " +
      styled("https://ohi.dev/docs/guides/projects/", "36", colored)
  }

}
