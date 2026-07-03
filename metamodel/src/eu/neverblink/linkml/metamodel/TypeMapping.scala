package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[TypeMapping]] LinkML class
  *
  * @inheritdoc
  */
case class TypeMappingImpl(
    @id
    @named("framework")
    frameworkKey: String,
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
    @named("type")
    mappedType: Option[Reference[TypeDefinition]] = None,
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
    @named("string_serialization")
    stringSerialization: Option[String] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
) extends TypeMapping

/** Represents how a slot or type can be serialized to a format.
  */
abstract class TypeMapping extends Extensible, Annotatable, CommonMetadata {

  /** The name of a format that can be used to serialize LinkML data. The string value should be a
    * code from the LinkML frameworks vocabulary, but this is not strictly enforced
    */
  def frameworkKey: String

  /** Type to coerce to
    */
  def mappedType: Option[Reference[TypeDefinition]]

  /** Used on a slot that stores the string serialization of the containing object. The syntax
    * follows python formatted strings, with slot names enclosed in {}s. These are expanded using
    * the values of those slots.\nWe call the slot with the serialization the s-slot, the slots used
    * in the {}s are v-slots. If both s-slots and v-slots are populated on an object then the value
    * of the s-slot should correspond to the expansion.\nImplementations of frameworks may choose to
    * use this property to either (a) PARSE: implement automated normalizations by parsing
    * denormalized strings into complex objects (b) GENERATE: implement automated to_string labeling
    * of complex objects\nFor example, a Measurement class may have 3 fields: unit, value, and
    * string_value. The string_value slot may have a string_serialization of {value}{unit} such that
    * if unit=cm and value=2, the value of string_value shouldd be 2cm
    *
    * @see
    *   https://github.com/linkml/issues/128
    */
  def stringSerialization: Option[String]
}
