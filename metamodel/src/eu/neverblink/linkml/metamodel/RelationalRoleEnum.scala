package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Enumeration of roles a slot on a relationship class can play
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
sealed abstract class RelationalRoleEnum

object RelationalRoleEnum {

  /** A slot with this role connects a relationship to its subject/source node
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("SUBJECT") case object Subject extends RelationalRoleEnum

  /** A slot with this role connects a relationship to its object/target node
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("OBJECT") case object Object extends RelationalRoleEnum

  /** A slot with this role connects a relationship to its predicate/property
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("PREDICATE") case object Predicate extends RelationalRoleEnum

  /** A slot with this role connects a symmetric relationship to a node that represents either
    * subject or object node
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("NODE") case object Node extends RelationalRoleEnum

  /** A slot with this role connects a relationship to a node that is not subject/object/predicate
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("OTHER_ROLE") case object OtherRole extends RelationalRoleEnum
}
