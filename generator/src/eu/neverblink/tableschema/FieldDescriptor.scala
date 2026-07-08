package eu.neverblink.tableschema

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

abstract class FieldDescriptor  {
  def `type`: Option[String]
  def constraints: Option[ConstraintsImpl]
  /** A description for this field e.g. "The recipient of the funds"
    */
  def description: Option[String]
  /** An example value for the field
    */
  def example: Option[String]
  /** The field descriptor MUST contain a name property. This property SHOULD correspond to the name of field/column in the data file (if it has a name). As such it SHOULD be unique (though it is possible, but very bad practice, for the data file to have multiple columns with the same name). name SHOULD NOT be considered case sensitive in determining uniqueness. However, since it should correspond to the name of the field in the data file it may be important to preserve case.\n
    */
  def name: String
  /** The RDF type for this field.
    */
  def rdfType: Option[String]
  /** A human readable label or title for the field
    */
  def title: Option[String]
}
