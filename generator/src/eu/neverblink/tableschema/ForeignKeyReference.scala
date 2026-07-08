package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[ForeignKeyReference]] LinkML class
  * 
  * @inheritdoc
  */
case class ForeignKeyReferenceImpl(
    fields: Seq[String],
    resource: String,
) extends ForeignKeyReference 


abstract class ForeignKeyReference  {
  /** The field or fields on the destination resource.
    */
  def fields: Seq[String]
  /** The name of the resource within the data package. Use empty string for self-referencing foreign keys.
    */
  def resource: String
}
