package eu.neverblink.linkml.generator.frictionless

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}

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
  * @param foreignKeys
  *   Fields of this table that reference the primary key of another table in the data package.
  */
final case class TableDescriptor(
    fields: Seq[FieldDescriptor] = Seq(),
    primaryKey: Option[String] = None,
    foreignKeys: Option[Seq[ForeignKey]] = None,
)

object TableDescriptor:
  /** Codec for a standalone Table Schema document.
    */
  given codec: JsonValueCodec[TableDescriptor] =
    JsonCodecMaker.make(
      CodecMakerConfig.withEncodingOnly(true)
        .withDiscriminatorFieldName(None)
        .withTransientDefault(false)
        .withTransientEmpty(false),
    )

/** A foreign key: one field of this table holding the primary key of a row in another table.
  *
  * @see
  *   https://specs.frictionlessdata.io/table-schema/#foreign-keys
  *
  * @param fields
  *   The field in this table that holds the reference.
  * @param reference
  *   What it points at.
  */
final case class ForeignKey(
    fields: String,
    reference: ForeignKeyReference,
)

/** The target of a [[ForeignKey]].
  *
  * @param resource
  *   Name of the resource in the same data package. The empty string means "this table"
  *   (self-reference).
  * @param fields
  *   The field of the target table being referenced, i.e. its primary key.
  */
final case class ForeignKeyReference(
    resource: String,
    fields: String,
)
