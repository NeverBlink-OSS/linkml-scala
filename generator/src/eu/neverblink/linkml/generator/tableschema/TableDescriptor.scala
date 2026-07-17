package eu.neverblink.linkml.generator.tableschema

/** Frictionless Table Schema table descriptor model. This is the root of the Table Schema.
  *
  * @see
  *   https://specs.frictionlessdata.io/table-schema/#descriptor
  *
  * @param fields
  *   field descriptors of the csv
  * @param primaryKey
  *   A primary key is a field or set of fields that uniquely identifies each row in the table. Per
  *   SQL standards, the fields cannot be null, so their use in the primary key is equivalent to
  *   adding required: true to their constraints.
  */
case class TableDescriptor(
    fields: Seq[FieldDescriptor] = Seq(),
    primaryKey: Option[String] = None,
)
