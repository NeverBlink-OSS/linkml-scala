package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

/** Mixin for classes that support annotations
  */
trait Annotatable {

  /** A collection of tag/text tuples with the semantics of OWL Annotation
    */
  def annotations: Map[String, AnnotationImpl]
}
