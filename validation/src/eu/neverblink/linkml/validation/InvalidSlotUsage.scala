package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[InvalidSlotUsage]] LinkML class
  *
  * @inheritdoc
  */
final case class InvalidSlotUsageImpl(
    @named("class_name")
    className: String,
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    severity: IssueSeverity = IssueSeverity.Warning,
    @named("slot_name_list")
    slotNameList: String,
) extends InvalidSlotUsage {

  override def infer(): InvalidSlotUsageImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "Class '" + className + "' has declared 'slot_usage' for slots that are not defined for its ancestors. These slots will not be included: " + slotNameList,
      ),
      message = inferOptional(
        "message",
        message,
        "Invalid 'slot_usage' slots: " + slotNameList + " in class " + className,
      ),
    )
}

/** A class declares `slot_usage` for slots that none of its ancestors provide.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class InvalidSlotUsage extends SchemaWarning {

  /** Name of the class the issue was found in.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def className: String

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
  def slotNameList: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): InvalidSlotUsage
}
