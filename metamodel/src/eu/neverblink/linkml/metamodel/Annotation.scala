package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Annotation]] LinkML class
  *
  * @inheritdoc
  */
case class AnnotationImpl(
    @id
    @named("tag")
    extensionTag: UriOrCurie,
    @value
    @named("value")
    extensionValue: AnyValue,
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
) extends Annotation

/** A tag/value pair with the semantics of OWL Annotation
  *
  * @see
  *   From schema: https://w3id.org/linkml/annotations
  */
abstract class Annotation extends Extension, Annotatable {

  /** A collection of tag/text tuples with the semantics of OWL Annotation
    *
    * @see
    *   From schema: https://w3id.org/linkml/annotations
    */
  def annotations: Map[String, AnnotationImpl]
}
