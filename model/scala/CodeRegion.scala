package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[CodeRegion]] LinkML class
  * 
  * @inheritdoc
  */
case class CodeRegionImpl(
    @named("end_column")
    endColumn: Option[Int] = None,
    @named("end_line")
    endLine: Option[Int] = None,
    @named("start_column")
    startColumn: Int,
    @named("start_line")
    startLine: Int,
) extends CodeRegion 

/** A region of code in a schema where an issue was found.
This class is a simplified (special case) version of the `region` object from the SARIF specification:
https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/sarif-v2.1.0-errata01-os-complete.html#_Toc141790935

Indexing is 1-based.

  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/validation-report
  */
abstract class CodeRegion  {
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
}
