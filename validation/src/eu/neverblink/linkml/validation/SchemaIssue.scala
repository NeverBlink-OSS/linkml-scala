package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

/** A single issue found during the validation of a LinkML schema.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  * @note
  *   This class is intended to be subclassed for specific types of issues. The issues should
  *   declare additional slots that are specific to the type of issue (e.g., name of class). The
  *   `message` and `details` slots should be specified in the subclass using `equals_expression` to
  *   provide a human-readable message describing the issue.
  * @note
  *   Do not subclass this class directly. Instead, subclass one of the following classes:
  *   SchemaWarning, SchemaError, SchemaFatal.
  */
abstract class SchemaIssue {

  /** Longer, human-readable message describing the issue in more detail.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def details: Option[String]

  /** The location in the schema where the issue was found.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def location: IssueLocationImpl

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

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
  def infer(): SchemaIssue
}
