package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[Example]] LinkML class
  *
  * @inheritdoc
  */
case class ExampleImpl(
                        value: Option[String] = None,
                        @named("description")
    valueDescription: Option[String] = None,
                        @named("object")
    valueObject: Option[LinkMlAny] = None,
) extends Example

/** Usage example and description
  */
abstract class Example {

  /** Example value
    */
  def value: Option[String]

  /** Description of what the value is doing
    */
  def valueDescription: Option[String]

  /** Direct object representation of the example
    */
  def valueObject: Option[LinkMlAny]
}
