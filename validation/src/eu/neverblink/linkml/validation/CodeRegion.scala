package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[CodeRegion]] LinkML class
  *
  * @inheritdoc
  */
final case class CodeRegionImpl(
    @named("end_column")
    endColumn: Option[Int] = None,
    @named("end_line")
    endLine: Option[Int] = None,
    @named("start_column")
    startColumn: Int,
    @named("start_line")
    startLine: Int,
) extends CodeRegion {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): CodeRegionImpl =
    this
}

/** A region of code in a schema where an issue was found. This class is a simplified (special case)
  * version of the `region` object from the SARIF specification:
  * https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/sarif-v2.1.0-errata01-os-complete.html#_Toc141790935
  *
  * Indexing is 1-based.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class CodeRegion {

  /** The column number in the schema where the issue ends (1-based, EXCLUSIVE).
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def endColumn: Option[Int]

  /** The line number in the schema where the issue ends (1-based, inclusive).
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def endLine: Option[Int]

  /** The column number in the schema where the issue starts (1-based).
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def startColumn: Int

  /** The line number in the schema where the issue starts (1-based).
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    */
  def startLine: Int

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): CodeRegion
}
