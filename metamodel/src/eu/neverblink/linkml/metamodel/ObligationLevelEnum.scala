package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** The level of obligation or recommendation strength for a metadata element
  */
sealed abstract class ObligationLevelEnum

object ObligationLevelEnum {

  /** The metadata element is required to be present in the model
    */
  @named("REQUIRED") case object Required extends ObligationLevelEnum

  /** The metadata element is recommended to be present in the model
    *
    * @see
    *   Aliases: ENCOURAGED
    */
  @named("RECOMMENDED") case object Recommended extends ObligationLevelEnum

  /** The metadata element is optional to be present in the model
    */
  @named("OPTIONAL") case object Optional extends ObligationLevelEnum

  /** The metadata element is an example of how to use the model
    */
  @named("EXAMPLE") case object Example extends ObligationLevelEnum

  /** The metadata element is allowed but discouraged to be present in the model
    */
  @named("DISCOURAGED") case object Discouraged extends ObligationLevelEnum
}
