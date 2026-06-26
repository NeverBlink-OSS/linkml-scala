package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Permissible values for the relationship between an element and an alias
  */
sealed abstract class AliasPredicateEnum

object AliasPredicateEnum {
  @named("EXACT_SYNONYM") case object ExactSynonym extends AliasPredicateEnum
  @named("RELATED_SYNONYM") case object RelatedSynonym extends AliasPredicateEnum
  @named("BROAD_SYNONYM") case object BroadSynonym extends AliasPredicateEnum
  @named("NARROW_SYNONYM") case object NarrowSynonym extends AliasPredicateEnum
}
