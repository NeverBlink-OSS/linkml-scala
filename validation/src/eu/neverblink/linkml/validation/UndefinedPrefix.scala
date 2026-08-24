package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UndefinedPrefix]] LinkML class
  *
  * @inheritdoc
  */
final case class UndefinedPrefixImpl(
    details: Option[String] = None,
    @named("issue_type")
    @serializeDefault
    issueType: String = "UndefinedPrefix",
    location: IssueLocationImpl,
    message: Option[String] = None,
    prefix: NcName,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Error,
) extends UndefinedPrefix {

  override def infer(): UndefinedPrefixImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Undefined prefix " + stringify(prefix) + " at " + inferenceInput(
          "location.json_pointer",
          location.jsonPointer,
        ),
      ),
    )
}

/** A prefix is referenced but not defined in the schema.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class UndefinedPrefix extends SchemaError {

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def prefix: NcName

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): UndefinedPrefix
}
