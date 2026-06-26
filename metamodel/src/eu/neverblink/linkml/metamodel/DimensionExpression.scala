package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[DimensionExpression]] LinkML class
  *
  * @inheritdoc
  */
case class DimensionExpressionImpl(
    title: Option[String] = None,
    description: Option[String] = None,
    alias: Option[String] = None,
    rank: Option[Int] = None,
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
    createdOn: Option[ZonedDateTime] = None,
    deprecated: Option[String] = None,
    @named("deprecated_element_has_exact_replacement")
    deprecatedElementHasExactReplacement: Option[UriOrCurie] = None,
    @named("deprecated_element_has_possible_replacement")
    deprecatedElementHasPossibleReplacement: Option[UriOrCurie] = None,
    @named("exact_cardinality")
    exactCardinality: Option[Int] = None,
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    examples: Seq[ExampleImpl] = Seq(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
    @named("from_schema")
    fromSchema: Option[UriOrCurie] = None,
    @named("imported_from")
    importedFrom: Option[String] = None,
    @named("in_language")
    inLanguage: Option[String] = None,
    @named("in_subset")
    inSubset: Seq[Reference[SubsetDefinition]] = Seq(),
    keywords: Seq[String] = Seq(),
    @named("last_updated_on")
    lastUpdatedOn: Option[ZonedDateTime] = None,
    mappings: Seq[UriOrCurie] = Seq(),
    @named("maximum_cardinality")
    maximumCardinality: Option[Int] = None,
    @named("minimum_cardinality")
    minimumCardinality: Option[Int] = None,
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
) extends DimensionExpression

/** Defines one of the dimensions of an array
  */
abstract class DimensionExpression extends Extensible, Annotatable, CommonMetadata {

  /** The name used for a slot in the context of its owning class. If present, this is used instead
    * of the actual slot name.
    *
    * @note
    *   An example of alias is used within this metamodel, slot_definitions is aliases as slots
    * @note
    *   Not to be confused with aliases, which indicates a set of terms to be used for search
    *   purposes.
    */
  def alias: Option[String]

  /** The exact number of entries for a multivalued slot
    *
    * @note
    *   If exact_cardinality is set, then minimum_cardinalty and maximum_cardinality must be unset
    *   or have the same value
    */
  def exactCardinality: Option[Int]

  /** The maximum number of entries for a multivalued slot
    *
    * @note
    *   Maximum_cardinality cannot be less than minimum_cardinality
    */
  def maximumCardinality: Option[Int]

  /** The minimum number of entries for a multivalued slot
    *
    * @note
    *   Minimum_cardinality cannot be greater than maximum_cardinality
    */
  def minimumCardinality: Option[Int]
}
