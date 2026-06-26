package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[MatchQuery]] LinkML class
  *
  * @inheritdoc
  */
case class MatchQueryImpl(
    @named("identifier_pattern")
    identifierPattern: Option[String] = None,
    @named("source_ontology")
    sourceOntology: Option[UriOrCurie] = None,
) extends MatchQuery

/** A query that is used on an enum expression to dynamically obtain a set of permissible values via
  * a query that matches on properties of the external concepts.
  */
abstract class MatchQuery {

  /** A regular expression that is used to obtain a set of identifiers from a source_ontology to
    * construct a set of permissible values
    */
  def identifierPattern: Option[String]

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
}
