package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[TimeField]] LinkML class
  * 
  * @inheritdoc
  */
case class TimeFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    format: Option[String] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends TimeField 


abstract class TimeField extends FieldDescriptor {
  /** Time format: default (ISO8601 hh:mm:ss), any, or a strptime pattern.
    */
  def format: Option[String]
}
