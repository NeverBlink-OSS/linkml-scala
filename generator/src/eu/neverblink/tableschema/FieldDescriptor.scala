package eu.neverblink.tableschema

import io.circe.derivation.Configuration
import io.circe.{Codec, HCursor, Json}

given Configuration = Configuration.default.withDefaults

/** @param fields
  *   field descriptors of the csv
  * @param primaryKey
  *   A primary key is a field or set of fields that uniquely identifies each row in the table. Per
  *   SQL standards, the fields cannot be null, so their use in the primary key is equivalent to
  *   adding required: true to their constraints.
  */
case class TableDescriptor(
    fields: Seq[FieldDescriptor] = Seq(),
    primaryKey: Option[String] = None,
) derives Codec

/** @param name
  *   The field descriptor MUST contain a name property. This property SHOULD correspond to the name
  *   of field/column in the data file (if it has a name). As such it SHOULD be unique (though it is
  *   possible, but very bad practice, for the data file to have multiple columns with the same
  *   name). name SHOULD NOT be considered case sensitive in determining uniqueness. However, since
  *   it should correspond to the name of the field in the data file it may be important to preserve
  *   case
  * @param `type`
  *   string indicating the type of this field.
  * @param description
  *   A description for this field e.g. “The recipient of the funds”
  * @param example
  *   An example value for the field
  * @param title
  *   A human-readable label or title for the field
  * @param constraints
  *   constraints for validating field values
  * @param rdfType
  *   A richer, “semantic”, description of the “type” of data in a given column
  * @param format
  *   indicating a format for the field type.
  * @param bareNumber
  *   If true the physical contents of this field must follow the formatting constraints already set
  *   out. If false the contents of this field may contain leading and/or trailing non-numeric
  *   characters (which implementors MUST therefore strip).
  * @param decimalChar
  *   A string whose value is used to represent a decimal point within the number.
  * @param groupChar
  *   A string whose value is used to group digits within the number.
  */
case class FieldDescriptor(
    name: String,
    `type`: String = "string",
    description: Option[String] = None,
    example: Option[String] = None,
    title: Option[String] = None,
    constraints: Option[Constraints] = None,
    rdfType: Option[String] = None,

    // Formats:
    format: String = "default",

//    // Numbers:
//    bareNumber: Option[Boolean] = None, // true
//    decimalChar: Option[String] = None, // "."
//    groupChar: Option[String] = None, // ","
) derives Codec

object FieldDescriptorTypes:
  // Simple atomic
  val string = "string"
  val number = "number"
  val integer = "integer"
  val boolean = "boolean"

  // JSON inlines
  val `object` = "object"
  val array = "array"

  // Date and time
  val date = "date"
  val time = "time"
  val datetime = "datetime"

  val any = "any"

/** @param `enum`
  *   The value of the field must exactly match a value in the enum array.
  * @param maxLength
  *   An integer that specifies the maximum length of a value.
  * @param maximum
  *   Specifies a maximum value for a field.
  * @param minLength
  *   An integer that specifies the minimum length of a value.
  * @param minimum
  *   Specifies a minimum value for a field. This is different to minLength which checks the number
  *   of items in the value.
  * @param pattern
  *   A regular expression that can be used to test field values. Values MUST conform to the
  *   standard XML Schema regular expression syntax.
  * @param required
  *   Indicates whether a property must have a value for each instance.
  * @param unique
  *   When true, each value for the property MUST be unique.
  */
case class Constraints(
    `enum`: Option[Seq[String]] = None,
    maxLength: Option[Int] = None,
    maximum: Option[String] = None,
    minLength: Option[Int] = None,
    minimum: Option[String] = None,
    pattern: Option[String] = None,
    required: Option[Boolean] = None, // false
    unique: Option[Boolean] = None, // false
) derives Codec
