package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** The severity of an issue found during the validation of a LinkML schema.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
sealed abstract class IssueSeverity

object IssueSeverity {

  /** A fatal error that prevents the schema from being loaded and validated further.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  @named("FATAL") case object Fatal extends IssueSeverity

  /** An error that prevents the schema from being valid.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  @named("ERROR") case object Error extends IssueSeverity

  /** A warning that indicates a potential issue with the schema.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  @named("WARNING") case object Warning extends IssueSeverity
}
