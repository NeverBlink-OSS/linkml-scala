package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

/** @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class SchemaFatal extends SchemaIssue {

  /** The severity of the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   Subclasses should set this slot's default value using `ifabsent`. For example:
    *   `IssueSeverity(FATAL)`
    */
  def severity: IssueSeverity

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): SchemaFatal
}
