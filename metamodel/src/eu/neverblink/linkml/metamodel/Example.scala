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
    valueObject: Option[Anything] = None,
) extends Example

/** Usage example and description
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class Example {

  /** Example value
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def value: Option[String]

  /** Description of what the value is doing
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def valueDescription: Option[String]

  /** Direct object representation of the example
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def valueObject: Option[Anything]
}
