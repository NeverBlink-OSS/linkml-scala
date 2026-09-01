package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[NonUniqueInternalName]] LinkML class
  *
  * @inheritdoc
  */
final case class NonUniqueInternalNameImpl(
    details: Option[String] = None,
    @named("element_name")
    elementName: String,
    @named("issue_type")
    @serializeDefault
    issueType: String = "NonUniqueInternalName",
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Error,
    @named("transformed_name")
    transformedName: String,
    @named("used_for")
    usedFor: String,
) extends NonUniqueInternalName {

  override def infer(): NonUniqueInternalNameImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Element with name '" + elementName + "' is renamed internally to '" + transformedName + "' which clashes with " + usedFor,
      ),
    )
}

/** There is a name clash after name mangling
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class NonUniqueInternalName extends SchemaError {

  /** Name of the element the issue was found in.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def elementName: String

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** Formatted description of the elements that share the name.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def transformedName: String

  /** Formatted description of the elements that share the name.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def usedFor: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): NonUniqueInternalName
}
