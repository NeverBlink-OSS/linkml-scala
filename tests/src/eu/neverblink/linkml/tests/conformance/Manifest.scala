package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Manifest]] LinkML class
  *
  * @inheritdoc
  */
final case class ManifestImpl(
    description: Option[String] = None,
    @compactDict
    entries: Map[String, TestImpl] = Map(),
    @id
    name: String,
    title: Option[String] = None,
) extends Manifest {

  override def infer(): ManifestImpl =
    this
}

/** A test manifest representing a grouping of related tests
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class Manifest {

  /** Description of an element, including the implementation guide
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def description: Option[String]

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def entries: Map[String, TestImpl]

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def name: String

  /** Human readable name of an element
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def title: Option[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): Manifest
}
