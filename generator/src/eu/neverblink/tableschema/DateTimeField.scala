package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[DateTimeField]] LinkML class
  * 
  * @inheritdoc
  */
case class DateTimeFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    format: Option[String] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends DateTimeField 


abstract class DateTimeField extends FieldDescriptor {
  /** Datetime format: default (ISO8601 YYYY-MM-DDThh:mm:ssZ), any, or a strptime pattern.
    */
  def format: Option[String]
}
