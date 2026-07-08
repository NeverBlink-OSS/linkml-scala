package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[TableDescriptor]] LinkML class
  * 
  * @inheritdoc
  */
case class TableDescriptorImpl(
    fields: Seq[FieldDescriptorImpl],
    foreignKeys: Seq[ForeignKeyImpl] = Seq(),
    missingValues: Seq[String] = Seq(),
    primaryKey: Seq[String] = Seq(),
) extends TableDescriptor 


abstract class TableDescriptor  {
  def fields: Seq[FieldDescriptorImpl]
  /** An array of Table Schema Foreign Key objects.
    */
  def foreignKeys: Seq[ForeignKeyImpl]
  /** Values that when encountered in the source, should be considered as null.
    */
  def missingValues: Seq[String]
  /** A primary key is a field name or an array of field names, whose values MUST uniquely identify each row in the table.
    */
  def primaryKey: Seq[String]
}
