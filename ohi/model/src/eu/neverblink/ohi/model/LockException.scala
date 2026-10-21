package eu.neverblink.ohi.model

import eu.neverblink.ohi.model.generated.lockerror.{LockError, LockErrorCode, LockOperation}

final class LockException(val error: LockError, cause: Option[Throwable] = None)
    extends RuntimeException(error.message, cause.orNull)

private[model] object LockException {
  def failure(
      code: LockErrorCode,
      operation: LockOperation,
      path: Option[String],
      message: String,
      cause: Option[Throwable] = None,
  ): LockException = new LockException(
    LockError(code = code, operation = operation, path = path, message = message),
    cause,
  )
}
