package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.ErrorOutput.adapt
import eu.neverblink.ohi.cli.generated.lockdownload.*
import eu.neverblink.ohi.model.{CatalogErrorJson, LockErrorJson, LockException}

private[cli] object LockDownloadOutput {
  private val resultCodec: LinkmlYamlCodec[LockDownloadResult] = LinkmlYamlCodec.derived
  private val errorCodec: LinkmlYamlCodec[LockDownloadError] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[CatalogError] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[LockError] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived

  def failure(
      files: Seq[LockFileResult],
      cause: CatalogException | LockException | CliFileException,
  ): LockDownloadError = cause match {
    case failure: CatalogException =>
      LockDownloadError(
        files = files,
        catalogError = Some(
          adapt[CatalogError](CatalogErrorJson.encode(failure.error))
            .copy(responseJson = failure.error.responseJson),
        ),
      )
    case failure: LockException =>
      LockDownloadError(
        files = files,
        lockError = Some(adapt[LockError](LockErrorJson.encode(failure.error))),
      )
    case failure: CliFileException =>
      LockDownloadError(
        files = files,
        fileError = Some(adapt[CliFileError](CatalogOutput.fileError(failure.error))),
      )
  }

  private def file(result: LockFileResult, colored: Boolean): String = result.status match {
    case LockFileStatus.Downloaded => CliFormatting.action("Saved", result.path, colored)
    case LockFileStatus.Verified => CliFormatting.action("Verified", result.path, colored)
  }

  def result(result: LockDownloadResult, json: Boolean, colored: Boolean = false): String =
    if json then JsonUtil.yamlToJson(resultCodec.encode(result))
    else if result.files.isEmpty then "No files in lock."
    else result.files.map(file(_, colored)).mkString("\n")

  def error(
      error: LockDownloadError,
      json: Boolean,
      colored: Boolean = false,
      retryCommand: Option[String] = None,
  ): String =
    if json then {
      val withoutResponse =
        error.copy(catalogError = error.catalogError.map(_.copy(responseJson = None)))
      ErrorOutput.withCatalogResponse(
        JsonUtil.yamlToJson(errorCodec.encode(withoutResponse)),
        error.catalogError.flatMap(_.responseJson),
      )
    } else {
      val causes = error.lockError.toSeq.map(cause =>
        ErrorOutput.lock(
          stringify(cause.code),
          stringify(cause.operation),
          cause.message,
          cause.path,
          colored,
        ),
      ) ++ error.catalogError.toSeq.map(cause =>
        ErrorOutput.catalog(
          stringify(cause.code),
          stringify(cause.operation),
          cause.message,
          cause.url,
          cause.status,
          cause.responseJson,
          timeoutOption = true,
          colored = colored,
        ),
      ) ++ error.fileError.toSeq.map(cause =>
        ErrorOutput.file(
          stringify(cause.code),
          cause.message,
          cause.url,
          cause.directory,
          cause.path,
          cause.partialPath,
          colored,
        ),
      )
      val completed = error.files.map(file(_, colored)).mkString("\n")
      val (diagnostic, recovery) = causes.head
      val retained = if error.files.nonEmpty then "Completed files were kept. " else ""
      val retry =
        retryCommand.fold("")(command => s"\n  ${CliFormatting.styled(command, "36", colored)}")
      Seq(completed, diagnostic, retained + recovery + retry).filter(_.nonEmpty).mkString("\n\n")
    }
}
