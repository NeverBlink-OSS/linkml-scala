package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[ForeignKey]] LinkML class
  * 
  * @inheritdoc
  */
case class ForeignKeyImpl(
    fields: Seq[String],
    reference: ForeignKeyReferenceImpl,
) extends ForeignKey 


abstract class ForeignKey  {
  /** The field or fields on this resource that form the source part of the foreign key.
    */
  def fields: Seq[String]
  /** The reference object for this foreign key.
    */
  def reference: ForeignKeyReferenceImpl
}
