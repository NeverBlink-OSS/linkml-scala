package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[LocalName]] LinkML class
  *
  * @inheritdoc
  */
final case class LocalNameImpl(
    @id
    @named("local_name_source")
    localNameSource: NcName,
    @value
    @named("local_name_value")
    localNameValue: String,
) extends LocalName {

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
  def infer(): LocalNameImpl =
    this
}

/** An attributed label
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class LocalName {

  /** The ncname of the source of the name
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def localNameSource: NcName

  /** A name assigned to an element in a given ontology
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def localNameValue: String

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): LocalName
}
