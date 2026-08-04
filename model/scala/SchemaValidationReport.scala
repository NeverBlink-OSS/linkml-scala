package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[SchemaValidationReport]] LinkML class
  * 
  * @inheritdoc
  */
case class SchemaValidationReportImpl(
    issues: Seq[SchemaIssueImpl],
) extends SchemaValidationReport 

/** A report of the validation of a LinkML schema.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class SchemaValidationReport  {
  /** A list of issues found during the validation of the schema.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def issues: Seq[SchemaIssueImpl]
}
