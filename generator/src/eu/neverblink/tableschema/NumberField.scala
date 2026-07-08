package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[NumberField]] LinkML class
  * 
  * @inheritdoc
  */
case class NumberFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    bareNumber: Boolean = false,
    constraints: Option[ConstraintsImpl] = None,
    decimalChar: Option[String] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    format: Option[Reference[DefaultFormat]] = None,
    groupChar: Option[String] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
) extends NumberField 


abstract class NumberField extends FieldDescriptor {
  /** If true the physical contents of this field must follow the formatting constraints already set out.
    */
  def bareNumber: Boolean
  /** A string whose value is used to represent a decimal point within the number. The default value is ".".
    */
  def decimalChar: Option[String]
  def format: Option[Reference[DefaultFormat]]
  /** A string whose value is used to group digits within the number. The default value is null.
    */
  def groupChar: Option[String]
}
