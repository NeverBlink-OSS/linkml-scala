package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UnexpectedError]] LinkML class
  *
  * @inheritdoc
  */
final case class UnexpectedErrorImpl(
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    reason: String,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends UnexpectedError {

  override def infer(): UnexpectedErrorImpl =
    copy(
      message =
        inferOptional("message", message, "Unexpected error while loading the schema: " + reason),
    )
}

/** Something unexpected went wrong that during schema loading or validation. Consider filing a bug
  * report with the schema and the error message: {reason}
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class UnexpectedError extends SchemaFatal {

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** Message of the underlying exception.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def reason: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): UnexpectedError
}
