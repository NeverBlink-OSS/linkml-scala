package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[AltDescription]] LinkML class
  *
  * @inheritdoc
  */
case class AltDescriptionImpl(
    @id
    @named("source")
    altDescriptionSource: String,
    @value
    @named("description")
    altDescriptionText: String,
) extends AltDescription

/** An attributed description
  *
  * @see
  *   Aliases: structured description
  */
abstract class AltDescription {

  /** The source of an attributed description
    */
  def altDescriptionSource: String

  /** Text of an attributed description
    */
  def altDescriptionText: String
}
