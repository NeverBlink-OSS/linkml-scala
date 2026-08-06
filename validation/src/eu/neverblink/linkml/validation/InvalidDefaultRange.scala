package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[InvalidDefaultRange]] LinkML class
  *
  * @inheritdoc
  */
final case class InvalidDefaultRangeImpl(
    details: Option[String] = None,
    @named("json_path")
    jsonPath: String,
    location: IssueLocationImpl,
    message: Option[String] = None,
    severity: IssueSeverity = IssueSeverity.Fatal,
) extends InvalidDefaultRange {

  override def infer(): InvalidDefaultRangeImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "Undefined range at " + jsonPath + ", schema 'default_range' is undefined, and the fallback 'string' type is not available. Define the 'range' of the slot, add a 'default_range' to the schema, import 'linkml:types', or define a 'string' type to fix.",
      ),
      message = inferOptional("message", message, "Undefined range at " + jsonPath),
    )
}

/** A slot omits its `range` while no usable `default_range` exists.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class InvalidDefaultRange extends SchemaFatal {

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

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): InvalidDefaultRange
}
