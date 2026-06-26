package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Enumeration of roles a slot on a relationship class can play
  */
sealed abstract class RelationalRoleEnum

object RelationalRoleEnum {

  /** A slot with this role connects a relationship to its subject/source node
    */
  @named("SUBJECT") case object Subject extends RelationalRoleEnum

  /** A slot with this role connects a relationship to its object/target node
    */
  @named("OBJECT") case object Object extends RelationalRoleEnum

  /** A slot with this role connects a relationship to its predicate/property
    */
  @named("PREDICATE") case object Predicate extends RelationalRoleEnum

  /** A slot with this role connects a symmetric relationship to a node that represents either
    * subject or object node
    */
  @named("NODE") case object Node extends RelationalRoleEnum

  /** A slot with this role connects a relationship to a node that is not subject/object/predicate
    */
  @named("OTHER_ROLE") case object OtherRole extends RelationalRoleEnum
}
