package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[InvalidRange]] LinkML class
  *
  * @inheritdoc
  */
final case class InvalidRangeImpl(
    @named("actual_type")
    actualType: String,
    details: Option[String] = None,
    @named("json_path")
    jsonPath: String,
    location: IssueLocationImpl,
    message: Option[String] = None,
    @named("range_value")
    rangeValue: String,
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends InvalidRange {

  override def infer(): InvalidRangeImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "Invalid range '" + rangeValue + "' at " + jsonPath + ", which refers to " + actualType + ". Ranges can only reference types, classes or enums.",
      ),
      message =
        inferOptional("message", message, "Invalid range '" + rangeValue + "' at " + jsonPath),
    )
}

/** A `range` points at an element that cannot be used as a range.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class InvalidRange extends SchemaFatal {

  /** The metamodel type the range actually resolved to.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def actualType: String

  /** Longer, human-readable message describing the issue in more detail.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def details: Option[String]

  /** Path to the offending part of the schema. Mirrors `location.json_pointer`, which
    * `equals_expression` cannot reach into.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def jsonPath: String

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def rangeValue: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): InvalidRange
}
