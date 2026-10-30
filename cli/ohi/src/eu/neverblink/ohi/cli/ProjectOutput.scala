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

import CliFormatting.{action, styled}

private[cli] object ProjectOutput {
  private val resultCodec: LinkmlYamlCodec[ProjectResult] = LinkmlYamlCodec.derived
  private val errorCodec: LinkmlYamlCodec[ProjectError] = LinkmlYamlCodec.derived
  private given catalogError: LinkmlYamlCodec[CatalogError] = LinkmlYamlCodec.derived
  private given lockError: LinkmlYamlCodec[LockError] = LinkmlYamlCodec.derived
  private given fileError: LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived

  def failure(
      operation: ProjectOperation,
      directory: String,
      declarationChanged: Boolean,
      lockChanged: Boolean,
      cause: Throwable,
      recovery: String,
  ): ProjectError = {
    val base = ProjectError(
      code = ProjectErrorCode.OperationFailed,
      operation = operation,
      projectDirectory = directory,
      declarationChanged = declarationChanged,
      lockChanged = lockChanged,
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

  def result(result: ProjectResult, json: Boolean, colored: Boolean = false): String =
    if json then JsonUtil.yamlToJson(resultCodec.encode(result))
    else {
      val directory = Path.of(result.projectDirectory)
      val declaration = directory.resolve("ohi.yaml").toString
      if result.operation == generated.projectresult.ProjectOperation.Init then
        if result.declarationChanged then
          action("Created", declaration, colored) +
            "\n\nNext: ohi add --help\nDocs: " + styled("https://ohi.dev/docs/", "36", colored)
        else styled(declaration, "36", colored) + " already exists. No changes made."
      else
        (Option.when(result.declarationChanged)(action("Saved", declaration, colored)).toSeq ++
          Option.when(result.lockChanged)(
            action("Saved", directory.resolve("ohi.lock.json").toString, colored),
          ) ++
          result.delivery.map(delivery => action("Saved", delivery.path, colored))).mkString("\n")
    }

  def error(
      error: ProjectError,
      json: Boolean,
      colored: Boolean = false,
      causeMessage: Option[String] = None,
      recoveryCommands: Seq[String] = Nil,
  ): String =
    if json then {
      JsonUtil.yamlToJson(errorCodec.encode(error))
    } else {
      val directory = java.nio.file.Path.of(error.projectDirectory)
      val declaration = directory.resolve("ohi.yaml").toString
      val lock = directory.resolve("ohi.lock.json").toString
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
          timeoutOption = false,
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
        }
        val location = if error.code == ProjectErrorCode.InvalidDeclaration then declaration
        else error.projectDirectory
        (s"${CliFormatting.styled("Error:", "1;31", colored)} $summary\n" +
          s"Path: ${CliFormatting.styled(location, "36", colored)}\n" +
          s"Details: ${TerminalOutput.withoutAnsi(causeMessage.getOrElse(error.message))}") ->
          (if error.operation == ProjectOperation.Init && error.code == ProjectErrorCode.InvalidDeclaration
           then "Correct ohi.yaml using format_version: 1 and exact selection URLs, then run:"
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
          val download = if error.operation != ProjectOperation.Add then Nil
          else
            error.fileError.flatMap(_.partialPath) match {
              case Some(path) =>
                Seq(
                  s"  Incomplete ontology file retained: ${CliFormatting.styled(path, "36", colored)}",
                )
              case None => Seq("  The ontology file was not downloaded.")
            }
          (Seq(
            CliFormatting.styled("Project files", "1", colored),
            record(error.declarationChanged, declaration, "Unchanged"),
            record(error.lockChanged, lock, "Not written"),
          ) ++ download).mkString("\n")
        }
      val commands = if recoveryCommands.isEmpty then ""
      else
        "\n" + recoveryCommands.map(command =>
          s"  ${CliFormatting.styled(command, "36", colored)}",
        ).mkString("\n")
      val docs = if error.operation == ProjectOperation.Init then
        "\n\nDocumentation: https://ohi.dev/docs/"
      else ""
      Seq(diagnostic, effects, recovery + commands + docs).filter(_.nonEmpty).mkString("\n\n")
    }
}
