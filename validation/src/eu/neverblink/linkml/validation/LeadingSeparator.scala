package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[LeadingSeparator]] LinkML class
  *
  * @inheritdoc
  */
final case class LeadingSeparatorImpl(
    details: Option[String] = None,
    @named("element_name")
    elementName: String,
    @named("issue_type")
    @serializeDefault
    issueType: String = "LeadingSeparator",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Warning,
) extends LeadingSeparator {

  override def infer(): LeadingSeparatorImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Element '" + elementName + "' name starts with a separator, it will be stripped. Leading separators are reserved.",
      ),
    )
}

/** An element starts with a word separator. This is reserved and may become an error in the future.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class LeadingSeparator extends SchemaWarning {

  /** Name of the element the issue was found in.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def elementName: String

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
  def infer(): LeadingSeparator
}
