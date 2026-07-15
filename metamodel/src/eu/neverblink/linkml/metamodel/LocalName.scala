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
    localNameSource: NcName,
    @value
    @named("local_name_value")
    localNameValue: String,
) extends LocalName

/** An attributed label
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class LocalName {

  /** The ncname of the source of the name
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def localNameSource: NcName

  /** A name assigned to an element in a given ontology
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def localNameValue: String
}
