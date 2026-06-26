package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[AnonymousClassExpression]] LinkML class
  *
  * @inheritdoc
  */
case class AnonymousClassExpressionImpl(
    title: Option[String] = None,
    description: Option[String] = None,
    @named("is_a")
    isA: Option[Reference[Definition]] = None,
    rank: Option[Int] = None,
    @named("any_of")
    anyOf: Seq[AnonymousClassExpressionImpl] = Seq(),
    @named("exactly_one_of")
    exactlyOneOf: Seq[AnonymousClassExpressionImpl] = Seq(),
    @named("none_of")
    noneOf: Seq[AnonymousClassExpressionImpl] = Seq(),
    @named("all_of")
    allOf: Seq[AnonymousClassExpressionImpl] = Seq(),
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
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    @named("slot_conditions")
    @compactDict
    slotConditions: Map[String, SlotDefinitionImpl] = Map(),
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
) extends AnonymousClassExpression

abstract class AnonymousClassExpression extends AnonymousExpression, ClassExpression {

  /** A primary parent class or slot from which inheritable metaslots are propagated from. While
    * multiple inheritance is not allowed, mixins can be provided effectively providing the same
    * thing. The semantics are the same when translated to formalisms that allow MI (e.g. RDFS/OWL).
    * When translating to a SI framework (e.g. java classes, python classes) then is a is used. When
    * translating a framework without polymorphism (e.g. json-schema, solr document schema) then is
    * a and mixins are recursively unfolded
    */
  def isA: Option[Reference[Definition]]
}
