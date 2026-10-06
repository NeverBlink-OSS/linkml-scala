package eu.neverblink.linkml.generator.owl.config

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[ImportMapping]] LinkML class
  *
  * @inheritdoc
  */
final case class ImportMappingImpl(
    @id
    ontology: String,
    @value
    schema: String,
) extends ImportMapping {

  override def infer(): ImportMappingImpl =
    this
}

/** The LinkML schema to import instead of an ontology.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/owl-import-config
  */
abstract class ImportMapping {

  /** The IRI of the imported ontology.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def ontology: String

  /** Path to the LinkML schema, relative to where the new schema is written.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  def schema: String

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): ImportMapping
}
