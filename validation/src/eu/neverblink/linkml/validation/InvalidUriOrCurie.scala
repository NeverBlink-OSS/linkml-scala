package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[InvalidUriOrCurie]] LinkML class
  *
  * @inheritdoc
  */
final case class InvalidUriOrCurieImpl(
    @named("defining_schema_id")
    definingSchemaId: String,
    details: Option[String] = None,
    @named("element_name")
    elementName: String,
    @named("element_type")
    elementType: String,
    location: IssueLocationImpl,
    message: Option[String] = None,
    severity: IssueSeverity = IssueSeverity.Error,
    @named("uri_or_curie")
    uriOrCurie: String,
) extends InvalidUriOrCurie {

  override def infer(): InvalidUriOrCurieImpl =
    copy(
      details = inferOptional(
        "details",
        details,
        "Invalid URI or CURIE '" + uriOrCurie + "' in " + elementType + " '" + elementName + "' imported from schema '" + definingSchemaId + "'. A valid URI must be a valid IRI, and a valid CURIE must be of the form 'prefix:localname' where 'prefix' is defined in the schema and 'localname' is a valid NCName.",
      ),
      message = inferOptional(
        "message",
        message,
        "Invalid URI or CURIE '" + uriOrCurie + "' in " + elementType + " '" + elementName + "' imported from schema '" + definingSchemaId + "'.",
      ),
    )
}

/** An element's URI or CURIE is not a valid IRI or `prefix:localname` pair.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class InvalidUriOrCurie extends SchemaError {

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def definingSchemaId: String

  /** Longer, human-readable message describing the issue in more detail.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def details: Option[String]

  /** Name of the element the issue was found in.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def elementName: String

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def elementType: String

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
  def uriOrCurie: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): InvalidUriOrCurie
}
