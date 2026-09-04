package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[JsonSchemaRejects]] LinkML class
  *
  * @inheritdoc
  */
final case class JsonSchemaRejectsImpl(
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("JsonSchemaRejects"),
    description: Option[String] = None,
    instance: String,
    title: Option[String] = None,
) extends JsonSchemaRejects {

  override def infer(): JsonSchemaRejectsImpl =
    this
}

/** @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
  */
abstract class JsonSchemaRejects extends Assertion {

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/conformance#
    */
  def instance: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): JsonSchemaRejects
}
