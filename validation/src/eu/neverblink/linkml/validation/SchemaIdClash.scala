package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[SchemaIdClash]] LinkML class
  *
  * @inheritdoc
  */
final case class SchemaIdClashImpl(
    details: Option[String] = None,
    @named("issue_type")
    @serializeDefault
    issueType: String = "SchemaIdClash",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends SchemaIdClash {

  override def infer(): SchemaIdClashImpl =
    copy(
      message = inferOptional("message", message, "Non-unique schema IDs detected"),
    )
}

/** Two distinct schemas declare the same `id`.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class SchemaIdClash extends SchemaFatal {

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
  def infer(): SchemaIdClash
}
