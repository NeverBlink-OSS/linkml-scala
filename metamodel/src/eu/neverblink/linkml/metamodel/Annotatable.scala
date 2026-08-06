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

  /** Fill in the slots that have an `equals_expression`, and check the values already present
    * against them.
    *
    * @throws InferenceException
    *   if a slot's value contradicts the value inferred for it
    */
  def infer(): Annotatable
}
