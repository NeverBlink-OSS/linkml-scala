package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[NonAsciiName]] LinkML class
  *
  * @inheritdoc
  */
final case class NonAsciiNameImpl(
    details: Option[String] = None,
    @named("element_name")
    elementName: String,
    @named("issue_type")
    @serializeDefault
    issueType: String = "NonAsciiName",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Error,
) extends NonAsciiName {

  override def infer(): NonAsciiNameImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Element '" + elementName + "' uses non-ASCII characters in its name",
      ),
    )
}

/** An element name uses non-ASCII characters.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class NonAsciiName extends SchemaError {

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
  def infer(): NonAsciiName
}
