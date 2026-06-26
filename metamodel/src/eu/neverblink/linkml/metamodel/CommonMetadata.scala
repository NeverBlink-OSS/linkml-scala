package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Generic metadata shared across definitions
  */
trait CommonMetadata {

  /** A concise human-readable display label for the element. The title should mirror the name, and
    * should use ordinary textual punctuation.
    *
    * @see
    *   Aliases: long name
    */
  def title: Option[String]

  /** A textual description of the element's purpose and use
    *
    * @see
    *   Aliases: definition
    */
  def description: Option[String]

  /** The relative order in which the element occurs, lower values are given precedence
    *
    * @see
    *   Aliases: order, precedence, display order
    * @note
    *   The rank of an element does not affect the semantics
    */
  def rank: Option[Int]

  /** Alternate names/labels for the element. These do not alter the semantics of the schema, but
    * may be useful to support search and alignment.
    *
    * @see
    *   Aliases: synonyms, alternate names, alternative labels, designations
    * @note
    *   Not be confused with the metaslot alias.
    */
  def aliases: Seq[String]

  /** A sourced alternative description for an element
    *
    * @see
    *   Aliases: alternate definitions
    */
  def altDescriptions: Map[String, AltDescriptionImpl]

  /** A list of terms from different schemas or terminology systems that have broader meaning.
    */
  def broadMappings: Seq[UriOrCurie]

  /** Controlled terms used to categorize an element.
    *
    * @note
    *   If you wish to use uncontrolled terms or terms that lack identifiers then use the keywords
    *   element
    */
  def categories: Seq[UriOrCurie]

  /** A list of terms from different schemas or terminology systems that have close meaning.
    */
  def closeMappings: Seq[UriOrCurie]

  /** Notes and comments about an element intended primarily for external consumption
    */
  def comments: Seq[String]

  /** Agent that contributed to the element
    */
  def contributors: Seq[UriOrCurie]

  /** Agent that created the element
    */
  def createdBy: Option[UriOrCurie]

  /** Time at which the element was created
    */
  def createdOn: Option[ZonedDateTime]

  /** Description of why and when this element will no longer be used
    *
    * @note
    *   Note that linkml does not use a boolean to indicate deprecation status - the presence of a
    *   string value in this field is sufficient to indicate deprecation.
    */
  def deprecated: Option[String]

  /** When an element is deprecated, it can be automatically replaced by this uri or curie
    */
  def deprecatedElementHasExactReplacement: Option[UriOrCurie]

  /** When an element is deprecated, it can be potentially replaced by this uri or curie
    */
  def deprecatedElementHasPossibleReplacement: Option[UriOrCurie]

  /** A list of terms from different schemas or terminology systems that have identical meaning.
    */
  def exactMappings: Seq[UriOrCurie]

  /** Example usages of an element
    */
  def examples: Seq[ExampleImpl]

  /** Id of the schema that defined the element
    *
    * @note
    *   A stronger model would be range schema_definition, but this doesn't address the import model
    */
  def fromSchema: Option[UriOrCurie]

  /** The imports entry that this element was derived from. Empty means primary source
    */
  def importedFrom: Option[String]

  /** The primary language used in the sources
    *
    * @note
    *   Recommended to use a string from IETF BCP 47
    */
  def inLanguage: Option[String]

  /** Used to indicate membership of a term in a defined subset of terms used for a particular
    * domain or application.
    *
    * @note
    *   An example of use in the translator_minimal subset in the biolink model, holding the minimal
    *   set of predicates used in a translator knowledge graph
    */
  def inSubset: Seq[Reference[SubsetDefinition]]

  /** Keywords or tags used to describe the element
    */
  def keywords: Seq[String]

  /** Time at which the element was last updated
    */
  def lastUpdatedOn: Option[ZonedDateTime]

  /** A list of terms from different schemas or terminology systems that have comparable meaning.
    * These may include terms that are precisely equivalent, broader or narrower in meaning, or
    * otherwise semantically related but not equivalent from a strict ontological perspective.
    *
    * @see
    *   Aliases: xrefs, identifiers, alternate identifiers, alternate ids
    */
  def mappings: Seq[UriOrCurie]

  /** Agent that modified the element
    */
  def modifiedBy: Option[UriOrCurie]

  /** A list of terms from different schemas or terminology systems that have narrower meaning.
    */
  def narrowMappings: Seq[UriOrCurie]

  /** Editorial notes about an element intended primarily for internal consumption
    */
  def notes: Seq[String]

  /** A list of terms from different schemas or terminology systems that have related meaning.
    */
  def relatedMappings: Seq[UriOrCurie]

  /** A list of related entities or URLs that may be of relevance
    */
  def seeAlso: Seq[UriOrCurie]

  /** A related resource from which the element is derived.
    *
    * @see
    *   Aliases: origin, derived from
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
    */
  def status: Option[UriOrCurie]

  /** A list of structured_alias objects, used to provide aliases in conjunction with additional
    * metadata.
    *
    * @see
    *   https://w3id.org/linkml/aliases
    */
  def structuredAliases: Seq[StructuredAliasImpl]

  /** Outstanding issues that needs resolution
    */
  def todos: Seq[String]
}
