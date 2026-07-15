package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** The level of obligation or recommendation strength for a metadata element
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
sealed abstract class ObligationLevelEnum

object ObligationLevelEnum {

  /** The metadata element is required to be present in the model
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("REQUIRED") case object Required extends ObligationLevelEnum

  /** The metadata element is recommended to be present in the model
    *
    * @see
    *   Aliases: ENCOURAGED
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("RECOMMENDED") case object Recommended extends ObligationLevelEnum

  /** The metadata element is optional to be present in the model
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("OPTIONAL") case object Optional extends ObligationLevelEnum

  /** The metadata element is an example of how to use the model
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("EXAMPLE") case object Example extends ObligationLevelEnum

  /** The metadata element is allowed but discouraged to be present in the model
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("DISCOURAGED") case object Discouraged extends ObligationLevelEnum
}
