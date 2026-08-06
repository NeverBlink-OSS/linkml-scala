package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UnknownStringReference]] LinkML class
  *
  * @inheritdoc
  */
final case class UnknownStringReferenceImpl(
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends UnknownStringReference {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): UnknownStringReferenceImpl =
    copy(
      message = inferOptional("message", message, "Unknown reference to element 'string'"),
      details = inferOptional(
        "details",
        details,
        "The schema contains a reference to an element 'string' that is not defined in the schema. Make sure you have 'linkml:types' imported.",
      ),
    )
}

/** @see
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

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): UnknownStringReference
}
