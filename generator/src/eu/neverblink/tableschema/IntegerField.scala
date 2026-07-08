package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[IntegerField]] LinkML class
  * 
  * @inheritdoc
  */
case class IntegerFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    bareNumber: Boolean = false,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    format: Option[Reference[DefaultFormat]] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends IntegerField 


abstract class IntegerField extends FieldDescriptor {
  /** If true the physical contents of this field must follow the formatting constraints already set out.
    */
  def bareNumber: Boolean
  def format: Option[Reference[DefaultFormat]]
}
