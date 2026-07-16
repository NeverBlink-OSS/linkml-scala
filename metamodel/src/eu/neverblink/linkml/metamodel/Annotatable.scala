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
}
