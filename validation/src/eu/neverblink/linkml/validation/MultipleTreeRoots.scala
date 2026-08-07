package eu.neverblink.linkml.validation

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[MultipleTreeRoots]] LinkML class
  *
  * @inheritdoc
  */
final case class MultipleTreeRootsImpl(
    @named("class_names")
    classNames: Seq[String],
    details: Option[String] = None,
    location: IssueLocationImpl,
    message: Option[String] = None,
    @serializeDefault
    severity: IssueSeverity = IssueSeverity.Error,
) extends MultipleTreeRoots {

  override def infer(): MultipleTreeRootsImpl =
    copy(
      message = inferOptional(
        "message",
        message,
        "Multiple classes are defined as a 'tree_root': " + stringify(classNames),
      ),
    )
}

/** More than one class in the root schema is marked as `tree_root`.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/issue-types
  */
abstract class MultipleTreeRoots extends SchemaError {

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/issue-types
    */
  def classNames: Seq[String]

  /** Short, human-readable message describing the issue.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/validation-report
    * @note
    *   This field is inferred using equals_expression and is present only if the consumer of the
    *   report wishes to include it.
    */
  def message: Option[String]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): MultipleTreeRoots
}
