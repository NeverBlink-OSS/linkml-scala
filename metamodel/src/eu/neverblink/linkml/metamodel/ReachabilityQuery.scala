package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[ReachabilityQuery]] LinkML class
  *
  * @inheritdoc
  */
case class ReachabilityQueryImpl(
    @named("include_self")
    includeSelf: Boolean = false,
    @named("is_direct")
    isDirect: Boolean = false,
    @named("relationship_types")
    relationshipTypes: Seq[UriOrCurie] = Seq(),
    @named("source_nodes")
    sourceNodes: Seq[UriOrCurie] = Seq(),
    @named("source_ontology")
    sourceOntology: Option[UriOrCurie] = None,
    @named("traverse_up")
    traverseUp: Boolean = false,
) extends ReachabilityQuery

/** A query that is used on an enum expression to dynamically obtain a set of permissible values via
  * walking from a set of source nodes to a set of descendants or ancestors over a set of
  * relationship types.
  */
abstract class ReachabilityQuery {

  /** True if the query is reflexive
    *
    * @see
    *   Aliases: reflexive
    */
  def includeSelf: Boolean

  /** True if the reachability query should only include directly related nodes, if False then
    * include also transitively connected
    *
    * @see
    *   Aliases: non-transitive
    */
  def isDirect: Boolean

  /** A list of relationship types (properties) that are used in a reachability query
    *
    * @see
    *   Aliases: predicates, properties
    */
  def relationshipTypes: Seq[UriOrCurie]

  /** A list of nodes that are used in the reachability query
    */
  def sourceNodes: Seq[UriOrCurie]

  /** An ontology or vocabulary or terminology that is used in a query to obtain a set of
    * permissible values
    *
    * @see
    *   Aliases: terminology, vocabulary
    * @note
    *   Examples include schema.org, wikidata, or an OBO ontology
    * @note
    *   For obo ontologies we recommend CURIEs of the form obo:cl, obo:envo, etc
    */
  def sourceOntology: Option[UriOrCurie]

  /** True if the direction of the reachability query is reversed and ancestors are retrieved
    *
    * @see
    *   Aliases: ancestors
    */
  def traverseUp: Boolean
}
