package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[SchemaParseError]] LinkML class
  *
  * @inheritdoc
  */
final case class SchemaParseErrorImpl(
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    @named("parser_message")
    parserMessage: String,
    severity: IssueSeverity = IssueSeverity.Fatal,
    @named("source_uri")
    sourceUri: String,
) extends SchemaParseError {

  override def infer(): SchemaParseErrorImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "Cannot parse schema '" + sourceUri + "': " + parserMessage,
      ),
      message = inferOptional("message", message, "Cannot parse schema: " + parserMessage),
    )
}

/** The schema text could not be parsed as YAML, or could not be decoded into the LinkML metamodel.
  * Where the underlying parser reported a position, it is carried in `location.code_region`.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class SchemaParseError extends SchemaFatal {

  /** Longer, human-readable message describing the issue in more detail.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def details: Option[String]

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** Verbatim message from the YAML parser or the metamodel decoder.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def parserMessage: String

  /** URI the schema text came from. Empty when parsing an in-memory string.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def sourceUri: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): SchemaParseError
}
