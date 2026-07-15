package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[StructuredAlias]] LinkML class
  *
  * @inheritdoc
  */
case class StructuredAliasImpl(
    title: Option[String] = None,
    description: Option[String] = None,
    rank: Option[Int] = None,
    @named("contexts")
    aliasContexts: Seq[Uri] = Seq(),
    @named("predicate")
    aliasPredicate: Option[Reference[AliasPredicateEnum]] = None,
    aliases: Seq[String] = Seq(),
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    @named("broad_mappings")
    broadMappings: Seq[UriOrCurie] = Seq(),
    categories: Seq[UriOrCurie] = Seq(),
    @named("close_mappings")
    closeMappings: Seq[UriOrCurie] = Seq(),
    comments: Seq[String] = Seq(),
    contributors: Seq[UriOrCurie] = Seq(),
    @named("created_by")
    createdBy: Option[UriOrCurie] = None,
    @named("created_on")
    createdOn: Option[LinkmlDateTime] = None,
    deprecated: Option[String] = None,
    @named("deprecated_element_has_exact_replacement")
    deprecatedElementHasExactReplacement: Option[UriOrCurie] = None,
    @named("deprecated_element_has_possible_replacement")
    deprecatedElementHasPossibleReplacement: Option[UriOrCurie] = None,
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    examples: Seq[ExampleImpl] = Seq(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
    @named("from_schema")
    fromSchema: Option[Uri] = None,
    @named("imported_from")
    importedFrom: Option[String] = None,
    @named("in_language")
    inLanguage: Option[String] = None,
    @named("in_subset")
    inSubset: Seq[Reference[SubsetDefinition]] = Seq(),
    keywords: Seq[String] = Seq(),
    @named("last_updated_on")
    lastUpdatedOn: Option[LinkmlDateTime] = None,
    @named("literal_form")
    literalForm: String,
    mappings: Seq[UriOrCurie] = Seq(),
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
) extends StructuredAlias

/** Object that contains meta data about a synonym or alias including where it came from (source)
  * and its scope (narrow, broad, etc.)
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class StructuredAlias extends Expression, Extensible, Annotatable, CommonMetadata {

  /** The context in which an alias should be applied
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def aliasContexts: Seq[Uri]

  /** The relationship between an element and its alias.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def aliasPredicate: Option[Reference[AliasPredicateEnum]]

  /** The category or categories of an alias. This can be drawn from any relevant vocabulary
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   If you wish to use uncontrolled terms or terms that lack identifiers then use the keywords
    *   element
    * @example
    *   `https://w3id.org/mod#acronym`: An acronym
    */
  def categories: Seq[UriOrCurie]

  /** The literal lexical form of a structured alias; i.e the actual alias value.
    *
    * @see
    *   Aliases: alias_name, string_value
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def literalForm: String
}
