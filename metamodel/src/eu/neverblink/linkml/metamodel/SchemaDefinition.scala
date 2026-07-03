package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[SchemaDefinition]] LinkML class
  *
  * @inheritdoc
  */
case class SchemaDefinitionImpl(
    @id
    name: NcName,
    @value
    id: Uri,
    @compactDict
    classes: Map[String, ClassDefinitionImpl] = Map(),
    title: Option[String] = None,
    @named("slots")
    @compactDict
    slotDefinitions: Map[String, SlotDefinitionImpl] = Map(),
    description: Option[String] = None,
    @compactDict
    enums: Map[String, EnumDefinitionImpl] = Map(),
    @compactDict
    types: Map[String, TypeDefinitionImpl] = Map(),
    @compactDict
    subsets: Map[String, SubsetDefinitionImpl] = Map(),
    @simpleDict
    prefixes: Map[String, PrefixImpl] = Map(),
    @named("default_prefix")
    defaultPrefix: Option[String] = None,
    @named("default_range")
    defaultRange: Option[Reference[TypeDefinition]] = None,
    @simpleDict
    settings: Map[String, SettingImpl] = Map(),
    imports: Seq[UriOrCurie] = Seq(),
    license: Option[String] = None,
    rank: Option[Int] = None,
    aliases: Seq[String] = Seq(),
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    bindings: Seq[EnumBindingImpl] = Seq(),
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
    @named("default_curi_maps")
    defaultCuriMaps: Seq[String] = Seq(),
    @named("definition_uri")
    definitionUri: Option[UriOrCurie] = None,
    deprecated: Option[String] = None,
    @named("deprecated_element_has_exact_replacement")
    deprecatedElementHasExactReplacement: Option[UriOrCurie] = None,
    @named("deprecated_element_has_possible_replacement")
    deprecatedElementHasPossibleReplacement: Option[UriOrCurie] = None,
    @named("emit_prefixes")
    emitPrefixes: Seq[NcName] = Seq(),
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    examples: Seq[ExampleImpl] = Seq(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
    @named("from_schema")
    fromSchema: Option[Uri] = None,
    @named("generation_date")
    generationDate: Option[LinkmlDateTime] = None,
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
    instantiates: Seq[UriOrCurie] = Seq(),
    keywords: Seq[String] = Seq(),
    @named("last_updated_on")
    lastUpdatedOn: Option[LinkmlDateTime] = None,
    @named("local_names")
    @simpleDict
    localNames: Map[String, LocalNameImpl] = Map(),
    mappings: Seq[UriOrCurie] = Seq(),
    @named("metamodel_version")
    metamodelVersion: Option[String] = None,
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    @named("slot_names_unique")
    slotNamesUnique: Boolean = false,
    source: Option[UriOrCurie] = None,
    @named("source_file")
    sourceFile: Option[String] = None,
    @named("source_file_date")
    sourceFileDate: Option[LinkmlDateTime] = None,
    @named("source_file_size")
    sourceFileSize: Option[Int] = None,
    status: Option[UriOrCurie] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    todos: Seq[String] = Seq(),
    version: Option[String] = None,
) extends SchemaDefinition

/** A collection of definitions that make up a schema or a data model.
  *
  * @see
  *   https://en.wikipedia.org/wiki/Data_dictionary
  * @see
  *   Aliases: data dictionary, data model, information model, logical model, schema, model
  */
abstract class SchemaDefinition extends Element {

  /** A unique name for the schema that is both human-readable and consists of only characters from
    * the NCName set
    *
    * @see
    *   https://en.wikipedia.org/wiki/Data_element_name
    * @see
    *   https://linkml.io/linkml/faq/modeling.html#why-are-my-class-names-translated-to-camelcase
    * @see
    *   Aliases: short name, unique name
    */
  def name: NcName

  /** The official schema URI
    */
  def id: Uri

  /** An index to the collection of all class definitions in the schema
    */
  def classes: Map[String, ClassDefinitionImpl]

  /** An index to the collection of all slot definitions in the schema
    *
    * @note
    *   Note the formal name of this element is slot_definitions, but it has alias slots, which is
    *   the canonical form used in yaml/json serializes of schemas.
    */
  def slotDefinitions: Map[String, SlotDefinitionImpl]

  /** An index to the collection of all enum definitions in the schema
    */
  def enums: Map[String, EnumDefinitionImpl]

  /** An index to the collection of all type definitions in the schema
    */
  def types: Map[String, TypeDefinitionImpl]

  /** An index to the collection of all subset definitions in the schema
    */
  def subsets: Map[String, SubsetDefinitionImpl]

  /** A collection of prefix expansions that specify how CURIEs can be expanded to URIs
    */
  def prefixes: Map[String, PrefixImpl]

  /** The prefix that is used for all elements within a schema
    */
  def defaultPrefix: Option[String]

  /** Default slot range to be used if range element is omitted from a slot definition
    */
  def defaultRange: Option[Reference[TypeDefinition]]

  /** A collection of global variable settings
    *
    * @see
    *   Aliases: constants
    * @note
    *   Global variables are used in string interpolation in structured patterns
    */
  def settings: Map[String, SettingImpl]

  /** A list of schemas that are to be included in this schema
    */
  def imports: Seq[UriOrCurie]

  /** License for the schema
    */
  def license: Option[String]

  /** A collection of enum bindings that specify how a slot can be bound to a permissible value from
    * an enumeration.\nLinkML provides enums to allow string values to be restricted to one of a set
    * of permissible values (specified statically or dynamically).\nEnum bindings allow enums to be
    * bound to any object, including complex nested objects. For example, given a (generic) class
    * Concept with slots id and label, it may be desirable to restrict the values the id takes on in
    * a given context. For example, a HumanSample class may have a slot for representing sample
    * site, with a range of concept, but the values of that slot may be restricted to concepts from
    * a particular branch of an anatomy ontology.
    */
  def bindings: Seq[EnumBindingImpl]

  /** Ordered list of prefixcommon biocontexts to be fetched to resolve id prefixes and inline
    * prefix variables
    */
  def defaultCuriMaps: Seq[String]

  /** A list of Curie prefixes that are used in the representation of instances of the model. All
    * prefixes in this list are added to the prefix sections of the target models.
    */
  def emitPrefixes: Seq[NcName]

  /** Date and time that the schema was loaded/generated
    */
  def generationDate: Option[LinkmlDateTime]

  /** Version of the metamodel used to load the schema
    */
  def metamodelVersion: Option[String]

  /** If true then induced/mangled slot names are not created for class_usage and attributes
    */
  def slotNamesUnique: Boolean

  /** Name, uri or description of the source of the schema
    */
  def sourceFile: Option[String]

  /** Modification date of the source of the schema
    */
  def sourceFileDate: Option[LinkmlDateTime]

  /** Size in bytes of the source of the schema
    */
  def sourceFileSize: Option[Int]

  /** Particular version of schema
    */
  def version: Option[String]
}
