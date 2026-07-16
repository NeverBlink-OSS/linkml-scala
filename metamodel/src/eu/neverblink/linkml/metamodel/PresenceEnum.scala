package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Enumeration of conditions by which a slot value should be set
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
sealed abstract class PresenceEnum

object PresenceEnum {

  /** @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("UNCOMMITTED") case object Uncommitted extends PresenceEnum

  /** @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("PRESENT") case object Present extends PresenceEnum

  /** @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("ABSENT") case object Absent extends PresenceEnum
}
