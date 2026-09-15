package eu.neverblink.linkml.tests.conformance

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[LintAction]] LinkML class
  *
  * @inheritdoc
  */
final case class LintActionImpl(
    description: Option[String] = None,
    title: Option[String] = None,
    @named("type")
    @serializeDefault
    `type`: Option[String] = Some("LintAction"),
) extends LintAction {

  override def infer(): LintActionImpl =
    this
}

/** The implementation should run a schema quality analyzer on the specified schema and report any
  * issues found. The human-readable output of such a analyzer should be passed as the Action
  * result.
  *
  * @see
  *   From schema: https://w3id.org/linkml/conformance/
  */
abstract class LintAction extends Action {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): LintAction
}
