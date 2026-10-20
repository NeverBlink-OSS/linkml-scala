package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode

private[cli] final class ProjectException(
    val code: ProjectErrorCode,
    message: String,
    cause: Option[Throwable] = None,
) extends RuntimeException(message, cause.orNull)
