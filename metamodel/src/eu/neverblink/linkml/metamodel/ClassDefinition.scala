package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[ClassDefinition]] LinkML class
  *
  * @inheritdoc
  */
case class ClassDefinitionImpl(
    @id
    name: String,
    @named("class_uri")
    classUri: Option[UriOrCurie] = None,
    title: Option[String] = None,
    description: Option[String] = None,
    alias: Option[String] = None,
    @named("is_a")
    isA: Option[Reference[ClassDefinition]] = None,
    mixins: Seq[Reference[ClassDefinition]] = Seq(),
    slots: Seq[Reference[SlotDefinition]] = Seq(),
    @named("slot_usage")
    @compactDict
    slotUsage: Map[String, SlotDefinitionImpl] = Map(),
    @compactDict
    attributes: Map[String, SlotDefinitionImpl] = Map(),
    @named("tree_root")
    treeRoot: Boolean = false,
    rank: Option[Int] = None,
    @named("any_of")
    anyOf: Seq[AnonymousClassExpressionImpl] = Seq(),
    @named("exactly_one_of")
    exactlyOneOf: Seq[AnonymousClassExpressionImpl] = Seq(),
    @named("none_of")
    noneOf: Seq[AnonymousClassExpressionImpl] = Seq(),
    @named("all_of")
    allOf: Seq[AnonymousClassExpressionImpl] = Seq(),
    @named("abstract")
    `abstract`: Boolean = false,
    aliases: Seq[String] = Seq(),
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    @named("apply_to")
    applyTo: Seq[Reference[ClassDefinition]] = Seq(),
    @named("broad_mappings")
    broadMappings: Seq[UriOrCurie] = Seq(),
    categories: Seq[UriOrCurie] = Seq(),
    @named("children_are_mutually_disjoint")
    childrenAreMutuallyDisjoint: Boolean = false,
    @named("classification_rules")
    classificationRules: Seq[AnonymousClassExpressionImpl] = Seq(),
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
    @named("defining_slots")
    definingSlots: Seq[Reference[SlotDefinition]] = Seq(),
    @named("definition_uri")
    definitionUri: Option[UriOrCurie] = None,
    deprecated: Option[String] = None,
    @named("deprecated_element_has_exact_replacement")
    deprecatedElementHasExactReplacement: Option[UriOrCurie] = None,
    @named("deprecated_element_has_possible_replacement")
    deprecatedElementHasPossibleReplacement: Option[UriOrCurie] = None,
    @named("disjoint_with")
    disjointWith: Seq[Reference[ClassDefinition]] = Seq(),
    @named("exact_mappings")
    exactMappings: Seq[UriOrCurie] = Seq(),
    examples: Seq[ExampleImpl] = Seq(),
    @simpleDict
    extensions: Map[String, ExtensionImpl] = Map(),
    @named("extra_slots")
    extraSlots: Option[ExtraSlotsExpressionImpl] = None,
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
    instantiates: Seq[UriOrCurie] = Seq(),
    keywords: Seq[String] = Seq(),
    @named("last_updated_on")
    lastUpdatedOn: Option[LinkmlDateTime] = None,
    @named("local_names")
    @simpleDict
    localNames: Map[String, LocalNameImpl] = Map(),
    mappings: Seq[UriOrCurie] = Seq(),
    mixin: Boolean = false,
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("represents_relationship")
    representsRelationship: Boolean = false,
    rules: Seq[ClassRuleImpl] = Seq(),
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    @named("slot_conditions")
    @compactDict
    slotConditions: Map[String, SlotDefinitionImpl] = Map(),
    @named("slot_names_unique")
    slotNamesUnique: Boolean = false,
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("string_serialization")
    stringSerialization: Option[String] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    @named("subclass_of")
    subclassOf: Option[UriOrCurie] = None,
    todos: Seq[String] = Seq(),
    @named("union_of")
    unionOf: Seq[Reference[ClassDefinition]] = Seq(),
    @named("unique_keys")
    @simpleDict
    uniqueKeys: Map[String, UniqueKeyImpl] = Map(),
    @named("values_from")
    valuesFrom: Seq[UriOrCurie] = Seq(),
) extends ClassDefinition

