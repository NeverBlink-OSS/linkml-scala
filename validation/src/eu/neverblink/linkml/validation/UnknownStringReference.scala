package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UnknownStringReference]] LinkML class
  *
  * @inheritdoc
  */
final case class UnknownStringReferenceImpl(
    details: Option[String] = None,
    @named("issue_type")
    @serializeDefault
    issueType: String = "UnknownStringReference",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends UnknownStringReference {

  override def infer(): UnknownStringReferenceImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "Unknown reference 'string' at " + inferenceInput(
          "location.json_pointer",
          location.jsonPointer,
        ) + ". Make sure you have 'linkml:types' imported.",
      ),
      message = inferOptional(
        "message",
        message,
        "Unknown reference 'string' at " + inferenceInput(
          "location.json_pointer",
          location.jsonPointer,
        ),
      ),
    )
}

/** A reference to the `string` type could not be resolved, which almost always means that
  * `linkml:types` was not imported.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class UnknownStringReference extends SchemaFatal {

  /** Longer, human-readable message describing the issue in more detail.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def details: Option[String]

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): UnknownStringReference
}
