package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[PathExpression]] LinkML class
  *
  * @inheritdoc
  */
case class PathExpressionImpl(
    title: Option[String] = None,
    description: Option[String] = None,
    rank: Option[Int] = None,
    @named("any_of")
    anyOf: Seq[PathExpressionImpl] = Seq(),
    @named("exactly_one_of")
    exactlyOneOf: Seq[PathExpressionImpl] = Seq(),
    @named("none_of")
    noneOf: Seq[PathExpressionImpl] = Seq(),
    @named("all_of")
    allOf: Seq[PathExpressionImpl] = Seq(),
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
    @named("followed_by")
    followedBy: Option[PathExpressionImpl] = None,
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
    mappings: Seq[UriOrCurie] = Seq(),
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    @named("range_expression")
    rangeExpression: Option[AnonymousClassExpressionImpl] = None,
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    reversed: Boolean = false,
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
    traverse: Option[Reference[SlotDefinition]] = None,
) extends PathExpression

/** An expression that describes an abstract path from an object to another through a sequence of
  * slot lookups
  *
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class PathExpression extends Expression, Extensible, Annotatable, CommonMetadata {

  /** Holds if at least one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def anyOf: Seq[PathExpressionImpl]

  /** Holds if only one of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def exactlyOneOf: Seq[PathExpressionImpl]

  /** Holds if none of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def noneOf: Seq[PathExpressionImpl]

  /** Holds if all of the expressions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def allOf: Seq[PathExpressionImpl]

  /** In a sequential list, this indicates the next member
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def followedBy: Option[PathExpressionImpl]

  /** A range that is described as a boolean expression combining existing ranges
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   One use for this is being able to describe a range using any_of expressions, for example to
    *   combine two enums
    */
  def rangeExpression: Option[AnonymousClassExpressionImpl]

  /** True if the slot is to be inversed
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def reversed: Boolean

  /** The slot to traverse
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def traverse: Option[Reference[SlotDefinition]]
}
