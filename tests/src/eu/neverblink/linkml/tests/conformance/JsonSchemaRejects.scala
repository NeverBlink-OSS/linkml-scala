package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[JsonSchemaRejects]] LinkML class
  *
  * @inheritdoc
  */
final case class JsonSchemaRejectsImpl(
    description: Option[String] = None,
    instance: String,
    title: Option[String] = None,
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("JsonSchemaRejects"),
) extends JsonSchemaRejects {

  override def infer(): JsonSchemaRejectsImpl =
    this
}

/** An implementation of this assertion must load the result of an Action as a JSON Schema into a
  * JSON Schema validator, fetch the instance from the specified file, and validate the instance
  * using the validator. The validator should report a problem with the instance.
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class JsonSchemaRejects extends Assertion {

  /** Should be a file path to a file containing the serialized form of the instance, present
    * somewhere in the directory structure.
    *
    * @see
    *   From schema: https://w3id.org/linkml/conformance/
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
