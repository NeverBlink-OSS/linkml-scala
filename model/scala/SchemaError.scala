package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** 
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class SchemaError extends SchemaIssue {
  /** The severity of the issue.
  
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   Subclasses should set this slot's default value using `ifabsent`. For example: `IssueSeverity(FATAL)`
    */
  def severity: IssueSeverity
}
