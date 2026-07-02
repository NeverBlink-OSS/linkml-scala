package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[PermissibleValue]] LinkML class
  *
  * @inheritdoc
  */
case class PermissibleValueImpl(
    @id
    text: String,
    title: Option[String] = None,
    description: Option[String] = None,
    @named("is_a")
    isA: Option[Reference[PermissibleValue]] = None,
    mixins: Seq[Reference[PermissibleValue]] = Seq(),
    meaning: Option[UriOrCurie] = None,
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
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    examples: Seq[ExampleImpl] = Seq(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
    @named("from_schema")
    fromSchema: Option[Uri] = None,
    implements: Seq[UriOrCurie] = Seq(),
    @named("imported_from")
    importedFrom: Option[String] = None,
    @named("in_language")
    inLanguage: Option[String] = None,
    @named("in_subset")
    inSubset: Seq[Reference[SubsetDefinition]] = Seq(),
    instantiates: Seq[UriOrCurie] = Seq(),
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
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
    unit: Option[UnitOfMeasureImpl] = None,
) extends PermissibleValue

/** A permissible value, accompanied by intended text and an optional mapping to a concept URI
  *
  * @see
  *   Aliases: PV
  */
abstract class PermissibleValue extends Extensible, Annotatable, CommonMetadata {

  /** The actual permissible value itself
    *
    * @see
    *   Aliases: value
    * @note
    *   There are no constraints on the text of the permissible value, but for many applications you
    *   may want to consider following idiomatic forms and using computer-friendly forms
    */
  def text: String

  /** A textual description of the element's purpose and use
    *
    * @see
    *   Aliases: definition
    */
  def description: Option[String]

  /** A primary parent class or slot from which inheritable metaslots are propagated from. While
    * multiple inheritance is not allowed, mixins can be provided effectively providing the same
    * thing. The semantics are the same when translated to formalisms that allow MI (e.g. RDFS/OWL).
    * When translating to a SI framework (e.g. java classes, python classes) then is a is used. When
    * translating a framework without polymorphism (e.g. json-schema, solr document schema) then is
    * a and mixins are recursively unfolded
    */
  def isA: Option[Reference[PermissibleValue]]

  /** A collection of secondary parent classes or slots from which inheritable metaslots are
    * propagated from.
    *
    * @see
    *   https://en.wikipedia.org/wiki/Mixin
    * @see
    *   Aliases: traits
    * @note
    *   Mixins act in the same way as parents (is_a). They allow a model to have a primary strict
    *   hierarchy, while keeping the benefits of multiple inheritance
    */
  def mixins: Seq[Reference[PermissibleValue]]

  /** The value meaning of a permissible value
    *
    * @see
    *   https://en.wikipedia.org/wiki/ISO/IEC_11179
    * @see
    *   Aliases: PV meaning
    * @note
    *   We may want to change the range of this (and other) elements in the model to an
    *   entitydescription type construct
    */
  def meaning: Option[UriOrCurie]

  /** An element in another schema which this element conforms to. The referenced element is not
    * imported into the schema for the implementing element. However, the referenced schema may be
    * used to check conformance of the implementing element.
    */
  def implements: Seq[UriOrCurie]

  /** An element in another schema which this element instantiates.
    */
  def instantiates: Seq[UriOrCurie]

  /** An encoding of a unit
    */
  def unit: Option[UnitOfMeasureImpl]
}
