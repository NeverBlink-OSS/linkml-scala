package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[UnknownReference]] LinkML class
  * 
  * @inheritdoc
  */
case class UnknownReferenceImpl(
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    @named("reference_value")
    referenceValue: String,
    severity: IssueSeverity,
) extends UnknownReference 

/** 
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class UnknownReference extends SchemaFatal {
  /** Longer, human-readable message describing the issue in more detail.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the report wishes to include it.
    */
  def details: Option[String]
  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the report wishes to include it.
    */
  def message: Option[String]
  /** 
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def referenceValue: String
}
