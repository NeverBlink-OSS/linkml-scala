package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*
/** Base implementation of the [[Constraints]] LinkML class
  * 
  * @inheritdoc
  */
case class ConstraintsImpl(
    enum: Seq[String] = Seq(),
    maxLength: Option[Int] = None,
    maximum: Option[String] = None,
    minLength: Option[Int] = None,
    minimum: Option[String] = None,
    pattern: Option[String] = None,
    required: Boolean = false,
    unique: Boolean = false,
) extends Constraints 


abstract class Constraints  {
  /** The value of the field must exactly match a value in the enum array.
    */
  def enum: Seq[String]
  /** An integer that specifies the maximum length of a value.
    */
  def maxLength: Option[Int]
  /** Specifies a maximum value for a field.
    */
  def maximum: Option[String]
  /** An integer that specifies the minimum length of a value.
    */
  def minLength: Option[Int]
  /** Specifies a minimum value for a field. This is different to minLength which checks the number of items in the value.
    */
  def minimum: Option[String]
  /** A regular expression that can be used to test field values. Values MUST conform to the standard XML Schema regular expression syntax.
    */
  def pattern: Option[String]
  /** Indicates whether a property must have a value for each instance.
    */
  def required: Boolean
  /** When true, each value for the property MUST be unique.
    */
  def unique: Boolean
}
