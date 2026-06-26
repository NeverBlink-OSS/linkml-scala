package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[ClassRule]] LinkML class
  *
  * @inheritdoc
  */
case class ClassRuleImpl(
    title: Option[String] = None,
    description: Option[String] = None,
    rank: Option[Int] = None,
    preconditions: Option[AnonymousClassExpressionImpl] = None,
    postconditions: Option[AnonymousClassExpressionImpl] = None,
    elseconditions: Option[AnonymousClassExpressionImpl] = None,
    aliases: Seq[String] = Seq(),
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    bidirectional: Boolean = false,
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
    deactivated: Boolean = false,
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
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    @named("open_world")
    openWorld: Boolean = false,
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
) extends ClassRule

/** A rule that applies to instances of a class
  *
  * @see
  *   Aliases: if rule
  */
abstract class ClassRule extends ClassLevelRule, Extensible, Annotatable, CommonMetadata {

  /** The relative order in which the element occurs, lower values are given precedence
    *
    * @see
    *   Aliases: order, precedence, display order
    * @note
    *   The rank of an element does not affect the semantics
    */
  def rank: Option[Int]

  /** An expression that must hold in order for the rule to be applicable to an instance
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   Aliases: if, body, antecedents
    */
  def preconditions: Option[AnonymousClassExpressionImpl]

  /** An expression that must hold for an instance of the class, if the preconditions hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   Aliases: then, head, consequents
    */
  def postconditions: Option[AnonymousClassExpressionImpl]

  /** An expression that must hold for an instance of the class, if the preconditions no not hold
    *
    * @see
    *   https://w3id.org/linkml/docs/specification/05validation/#rules
    * @see
    *   Aliases: else
    */
  def elseconditions: Option[AnonymousClassExpressionImpl]

  /** In addition to preconditions entailing postconditions, the postconditions entail the
    * preconditions
    *
    * @see
    *   Aliases: iff, if and only if
    */
  def bidirectional: Boolean

  /** A deactivated rule is not executed by the rules engine
    */
  def deactivated: Boolean

  /** If true, the the postconditions may be omitted in instance data, but it is valid for an
    * inference engine to add these
    */
  def openWorld: Boolean
}
