package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[LocalName]] LinkML class
  *
  * @inheritdoc
  */
case class LocalNameImpl(
    @id
    @named("local_name_source")
    localNameSource: String,
    @value
    @named("local_name_value")
    localNameValue: String,
) extends LocalName

/** An attributed label
  */
abstract class LocalName {

  /** The ncname of the source of the name
    */
  def localNameSource: String

  /** A name assigned to an element in a given ontology
    */
  def localNameValue: String
}
