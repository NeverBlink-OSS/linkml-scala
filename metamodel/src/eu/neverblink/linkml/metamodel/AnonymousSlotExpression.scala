package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[AnonymousSlotExpression]] LinkML class
  *
  * @inheritdoc
  */
case class AnonymousSlotExpressionImpl(
    title: Option[String] = None,
    description: Option[String] = None,
    multivalued: Boolean = false,
    required: Boolean = false,
    recommended: Boolean = false,
    inlined: Boolean = false,
    @named("inlined_as_list")
    inlinedAsList: Boolean = false,
    pattern: Option[String] = None,
    rank: Option[Int] = None,
    @named("any_of")
    anyOf: Seq[AnonymousSlotExpressionImpl] = Seq(),
    @named("exactly_one_of")
    exactlyOneOf: Seq[AnonymousSlotExpressionImpl] = Seq(),
    @named("none_of")
    noneOf: Seq[AnonymousSlotExpressionImpl] = Seq(),
    @named("all_of")
    allOf: Seq[AnonymousSlotExpressionImpl] = Seq(),
    aliases: Seq[String] = Seq(),
    @named("all_members")
    allMembers: Option[AnonymousSlotExpressionImpl] = None,
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    array: Option[ArrayExpressionImpl] = None,
    bindings: Seq[EnumBindingImpl] = Seq(),
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
    @named("enum_range")
    enumRange: Option[EnumExpressionImpl] = None,
    @named("equals_expression")
    equalsExpression: Option[String] = None,
    @named("equals_number")
    equalsNumber: Option[Int] = None,
    @named("equals_string")
    equalsString: Option[String] = None,
    @named("equals_string_in")
    equalsStringIn: Seq[String] = Seq(),
    @named("exact_cardinality")
    exactCardinality: Option[Int] = None,
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    examples: Seq[ExampleImpl] = Seq(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
    @named("from_schema")
    fromSchema: Option[Uri] = None,
    @named("has_member")
    hasMember: Option[AnonymousSlotExpressionImpl] = None,
    @named("implicit_prefix")
    implicitPrefix: Option[String] = None,
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
    @named("maximum_cardinality")
    maximumCardinality: Option[Int] = None,
    @named("maximum_value")
    maximumValue: Option[Anything] = None,
    @named("minimum_cardinality")
    minimumCardinality: Option[Int] = None,
    @named("minimum_value")
    minimumValue: Option[Anything] = None,
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    range: Option[Reference[Element]] = None,
    @named("range_expression")
    rangeExpression: Option[AnonymousClassExpressionImpl] = None,
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    @named("structured_pattern")
    structuredPattern: Option[PatternExpressionImpl] = None,
    todos: Seq[String] = Seq(),
    unit: Option[UnitOfMeasureImpl] = None,
    @named("value_presence")
    valuePresence: Option[Reference[PresenceEnum]] = None,
) extends AnonymousSlotExpression

abstract class AnonymousSlotExpression extends AnonymousExpression, SlotExpression {}
