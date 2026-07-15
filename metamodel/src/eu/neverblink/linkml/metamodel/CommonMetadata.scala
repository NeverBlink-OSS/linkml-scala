package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Generic metadata shared across definitions
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
trait CommonMetadata {

  /** A concise human-readable display label for the element. The title should mirror the name, and
    * should use ordinary textual punctuation.
    *
    * @see
    *   Aliases: long name
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def title: Option[String]

  /** A textual description of the element's purpose and use
    *
    * @see
    *   Aliases: definition
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def description: Option[String]

  /** The relative order in which the element occurs, lower values are given precedence
    *
    * @see
    *   Aliases: order, precedence, display order
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The rank of an element does not affect the semantics
    */
  def rank: Option[Int]

  /** Alternate names/labels for the element. These do not alter the semantics of the schema, but
    * may be useful to support search and alignment.
    *
    * @see
    *   Aliases: synonyms, alternate names, alternative labels, designations
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Not be confused with the metaslot alias.
    */
  def aliases: Seq[String]

  /** A sourced alternative description for an element
    *
    * @see
    *   Aliases: alternate definitions
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def altDescriptions: Map[String, AltDescriptionImpl]

  /** A list of terms from different schemas or terminology systems that have broader meaning.
    *
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def broadMappings: Seq[UriOrCurie]

  /** Controlled terms used to categorize an element.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   If you wish to use uncontrolled terms or terms that lack identifiers then use the keywords
    *   element
    */
  def categories: Seq[UriOrCurie]

  /** A list of terms from different schemas or terminology systems that have close meaning.
    *
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def closeMappings: Seq[UriOrCurie]

  /** Notes and comments about an element intended primarily for external consumption
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def comments: Seq[String]

  /** Agent that contributed to the element
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def contributors: Seq[UriOrCurie]

  /** Agent that created the element
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def createdBy: Option[UriOrCurie]

  /** Time at which the element was created
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def createdOn: Option[LinkmlDateTime]

  /** Description of why and when this element will no longer be used
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Note that linkml does not use a boolean to indicate deprecation status - the presence of a
    *   string value in this field is sufficient to indicate deprecation.
    */
  def deprecated: Option[String]

  /** When an element is deprecated, it can be automatically replaced by this uri or curie
    *
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def deprecatedElementHasExactReplacement: Option[UriOrCurie]

  /** When an element is deprecated, it can be potentially replaced by this uri or curie
    *
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def deprecatedElementHasPossibleReplacement: Option[UriOrCurie]

  /** A list of terms from different schemas or terminology systems that have identical meaning.
    *
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def exactMappings: Seq[UriOrCurie]

  /** Example usages of an element
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def examples: Seq[ExampleImpl]

  /** Id of the schema that defined the element
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   A stronger model would be range schema_definition, but this doesn't address the import model
    */
  def fromSchema: Option[Uri]

  /** The imports entry that this element was derived from. Empty means primary source
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def importedFrom: Option[String]

  /** The primary language used in the sources
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Recommended to use a string from IETF BCP 47
    */
  def inLanguage: Option[String]

  /** Used to indicate membership of a term in a defined subset of terms used for a particular
    * domain or application.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   An example of use in the translator_minimal subset in the biolink model, holding the minimal
    *   set of predicates used in a translator knowledge graph
    */
  def inSubset: Seq[Reference[SubsetDefinition]]

  /** Keywords or tags used to describe the element
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def keywords: Seq[String]

  /** Time at which the element was last updated
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def lastUpdatedOn: Option[LinkmlDateTime]

  /** A list of terms from different schemas or terminology systems that have comparable meaning.
    * These may include terms that are precisely equivalent, broader or narrower in meaning, or
    * otherwise semantically related but not equivalent from a strict ontological perspective.
    *
    * @see
    *   Aliases: xrefs, identifiers, alternate identifiers, alternate ids
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def mappings: Seq[UriOrCurie]

  /** Agent that modified the element
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def modifiedBy: Option[UriOrCurie]

  /** A list of terms from different schemas or terminology systems that have narrower meaning.
    *
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def narrowMappings: Seq[UriOrCurie]

  /** Editorial notes about an element intended primarily for internal consumption
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def notes: Seq[String]

  /** A list of terms from different schemas or terminology systems that have related meaning.
    *
    * @see
    *   From schema: https://w3id.org/linkml/mappings
    */
  def relatedMappings: Seq[UriOrCurie]

  /** A list of related entities or URLs that may be of relevance
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def seeAlso: Seq[UriOrCurie]

  /** A related resource from which the element is derived.
    *
    * @see
    *   Aliases: origin, derived from
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The described resource may be derived from the related resource in whole or in part
    */
  def source: Option[UriOrCurie]

  /** Status of the element
    *
    * @see
    *   https://www.hl7.org/fhir/valueset-publication-status.html
    * @see
    *   https://www.hl7.org/fhir/versions.html#std-process
    * @see
    *   Aliases: workflow status
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def status: Option[UriOrCurie]

  /** A list of structured_alias objects, used to provide aliases in conjunction with additional
    * metadata.
    *
    * @see
    *   https://w3id.org/linkml/aliases
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def structuredAliases: Seq[StructuredAliasImpl]

  /** Outstanding issues that needs resolution
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def todos: Seq[String]
}
