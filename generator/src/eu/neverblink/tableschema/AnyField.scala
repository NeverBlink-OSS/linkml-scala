package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[AnyField]] LinkML class
  * 
  * @inheritdoc
  */
case class AnyFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends AnyField 


abstract class AnyField extends FieldDescriptor {
  
}
