package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[UndefinedDefaultRange]] LinkML class
  *
  * @inheritdoc
  */
final case class UndefinedDefaultRangeImpl(
    details: Option[String] = None,
    @named("issue_type")
    @serializeDefault
    issueType: String = "UndefinedDefaultRange",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Warning,
) extends UndefinedDefaultRange {

  override def infer(): UndefinedDefaultRangeImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "The 'default_range' of the schema is not defined and could not find a 'string' type to use as a fallback. This will become a fatal error if any slots in the schema omit their 'range'. Add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.",
      ),
      message = inferOptional("message", message, "No 'default_range' is defined in the schema"),
    )
}

/** The schema has no `default_range` and no `string` type to fall back on.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class UndefinedDefaultRange extends SchemaWarning {

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

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): UndefinedDefaultRange
}
