package eu.neverblink.linkml.generator.owl.config

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** How a name is made from the local part of an IRI. In every style, characters that are not
  * allowed in names become underscores.
  *
  * @see
  *   From schema: https://linkml.neverblink.eu/model/owl-import-config
  */
sealed abstract class NameStyle derives Stringify

object NameStyle {

  /** As it is.
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  @named("keep") case object Keep extends NameStyle

  /** `has_value`
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  @named("snake") case object Snake extends NameStyle

  /** `HasValue`
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  @named("pascal") case object Pascal extends NameStyle

  /** `HAS_VALUE`
    *
    * @see
    *   From schema: https://linkml.neverblink.eu/model/owl-import-config
    */
  @named("upper_snake") case object UpperSnake extends NameStyle
}
