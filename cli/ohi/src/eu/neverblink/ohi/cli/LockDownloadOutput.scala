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

  private def file(result: LockFileResult): String = result.status match {
    case LockFileStatus.Downloaded => s"Saved ${result.path}"
    case LockFileStatus.Verified => s"Verified ${result.path}"
  }

  def result(result: LockDownloadResult, json: Boolean): String =
    if json then JsonUtil.yamlToJson(resultCodec.encode(result))
    else if result.files.isEmpty then "No files in lock."
    else result.files.map(file).mkString("\n")

  def error(error: LockDownloadError, json: Boolean): String =
    if json then {
      val withoutResponse =
        error.copy(catalogError = error.catalogError.map(_.copy(responseJson = None)))
      ErrorOutput.withCatalogResponse(
        JsonUtil.yamlToJson(errorCodec.encode(withoutResponse)),
        error.catalogError.flatMap(_.responseJson),
      )
    } else {
      val causes = error.lockError.toSeq.map(_.message) ++
        error.catalogError.toSeq.map(cause =>
          s"${stringify(cause.operation)} failed (${stringify(cause.code)}): ${cause.message}" +
            cause.url.fold("")(url => s"\nURL: $url"),
        ) ++ error.fileError.toSeq.map(cause =>
          cause.message + cause.partialPath.fold("")(path => s"\nPartial file retained: $path"),
        )
      (error.files.map(file) ++ causes :+ "Correct the cause and repeat the same command.")
        .mkString("\n")
    }
}
