package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.ErrorOutput.{as, given}
import eu.neverblink.ohi.cli.generated.lockdownload.*
import eu.neverblink.ohi.model.LockException

private[cli] object LockDownloadOutput {
  private val resultCodec: LinkmlYamlCodec[LockDownloadResult] = LinkmlYamlCodec.derived
  private val errorCodec: LinkmlYamlCodec[LockDownloadError] = LinkmlYamlCodec.derived
  private given catalogError: LinkmlYamlCodec[CatalogError] = LinkmlYamlCodec.derived
  private given lockError: LinkmlYamlCodec[LockError] = LinkmlYamlCodec.derived
  private given fileError: LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived

  def failure(
      files: Seq[LockFileResult],
      cause: CatalogException | LockException | CliFileException,
  ): LockDownloadError = cause match {
    case failure: CatalogException =>
      LockDownloadError(
        files = files,
        catalogError = Some(
          failure.error.as[CatalogError],
        ),
      )
    case failure: LockException =>
      LockDownloadError(
        files = files,
        lockError = Some(failure.error.as[LockError]),
      )
    case failure: CliFileException =>
      LockDownloadError(
        files = files,
        fileError = Some(failure.error.as[CliFileError]),
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
      JsonUtil.yamlToJson(errorCodec.encode(error))
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
