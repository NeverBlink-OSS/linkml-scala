package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Enumeration of conditions by which a slot value should be set
  */
sealed abstract class PresenceEnum

object PresenceEnum {
  @named("UNCOMMITTED") case object Uncommitted extends PresenceEnum
  @named("PRESENT") case object Present extends PresenceEnum
  @named("ABSENT") case object Absent extends PresenceEnum
}
