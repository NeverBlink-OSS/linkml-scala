package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[EnumDefinition]] LinkML class
  *
  * @inheritdoc
  */
case class EnumDefinitionImpl(
    @id
    name: String,
    title: Option[String] = None,
    description: Option[String] = None,
    @named("is_a")
    isA: Option[Reference[Definition]] = None,
    mixins: Seq[Reference[Definition]] = Seq(),
    rank: Option[Int] = None,
    @named("abstract")
    `abstract`: Boolean = false,
    aliases: Seq[String] = Seq(),
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    @named("apply_to")
    applyTo: Seq[Reference[Definition]] = Seq(),
    @named("broad_mappings")
    broadMappings: Seq[UriOrCurie] = Seq(),
    categories: Seq[UriOrCurie] = Seq(),
    @named("close_mappings")
    closeMappings: Seq[UriOrCurie] = Seq(),
    @named("code_set")
    codeSet: Option[UriOrCurie] = None,
    @named("code_set_tag")
    codeSetTag: Option[String] = None,
    @named("code_set_version")
    codeSetVersion: Option[String] = None,
    comments: Seq[String] = Seq(),
    concepts: Seq[UriOrCurie] = Seq(),
    @named("conforms_to")
    conformsTo: Option[String] = None,
    contributors: Seq[UriOrCurie] = Seq(),
    @named("created_by")
    createdBy: Option[UriOrCurie] = None,
    @named("created_on")
    createdOn: Option[LinkmlDateTime] = None,
    @named("definition_uri")
    definitionUri: Option[UriOrCurie] = None,
    deprecated: Option[String] = None,
    @named("deprecated_element_has_exact_replacement")
    deprecatedElementHasExactReplacement: Option[UriOrCurie] = None,
    @named("deprecated_element_has_possible_replacement")
    deprecatedElementHasPossibleReplacement: Option[UriOrCurie] = None,
    @named("enum_uri")
    enumUri: Option[UriOrCurie] = None,
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    examples: Seq[ExampleImpl] = Seq(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
    @named("from_schema")
    fromSchema: Option[Uri] = None,
    @named("id_prefixes")
    idPrefixes: Seq[NcName] = Seq(),
    @named("id_prefixes_are_closed")
    idPrefixesAreClosed: Boolean = false,
    implements: Seq[UriOrCurie] = Seq(),
    @named("imported_from")
    importedFrom: Option[String] = None,
    @named("in_language")
    inLanguage: Option[String] = None,
    @named("in_subset")
    inSubset: Seq[Reference[SubsetDefinition]] = Seq(),
    include: Seq[AnonymousEnumExpressionImpl] = Seq(),
    inherits: Seq[Reference[EnumDefinition]] = Seq(),
    instantiates: Seq[UriOrCurie] = Seq(),
    keywords: Seq[String] = Seq(),
    @named("last_updated_on")
    lastUpdatedOn: Option[LinkmlDateTime] = None,
    @named("local_names")
    @simpleDict
    localNames: Map[String, LocalNameImpl] = Map(),
    mappings: Seq[UriOrCurie] = Seq(),
    matches: Option[MatchQueryImpl] = None,
    minus: Seq[AnonymousEnumExpressionImpl] = Seq(),
    mixin: Boolean = false,
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    @named("permissible_values")
    @compactDict
    permissibleValues: Map[String, PermissibleValueImpl] = Map(),
    @named("pv_formula")
    pvFormula: Option[Reference[PvFormulaOptions]] = None,
    @named("reachable_from")
    reachableFrom: Option[ReachabilityQueryImpl] = None,
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("string_serialization")
    stringSerialization: Option[String] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
    @named("values_from")
    valuesFrom: Seq[UriOrCurie] = Seq(),
) extends EnumDefinition

/** An element whose instances must be drawn from a specified set of permissible values
  *
  * @see
  *   Aliases: enum, enumeration, semantic enumeration, value set, term set, concept set, code set,
  *   Terminology Value Set, answer list, value domain
  */
abstract class EnumDefinition extends Definition, EnumExpression {

  /** URI of the enum that provides a semantic interpretation of the element in a linked data
    * context. The URI may come from any namespace and may be shared between schemas
    *
    * @see
    *   Aliases: public ID
    */
  def enumUri: Option[UriOrCurie]
}
