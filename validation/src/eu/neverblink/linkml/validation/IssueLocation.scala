package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[IssueLocation]] LinkML class
  *
  * @inheritdoc
  */
final case class IssueLocationImpl(
    @named("code_region")
    codeRegion: Option[CodeRegionImpl] = None,
    @named("json_pointer")
    jsonPointer: Option[String] = None,
    @named("schema_id")
    schemaId: Option[Uri] = None,
) extends IssueLocation {

  override def infer(): IssueLocationImpl =
    this
}

/** A location in a schema where an issue was found.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class IssueLocation {

  /** A region of code in the schema where the issue was found.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def codeRegion: Option[CodeRegionImpl]

  /** A JSON pointer to the location in the schema where the issue was found.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def jsonPointer: Option[String]

  /** The identifier of the schema in which the issue was found. Always present, unless the issue
    * pertains to the lack of the identifier itself.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def schemaId: Option[Uri]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): IssueLocation
}
