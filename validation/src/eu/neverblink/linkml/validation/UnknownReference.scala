package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UnknownReference]] LinkML class
  *
  * @inheritdoc
  */
final case class UnknownReferenceImpl(
    details: Option[String] = None,
    @named("issue_type")
    @serializeDefault
    issueType: String = "UnknownReference",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @named("reference_value")
    referenceValue: String,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends UnknownReference {

  override def infer(): UnknownReferenceImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Unknown reference '" + referenceValue + "' at " + inferenceInput(
          "location.json_pointer",
          location.jsonPointer,
        ),
      ),
    )
}

/** A reference in the schema points at an element that is not defined.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class UnknownReference extends SchemaFatal {

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
  def referenceValue: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): UnknownReference
}
