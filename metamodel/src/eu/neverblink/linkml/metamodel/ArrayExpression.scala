package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[ArrayExpression]] LinkML class
  *
  * @inheritdoc
  */
case class ArrayExpressionImpl(
    title: Option[String] = None,
    description: Option[String] = None,
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
    dimensions: Seq[DimensionExpressionImpl] = Seq(),
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    @named("exact_number_dimensions")
    exactNumberDimensions: Option[Int] = None,
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
    lastUpdatedOn: Option[ZonedDateTime] = None,
    mappings: Seq[UriOrCurie] = Seq(),
    @named("maximum_number_dimensions")
    maximumNumberDimensions: Option[Anything] = None,
    @named("minimum_number_dimensions")
    minimumNumberDimensions: Option[Int] = None,
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
) extends ArrayExpression

/** Defines the dimensions of an array
  */
abstract class ArrayExpression extends Extensible, Annotatable, CommonMetadata {

  /** Definitions of each axis in the array
    *
    * @see
    *   Aliases: axes
    */
  def dimensions: Seq[DimensionExpressionImpl]

  /** Exact number of dimensions in the array
    *
    * @note
    *   If exact_number_dimensions is set, then minimum_number_dimensions and
    *   maximum_number_dimensions must be unset or have the same value
    */
  def exactNumberDimensions: Option[Int]

  /** Maximum number of dimensions in the array, or False if explicitly no maximum. If this is
    * unset, and an explicit list of dimensions are passed using dimensions, then this is
    * interpreted as a closed list and the maximum_number_dimensions is the length of the dimensions
    * list, unless this value is set to False
    *
    * @note
    *   Maximum_number_dimensions cannot be less than minimum_number_dimensions
    */
  def maximumNumberDimensions: Option[Anything]

  /** Minimum number of dimensions in the array
    *
    * @note
    *   Minimum_cardinality cannot be greater than maximum_cardinality
    */
  def minimumNumberDimensions: Option[Int]
}
