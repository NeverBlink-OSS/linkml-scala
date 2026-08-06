package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UnknownReference]] LinkML class
  *
  * @inheritdoc
  */
final case class UnknownReferenceImpl(
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    @named("reference_value")
    referenceValue: String,
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends UnknownReference {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): UnknownReferenceImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "The schema contains a reference to an element '" + referenceValue + "' that is not defined in the schema. This could be a class, slot, or type. Check the schema for typos or missing definitions.",
      ),
      message =
        inferOptional("message", message, "Unknown reference to element '" + referenceValue + "'"),
    )
}

/** @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class UnknownReference extends SchemaFatal {

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

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def referenceValue: String

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): UnknownReference
}
