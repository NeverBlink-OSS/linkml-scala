package eu.neverblink.linkml.generator.tableschema

import io.circe.Codec

/** Frictionless Table Schema field descriptor model.
  *
  * @see
  *   https://specs.frictionlessdata.io/table-schema/#field-descriptors
  *
  * @param name
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

object FieldDescriptor:
  object types:
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
