package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Test]] LinkML class
  *
  * @inheritdoc
  */
final case class TestImpl(
    action: Action,
    assertion: Assertion,
    description: Option[String] = None,
    @id
    name: String,
    schema: String,
    title: Option[String] = None,
) extends Test {

  override def infer(): TestImpl =
    this
}

/** A single test consisting of a schema, action, and assertion
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class Test {

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def action: Action

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def assertion: Assertion

  /** Description of an element, including the implementation guide
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def description: Option[String]

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def name: String

  /** @see
    *   From schema: https://w3id.org/linkml/conformance/
    */
  def schema: String

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
  def infer(): Test
}
