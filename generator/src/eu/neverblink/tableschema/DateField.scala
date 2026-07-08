package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[DateField]] LinkML class
  * 
  * @inheritdoc
  */
case class DateFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    format: Option[String] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends DateField 


abstract class DateField extends FieldDescriptor {
  /** Date format: default (ISO8601 YYYY-MM-DD), any, or a strptime pattern.
    */
  def format: Option[String]
}
