package eu.neverblink.linkml.generator.owl.config

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[TermRename]] LinkML class
  *
  * @inheritdoc
  */
final case class TermRenameImpl(
    @value
    @named("new_name")
    newName: String,
    @id
    term: String,
) extends TermRename {

  override def infer(): TermRenameImpl =
    this
}

/** A fixed name for a term.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/owl-import-config
  */
abstract class TermRename {

  /** @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def newName: String

  /** The term's IRI or CURIE.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def term: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): TermRename
}
