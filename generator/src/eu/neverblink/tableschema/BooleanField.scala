package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[BooleanField]] LinkML class
  * 
  * @inheritdoc
  */
case class BooleanFieldImpl(
    @named("type")
    `type`: Option[String] = None,
    constraints: Option[ConstraintsImpl] = None,
    description: Option[String] = None,
    example: Option[String] = None,
    falseValues: Seq[String] = Seq(),
    format: Option[Reference[DefaultFormat]] = None,
    name: String,
    rdfType: Option[String] = None,
    title: Option[String] = None,
    trueValues: Seq[String] = Seq(),
) extends BooleanField 


abstract class BooleanField extends FieldDescriptor {
  /** String values that should be cast to false. Defaults to ["false", "False", "FALSE", "0"].
    */
  def falseValues: Seq[String]
  def format: Option[Reference[DefaultFormat]]
  /** String values that should be cast to true. Defaults to ["true", "True", "TRUE", "1"].
    */
  def trueValues: Seq[String]
}
