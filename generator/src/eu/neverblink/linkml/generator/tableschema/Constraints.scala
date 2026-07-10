package eu.neverblink.linkml.generator.tableschema

import io.circe.Codec

/** Frictionless Table Schema constraints model.
  *
  * @see
  *   https://specs.frictionlessdata.io/table-schema/#constraints
  *
  * @param `enum`
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
