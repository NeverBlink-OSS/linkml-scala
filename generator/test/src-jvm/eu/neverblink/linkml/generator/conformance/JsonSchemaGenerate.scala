package eu.neverblink.linkml.generator.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[JsonSchemaGenerate]] LinkML class
  *
  * @inheritdoc
  */
final case class JsonSchemaGenerateImpl(
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("JsonSchemaGenerate"),
    description: Option[String] = None,
    title: Option[String] = None,
) extends JsonSchemaGenerate {

  override def infer(): JsonSchemaGenerateImpl =
    this
}

/** @see
  *   From schema: https://linkml.neverblink.eu/model/conformance#
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
