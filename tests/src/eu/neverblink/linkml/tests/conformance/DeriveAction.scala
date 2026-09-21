package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[DeriveAction]] LinkML class
  *
  * @inheritdoc
  */
final case class DeriveActionImpl(
    description: Option[String] = None,
    title: Option[String] = None,
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("DeriveAction"),
) extends DeriveAction {

  override def infer(): DeriveActionImpl =
    this
}

/** The implementation should run the schema derivation procedure as defined in the specification,
  * serialize the derived schema, and pass the serialized schema as the Action result.
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class DeriveAction extends Action {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): DeriveAction
}
