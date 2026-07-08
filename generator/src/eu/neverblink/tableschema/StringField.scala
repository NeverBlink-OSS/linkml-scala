package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[StringField]] LinkML class
  * 
  * @inheritdoc
  */
case class StringFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    format: Option[Reference[StringFormat]] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends StringField 


abstract class StringField extends FieldDescriptor {
  def format: Option[Reference[StringFormat]]
}
