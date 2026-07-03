package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[EnumBinding]] LinkML class
  *
  * @inheritdoc
  */
case class EnumBindingImpl(
    title: Option[String] = None,
    description: Option[String] = None,
    rank: Option[Int] = None,
    aliases: Seq[String] = Seq(),
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    @named("binds_value_of")
    bindsValueOf: Option[String] = None,
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
    mappings: Seq[UriOrCurie] = Seq(),
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    @named("obligation_level")
    obligationLevel: Option[Reference[ObligationLevelEnum]] = None,
    @named("pv_formula")
    pvFormula: Option[Reference[PvFormulaOptions]] = None,
    range: Option[Reference[EnumDefinition]] = None,
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
) extends EnumBinding

/** A binding of a slot or a class to a permissible value from an enumeration.
  */
abstract class EnumBinding extends Extensible, Annotatable, CommonMetadata {

  /** A path to a slot that is being bound to a permissible value from an enumeration.
    */
  def bindsValueOf: Option[String]

  /** The level of obligation or recommendation strength for a metadata element
    */
  def obligationLevel: Option[Reference[ObligationLevelEnum]]

  /** Defines the specific formula to be used to generate the permissible values.
    *
    * @note
    *   You cannot have BOTH the permissible_values and permissible_value_formula tag
    * @note
    *   Code_set must be supplied for this to be valid
    */
  def pvFormula: Option[Reference[PvFormulaOptions]]

  /** Defines the type of the object of the slot. Given the following slot definition S1: domain: C1
    * range: C2 the declaration X: S1: Y
    *
    * implicitly asserts Y is an instance of C2
    *
    * @see
    *   Aliases: value domain
    * @note
    *   Range is underspecified, as not all elements can appear as the range of a slot.
    * @note
    *   To use a URI or CURIE as the range, create a class with the URI or curie as the class_uri
    */
  def range: Option[Reference[EnumDefinition]]
}
