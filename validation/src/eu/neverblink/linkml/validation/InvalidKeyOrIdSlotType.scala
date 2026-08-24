package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[InvalidKeyOrIdSlotType]] LinkML class
  *
  * @inheritdoc
  */
final case class InvalidKeyOrIdSlotTypeImpl(
    @named("class_name")
    className: String,
    details: Option[String] = None,
    @named("element_name")
    elementName: String,
    @named("issue_type")
    @serializeDefault
    issueType: String = "InvalidKeyOrIdSlotType",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Error,
) extends InvalidKeyOrIdSlotType {

  override def infer(): InvalidKeyOrIdSlotTypeImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Invalid type of key / identifier slot in class '" + className + "': '" + elementName + "'. Expected a basic, scalar data type (e.g., string, integer, float, uri).",
      ),
    )
}

/** A `key` or `identifier` slot has a range that is not a scalar type.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class InvalidKeyOrIdSlotType extends SchemaError {

  /** Name of the class the issue was found in.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def className: String

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
  def infer(): InvalidKeyOrIdSlotType
}
