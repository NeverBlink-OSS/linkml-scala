package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[TypeDefinition]] LinkML class
  *
  * @inheritdoc
  */
case class TypeDefinitionImpl(
    @id
    name: String,
    @named("uri")
    typeUri: Option[UriOrCurie] = None,
    title: Option[String] = None,
    description: Option[String] = None,
    typeof: Option[Reference[TypeDefinition]] = None,
    base: Option[String] = None,
    repr: Option[String] = None,
    pattern: Option[String] = None,
    rank: Option[Int] = None,
    @named("any_of")
    anyOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
    @named("exactly_one_of")
    exactlyOneOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
    @named("none_of")
    noneOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
    @named("all_of")
    allOf: Seq[AnonymousTypeExpressionImpl] = Seq(),
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
    @named("equals_number")
    equalsNumber: Option[Int] = None,
    @named("equals_string")
    equalsString: Option[String] = None,
    @named("equals_string_in")
    equalsStringIn: Seq[String] = Seq(),
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
    @named("implicit_prefix")
    implicitPrefix: Option[String] = None,
    @named("imported_from")
    importedFrom: Option[String] = None,
    @named("in_language")
    inLanguage: Option[String] = None,
    @named("in_subset")
    inSubset: Seq[Reference[SubsetDefinition]] = Seq(),
    instantiates: Seq[UriOrCurie] = Seq(),
    keywords: Seq[String] = Seq(),
    @named("last_updated_on")
    lastUpdatedOn: Option[LinkmlDateTime] = None,
    @named("local_names")
    @simpleDict
    localNames: Map[String, LocalNameImpl] = Map(),
    mappings: Seq[UriOrCurie] = Seq(),
    @named("maximum_value")
    maximumValue: Option[Anything] = None,
    @named("minimum_value")
    minimumValue: Option[Anything] = None,
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
    @named("structured_pattern")
    structuredPattern: Option[PatternExpressionImpl] = None,
    todos: Seq[String] = Seq(),
    @named("union_of")
    unionOf: Seq[Reference[TypeDefinition]] = Seq(),
    unit: Option[UnitOfMeasureImpl] = None,
) extends TypeDefinition

/** An element that whose instances are atomic scalar values that can be mapped to primitive types
  */
abstract class TypeDefinition extends Element, TypeExpression {

  /** The uri that defines the possible values for the type definition
    *
    * @note
    *   Uri is typically drawn from the set of URI's defined in OWL
    *   (https://www.w3.org/TR/2012/REC-owl2-syntax-20121211/#Datatype_Maps)
    * @note
    *   Every root type must have a type uri
    */
  def typeUri: Option[UriOrCurie]

  /** A parent type from which type properties are inherited
    *
    * @note
    *   The target type definition of the typeof slot is referred to as the "parent type"
    * @note
    *   The type definition containing the typeof slot is referred to as the "child type"
    * @note
    *   Type definitions without a typeof slot are referred to as a "root type"
    */
  def typeof: Option[Reference[TypeDefinition]]

  /** Python base type in the LinkML runtime that implements this type definition
    *
    * @note
    *   Every root type must have a base
    * @note
    *   The base is inherited by child types but may be overridden. Base compatibility is not
    *   checked.
    */
  def base: Option[String]

  /** The name of the python object that implements this type definition
    */
  def repr: Option[String]

  /** Indicates that the domain element consists exactly of the members of the element in the range.
    *
    * @note
    *   This only applies in the OWL generation
    */
  def unionOf: Seq[Reference[TypeDefinition]]
}
