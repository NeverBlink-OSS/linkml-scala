package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[SchemaImportError]] LinkML class
  *
  * @inheritdoc
  */
final case class SchemaImportErrorImpl(
    details: Option[String] = None,
    @named("import_uri")
    importUri: String,
    location: IssueLocationImpl,
    message: Option[String] = None,
    reason: String,
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends SchemaImportError {

  override def infer(): SchemaImportErrorImpl =
    copy(
      details =
        inferOptional("details", details, "Cannot import schema '" + importUri + "': " + reason),
      message = inferOptional("message", message, "Cannot import schema '" + importUri + "'"),
    )
}

/** An imported schema could not be read.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class SchemaImportError extends SchemaFatal {

  /** Longer, human-readable message describing the issue in more detail.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def details: Option[String]

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def importUri: String

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** Verbatim message from whatever failed to supply the schema text.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def reason: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): SchemaImportError
}
