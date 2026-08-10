package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[SchemaValidationReport]] LinkML class
  *
  * @inheritdoc
  */
final case class SchemaValidationReportImpl(
    issues: Seq[SchemaIssue],
    @named("validation_run_id")
    validationRunId: Option[String] = None,
) extends SchemaValidationReport {

  override def infer(): SchemaValidationReportImpl =
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

  /** Implementation-specific identifier of this validation run. This can be, for example, the
    * top-level file name of the schema being validated. It is useful for multi-schema (batch)
    * validation runs.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def validationRunId: Option[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): SchemaValidationReport
}
