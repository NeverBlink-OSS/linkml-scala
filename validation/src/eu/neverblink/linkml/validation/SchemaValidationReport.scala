package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

/** Base implementation of the [[SchemaValidationReport]] LinkML class
  *
  * @inheritdoc
  */
final case class SchemaValidationReportImpl(
    issues: Seq[SchemaIssue],
) extends SchemaValidationReport {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): SchemaValidationReportImpl =
    this
}

/** A report of the validation of a LinkML schema.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class SchemaValidationReport {

  /** A list of issues found during the validation of the schema.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def issues: Seq[SchemaIssue]

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): SchemaValidationReport
}