/** An element whose instances are complex objects that may have slot-value assignments
  *
  * @see
  *   Aliases: table, record, template, message, observation
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class ClassDefinition extends Definition, ClassExpression {

  /** URI of the class that provides a semantic interpretation of the element in a linked data
    * context. The URI may come from any namespace and may be shared between schemas
    *
    * @see
    *   https://w3id.org/linkml/definition_uri
    * @see
    *   https://linkml.io/linkml/schemas/uris-and-mappings.html
    * @see
    *   Aliases: public ID
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Assigning class_uris can provide additional hooks for interoperation, indicating a common
    *   conceptual model
    */
  def classUri: Option[UriOrCurie]

  /** The name used for a slot in the context of its owning class. If present, this is used instead
    * of the actual slot name.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   An example of alias is used within this metamodel, slot_definitions is aliases as slots
    * @note
    *   Not to be confused with aliases, which indicates a set of terms to be used for search
    *   purposes.
    */
  def alias: Option[String]

  /** A primary parent class from which inheritable metaslots are propagated
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def isA: Option[Reference[ClassDefinition]]

  /** A collection of secondary parent mixin classes from which inheritable metaslots are propagated
    *
    * @see
    *   https://en.wikipedia.org/wiki/Mixin
    * @see
    *   Aliases: traits
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Mixins act in the same way as parents (is_a). They allow a model to have a primary strict
    *   hierarchy, while keeping the benefits of multiple inheritance
    */
  def mixins: Seq[Reference[ClassDefinition]]

  /** Collection of slot names that are applicable to a class
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The list of applicable slots is inherited from parent classes
    * @note
    *   This defines the set of slots that are allowed to be used for a given class. The final list
    *   of slots for a class is the combination of the parent (is a) slots, mixins slots, apply to
    *   slots minus the slot usage entries.
    */
  def slots: Seq[Reference[SlotDefinition]]

  /** The refinement of a slot in the context of the containing class definition.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Many slots may be reused across different classes, but the meaning of the slot may be
    *   refined by context. For example, a generic association model may use slots
    *   subject/predicate/object with generic semantics and minimal constraints. When this is
    *   subclasses, e.g. to disease-phenotype associations then slot usage may specify both local
    *   naming (e.g. subject=disease) and local constraints
    */
  def slotUsage: Map[String, SlotDefinitionImpl]

  /** Inline definition of slots
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Attributes are an alternative way of defining new slots. An attribute adds a slot to the
    *   global space in the form <class_name>__<slot_name> (lower case, double underscores).
    *   Attributes can be specialized via slot_usage.
    */
  def attributes: Map[String, SlotDefinitionImpl]

  /** Indicates that this is the Container class which forms the root of the serialized document
    * structure in tree serializations
    *
    * @see
    *   https://linkml.io/linkml/intro/tutorial02.html
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Each schema should have at most one tree root
    */
  def treeRoot: Boolean

  /** Used to extend class or slot definitions. For example, if we have a core schema where a gene
    * has two slots for identifier and symbol, and we have a specialized schema for my_organism
    * where we wish to add a slot systematic_name, we can avoid subclassing by defining a class
    * gene_my_organism, adding the slot to this class, and then adding an apply_to pointing to the
    * gene class. The new slot will be 'injected into' the gene class.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def applyTo: Seq[Reference[ClassDefinition]]

  /** If true then all direct is_a children are mutually disjoint and share no instances in common
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def childrenAreMutuallyDisjoint: Boolean

  /** The collection of classification rules that apply to all members of this class. Classification
    * rules allow for automatically assigning the instantiated type of an instance.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def classificationRules: Seq[AnonymousClassExpressionImpl]

  /** The combination of is a plus defining slots form a genus-differentia definition, or the set of
    * necessary and sufficient conditions that can be transformed into an OWL equivalence axiom
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def definingSlots: Seq[Reference[SlotDefinition]]

  /** Two classes are disjoint if they have no instances in common, two slots are disjoint if they
    * can never hold between the same two instances
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def disjointWith: Seq[Reference[ClassDefinition]]

  /** How a class instance handles extra data not specified in the class definition. Note that this
    * does *not* define the constraints that are placed on additional slots defined by inheriting
    * classes.
    *
    * Possible values:
    *   - `allowed: true` - allow all additional data
    *   - `allowed: false` (or `allowed:` or `allowed: null` while `range_expression` is `null`) -
    *     forbid all additional data (default)
    *   - `range_expression: ...` - allow additional data if it matches the slot expression (see
    *     examples)
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def extraSlots: Option[ExtraSlotsExpressionImpl]

  /** True if this class represents a relationship rather than an entity
    *
    * @see
    *   http://www.w3.org/1999/02/22-rdf-syntax-ns#Statement
    * @see
    *   https://patterns.dataincubator.org/book/qualified-relation.html
    * @see
    *   Aliases: is_reified
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   In the context of Entity-Relationship (ER) modeling, this is used to state that a class
    *   models a relationship between entities, and should be drawn with a diamond
    * @note
    *   In the context of RDF, this should be used when instances of the class are `rdf:Statement`s
    * @note
    *   In the context of property graphs, this should be used when a class is used to represent an
    *   edge that connects nodes
    */
  def representsRelationship: Boolean

  /** The collection of rules that apply to all members of this class
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def rules: Seq[ClassRuleImpl]

  /** If true then induced/mangled slot names are not created for class_usage and attributes
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def slotNamesUnique: Boolean

  /** DEPRECATED -- rdfs:subClassOf to be emitted in OWL generation
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def subclassOf: Option[UriOrCurie]

  /** Indicates that the domain element consists exactly of the members of the element in the range.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   This only applies in the OWL generation
    */
  def unionOf: Seq[Reference[ClassDefinition]]

  /** A collection of named unique keys for this class. Such unique keys may be spread over several
    * slots, which is why there are also called "compound keys". A unique key uniquely identifies
    * instances of the class within a given container, meaning there cannot be two (or more)
    * instances of the class with the same values for all the slots that make up the unique key.
    *
    * @see
    *   https://linkml.io/linkml/schemas/constraints.html#unique-keys
    * @see
    *   https://w3id.org/linkml/key
    * @see
    *   https://w3id.org/linkml/identifier
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Not to be confused with a "singular unique key", which is defined by means of the `key`
    *   slot, or with an "identifier", which is defined by means of the "identifier" slot. Compound
    *   keys, singular unique keys, and identifiers all create a unicity constraint, but singular
    *   unique keys and identifiers have additional effects that compound keys do not have.\n
    */
  def uniqueKeys: Map[String, UniqueKeyImpl]
}
