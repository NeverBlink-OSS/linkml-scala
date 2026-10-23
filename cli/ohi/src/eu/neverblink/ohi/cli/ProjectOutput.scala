package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.ErrorOutput.adapt
import eu.neverblink.ohi.cli.generated.projecterror.*
import eu.neverblink.ohi.cli.generated.projectresult.ProjectResult
import eu.neverblink.ohi.model.{CatalogErrorJson, LockErrorJson, LockException}

private[cli] object ProjectOutput {
  private val resultCodec: LinkmlYamlCodec[ProjectResult] = LinkmlYamlCodec.derived
  private val errorCodec: LinkmlYamlCodec[ProjectError] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[CatalogError] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[LockError] = LinkmlYamlCodec.derived
  private given LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived

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
            adapt[CatalogError](CatalogErrorJson.encode(failure.error))
              .copy(responseJson = failure.error.responseJson),
          ),
        )
      case failure: LockException =>
        base.copy(lockError = Some(adapt[LockError](LockErrorJson.encode(failure.error))))
      case failure: CliFileException =>
        base.copy(fileError = Some(adapt[CliFileError](CatalogOutput.fileError(failure.error))))
    }
  }

  def result(result: ProjectResult, json: Boolean): String =
    if json then JsonUtil.yamlToJson(resultCodec.encode(result))
    else {
      val effects =
        s"Declaration written: ${result.declarationChanged}. Lock written: ${result.lockChanged}."
      s"${stringify(result.operation)} completed in ${result.projectDirectory}. $effects" +
        result.delivery.fold("")(delivery => s"\nSaved ${delivery.path}")
    }

  def error(error: ProjectError, json: Boolean): String =
    if json then {
      val withoutResponse =
        error.copy(catalogError = error.catalogError.map(_.copy(responseJson = None)))
      ErrorOutput.withCatalogResponse(
        JsonUtil.yamlToJson(errorCodec.encode(withoutResponse)),
        error.catalogError.flatMap(_.responseJson),
      )
    } else
      s"${stringify(error.operation)} failed (${stringify(error.code)}) in ${error.projectDirectory}. " +
        s"Declaration written: ${error.declarationChanged}. Lock written: ${error.lockChanged}.\n${error.message}"
}
