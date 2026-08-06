package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** Mixin for classes that support annotations
  *
  * @see
  *   From schema: https://w3id.org/linkml/annotations
  */
trait Annotatable {

  /** A collection of tag/text tuples with the semantics of OWL Annotation
    *
    * @see
    *   From schema: https://w3id.org/linkml/annotations
    */
  def annotations: Map[String, AnnotationImpl]

  /** Fill in the slots that have an `equals_expression` with their computed values, and check that
    * the values already present agree with what their expressions infer.
    *
    * @throws eu.neverblink.linkml.runtime.InferenceException
    *   if a slot's value contradicts the value inferred for it, or if an expression references a
    *   slot that has no value
    */
  def infer(): Annotatable
}
