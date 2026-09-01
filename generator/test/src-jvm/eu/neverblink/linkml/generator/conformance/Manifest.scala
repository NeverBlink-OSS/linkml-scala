package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[Manifest]] LinkML class
  * 
  * @inheritdoc
  */
final case class ManifestImpl(
    entries: Seq[TestImpl] = Seq(),
    name: String,
    schema: String,
) extends Manifest {
  
  override def infer(): ManifestImpl =
    this
}

/** 
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
  */
abstract class Manifest  {
  /** 
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def entries: Seq[TestImpl]
  /** 
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def name: String
  /** 
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def schema: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and
    * check that the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression
    *   references a slot that has no value
    */
  def infer(): Manifest
}
