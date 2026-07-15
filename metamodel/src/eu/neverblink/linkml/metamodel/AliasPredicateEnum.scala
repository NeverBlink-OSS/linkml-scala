package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Permissible values for the relationship between an element and an alias
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
sealed abstract class AliasPredicateEnum

object AliasPredicateEnum {

  /** @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("EXACT_SYNONYM") case object ExactSynonym extends AliasPredicateEnum

  /** @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("RELATED_SYNONYM") case object RelatedSynonym extends AliasPredicateEnum

  /** @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("BROAD_SYNONYM") case object BroadSynonym extends AliasPredicateEnum

  /** @see
    *   From schema: https://w3id.org/linkml/meta
    */
  @named("NARROW_SYNONYM") case object NarrowSynonym extends AliasPredicateEnum
}
