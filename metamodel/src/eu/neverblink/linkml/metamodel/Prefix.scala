package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Prefix]] LinkML class
  *
  * @inheritdoc
  */
final case class PrefixImpl(
    @id
    @named("prefix_prefix")
    prefixPrefix: NcName,
    @value
    @named("prefix_reference")
    prefixReference: Uri,
) extends Prefix {

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * Only single-valued slots with a `string` range are inferred; slots with any other range are
    * left untouched.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): PrefixImpl =
    this
}

/** Prefix URI tuple
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class Prefix {

  /** The prefix components of a prefix expansions. This is the part that appears before the colon
    * in a CURIE.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def prefixPrefix: NcName

  /** The namespace to which a prefix expands to.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def prefixReference: Uri

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): Prefix
}
