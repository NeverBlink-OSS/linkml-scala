package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[JsonSchemaGenerate]] LinkML class
  *
  * @inheritdoc
  */
final case class JsonSchemaGenerateImpl(
    description: Option[String] = None,
    title: Option[String] = None,
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("JsonSchemaGenerate"),
) extends JsonSchemaGenerate {

  override def infer(): JsonSchemaGenerateImpl =
    this
}

/** The implementation should use a JSON Schema generator if it has one, skipping the test if it
  * does not. The generated JSON Schema should be passed as the Action result.
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class JsonSchemaGenerate extends GenerateAction {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): JsonSchemaGenerate
}
