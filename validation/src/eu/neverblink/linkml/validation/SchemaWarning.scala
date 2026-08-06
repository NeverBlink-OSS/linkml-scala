package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

/** @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class SchemaWarning extends SchemaIssue {

  /** The severity of the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   Subclasses should set this slot's default value using `ifabsent`. For example:
    *   `IssueSeverity(FATAL)`
    */
  def severity: IssueSeverity

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): SchemaWarning
}
