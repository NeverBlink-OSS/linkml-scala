package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[NonStandardSeparator]] LinkML class
  *
  * @inheritdoc
  */
final case class NonStandardSeparatorImpl(
    details: Option[String] = None,
    @named("element_name")
    elementName: String,
    @named("issue_type")
    @serializeDefault
    issueType: String = "NonStandardSeparator",
    location: IssueLocationImpl,
    message: Option[String] = None,
    separators: Seq[String] = Seq(),
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Warning,
) extends NonStandardSeparator {

  override def infer(): NonStandardSeparatorImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Element '" + elementName + "' uses non-standard separators in its name: " + stringify(
          separators,
        ) + ". They will be replaced with underscores",
      ),
    )
}

/** An element contains allowed, but non-standard word separators. This may become an error in the
  * future.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class NonStandardSeparator extends SchemaWarning {

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

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def separators: Seq[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): NonStandardSeparator
}
