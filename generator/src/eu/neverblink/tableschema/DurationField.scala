package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[DurationField]] LinkML class
  * 
  * @inheritdoc
  */
case class DurationFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    format: Option[Reference[DefaultFormat]] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends DurationField 


abstract class DurationField extends FieldDescriptor {
  def format: Option[Reference[DefaultFormat]]
}
