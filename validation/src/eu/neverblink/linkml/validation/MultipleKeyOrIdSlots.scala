package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[MultipleKeyOrIdSlots]] LinkML class
  *
  * @inheritdoc
  */
final case class MultipleKeyOrIdSlotsImpl(
    @named("class_name")
    className: String,
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    severity: IssueSeverity = IssueSeverity.Error,
    @named("slot_name_list")
    slotNameList: String,
) extends MultipleKeyOrIdSlots {

  override def infer(): MultipleKeyOrIdSlotsImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Multiple key / identifier slots in class '" + className + "': " + slotNameList,
      ),
    )
}

/** A class has more than one `key` or `identifier` slot.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class MultipleKeyOrIdSlots extends SchemaError {

  /** Name of the class the issue was found in.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def className: String

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
  def infer(): MultipleKeyOrIdSlots
}
