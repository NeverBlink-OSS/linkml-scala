package eu.neverblink.linkml.metamodel

// GENERATED FROM LINKML

import eu.neverblink.linkml.runtime.*

/** Base implementation of the [[SlotDefinition]] LinkML class
  *
  * @inheritdoc
  */
case class SlotDefinitionImpl(
    @id
    name: String,
    @named("slot_uri")
    slotUri: Option[UriOrCurie] = None,
    title: Option[String] = None,
    description: Option[String] = None,
    identifier: Boolean = false,
    alias: Option[String] = None,
    multivalued: Boolean = false,
    required: Boolean = false,
    recommended: Boolean = false,
    @named("is_a")
    isA: Option[Reference[SlotDefinition]] = None,
    mixins: Seq[Reference[SlotDefinition]] = Seq(),
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
    @named("abstract")
    `abstract`: Boolean = false,
    aliases: Seq[String] = Seq(),
    @named("all_members")
    allMembers: Option[AnonymousSlotExpressionImpl] = None,
    @named("alt_descriptions")
    @simpleDict
    altDescriptions: Map[String, AltDescriptionImpl] = Map(),
    @simpleDict
    annotations: Map[String, AnnotationImpl] = Map(),
    @named("apply_to")
    applyTo: Seq[Reference[SlotDefinition]] = Seq(),
    array: Option[ArrayExpressionImpl] = None,
    asymmetric: Boolean = false,
    bindings: Seq[EnumBindingImpl] = Seq(),
    @named("broad_mappings")
    broadMappings: Seq[UriOrCurie] = Seq(),
    categories: Seq[UriOrCurie] = Seq(),
    @named("children_are_mutually_disjoint")
    childrenAreMutuallyDisjoint: Boolean = false,
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
    @named("designates_type")
    designatesType: Boolean = false,
    @named("disjoint_with")
    disjointWith: Seq[Reference[SlotDefinition]] = Seq(),
    domain: Option[Reference[ClassDefinition]] = None,
    @named("domain_of")
    domainOf: Seq[Reference[ClassDefinition]] = Seq(),
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
    @named("id_prefixes")
    idPrefixes: Seq[NcName] = Seq(),
    @named("id_prefixes_are_closed")
    idPrefixesAreClosed: Boolean = false,
    ifabsent: Option[String] = None,
    implements: Seq[UriOrCurie] = Seq(),
    @named("implicit_prefix")
    implicitPrefix: Option[String] = None,
    @named("imported_from")
    importedFrom: Option[String] = None,
    @named("in_language")
    inLanguage: Option[String] = None,
    @named("in_subset")
    inSubset: Seq[Reference[SubsetDefinition]] = Seq(),
    inherited: Boolean = false,
    instantiates: Seq[UriOrCurie] = Seq(),
    inverse: Option[Reference[SlotDefinition]] = None,
    irreflexive: Boolean = false,
    @named("is_class_field")
    isClassField: Boolean = false,
    @named("is_grouping_slot")
    isGroupingSlot: Boolean = false,
    @named("is_usage_slot")
    isUsageSlot: Boolean = false,
    key: Boolean = false,
    keywords: Seq[String] = Seq(),
    @named("last_updated_on")
    lastUpdatedOn: Option[LinkmlDateTime] = None,
    @named("list_elements_ordered")
    listElementsOrdered: Boolean = false,
    @named("list_elements_unique")
    listElementsUnique: Boolean = false,
    @named("local_names")
    @simpleDict
    localNames: Map[String, LocalNameImpl] = Map(),
    @named("locally_reflexive")
    locallyReflexive: Boolean = false,
    mappings: Seq[UriOrCurie] = Seq(),
    @named("maximum_cardinality")
    maximumCardinality: Option[Int] = None,
    @named("maximum_value")
    maximumValue: Option[Anything] = None,
    @named("minimum_cardinality")
    minimumCardinality: Option[Int] = None,
    @named("minimum_value")
    minimumValue: Option[Anything] = None,
    mixin: Boolean = false,
    @named("modified_by")
    modifiedBy: Option[UriOrCurie] = None,
    @named("narrow_mappings")
    narrowMappings: Seq[UriOrCurie] = Seq(),
    notes: Seq[String] = Seq(),
    owner: Option[Reference[Definition]] = None,
    @named("path_rule")
    pathRule: Option[PathExpressionImpl] = None,
    range: Option[Reference[Element]] = None,
    @named("range_expression")
    rangeExpression: Option[AnonymousClassExpressionImpl] = None,
    readonly: Option[String] = None,
    reflexive: Boolean = false,
    @named("reflexive_transitive_form_of")
    reflexiveTransitiveFormOf: Option[Reference[SlotDefinition]] = None,
    @named("related_mappings")
    relatedMappings: Seq[UriOrCurie] = Seq(),
    @named("relational_role")
    relationalRole: Option[Reference[RelationalRoleEnum]] = None,
    role: Option[String] = None,
    @named("see_also")
    seeAlso: Seq[UriOrCurie] = Seq(),
    shared: Boolean = false,
    @named("singular_name")
    singularName: Option[String] = None,
    @named("slot_group")
    slotGroup: Option[Reference[SlotDefinition]] = None,
    source: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    @named("string_serialization")
    stringSerialization: Option[String] = None,
    @named("structured_aliases")
    structuredAliases: Seq[StructuredAliasImpl] = Seq(),
    @named("structured_pattern")
    structuredPattern: Option[PatternExpressionImpl] = None,
    @named("subproperty_of")
    subpropertyOf: Option[Reference[SlotDefinition]] = None,
    symmetric: Boolean = false,
    todos: Seq[String] = Seq(),
    transitive: Boolean = false,
    @named("transitive_form_of")
    transitiveFormOf: Option[Reference[SlotDefinition]] = None,
    @named("type_mappings")
    @compactDict
    typeMappings: Map[String, TypeMappingImpl] = Map(),
    @named("union_of")
    unionOf: Seq[Reference[SlotDefinition]] = Seq(),
    unit: Option[UnitOfMeasureImpl] = None,
    @named("usage_slot_name")
    usageSlotName: Option[String] = None,
    @named("value_presence")
    valuePresence: Option[Reference[PresenceEnum]] = None,
    @named("values_from")
    valuesFrom: Seq[UriOrCurie] = Seq(),
) extends SlotDefinition {

  /** Unfolded slot combining procedure `for metaslot in metaslots` from the spec. This variant
    * merges ALL SlotDefinition slots.
    * @see
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-combine-slots
    * @param combineRange
    *   Injected range combination function to resolve a circular dependency between metamodel and
    *   schema view
    */
  def combineWith(
      other: SlotDefinitionImpl,
      combineRange: (Reference[Element], Reference[Element]) => Reference[Element],
  ): SlotDefinitionImpl =
    copy(
      name = combineFallback(this.name, other.name),
      slotUri = combineOption(this.slotUri, other.slotUri, combineFallback),
      title = combineOption(this.title, other.title, combineFallback),
      description = combineOption(this.description, other.description, combineFallback),
      identifier = combineBoolean(this.identifier, other.identifier),
      alias = combineOption(this.alias, other.alias, combineFallback),
      multivalued = combineBoolean(this.multivalued, other.multivalued),
      required = combineBoolean(this.required, other.required),
      recommended = combineBoolean(this.recommended, other.recommended),
      isA = combineOption(this.isA, other.isA, combineFallback),
      mixins = combineSeq(this.mixins, other.mixins),
      inlined = combineBoolean(this.inlined, other.inlined),
      inlinedAsList = combineBoolean(this.inlinedAsList, other.inlinedAsList),
      pattern = combineOption(this.pattern, other.pattern, combinePattern),
      rank = combineOption(this.rank, other.rank, combineFallback),
      anyOf = combineSeq(this.anyOf, other.anyOf),
      exactlyOneOf = combineSeq(this.exactlyOneOf, other.exactlyOneOf),
      noneOf = combineSeq(this.noneOf, other.noneOf),
      allOf = combineSeq(this.allOf, other.allOf),
      `abstract` = combineBoolean(this.`abstract`, other.`abstract`),
      aliases = combineSeq(this.aliases, other.aliases),
      allMembers = combineOption(this.allMembers, other.allMembers, combineFallback),
      altDescriptions = combineMap(this.altDescriptions, other.altDescriptions),
      annotations = combineMap(this.annotations, other.annotations),
      applyTo = combineSeq(this.applyTo, other.applyTo),
      array = combineOption(this.array, other.array, combineFallback),
      asymmetric = combineBoolean(this.asymmetric, other.asymmetric),
      bindings = combineSeq(this.bindings, other.bindings),
      broadMappings = combineSeq(this.broadMappings, other.broadMappings),
      categories = combineSeq(this.categories, other.categories),
      childrenAreMutuallyDisjoint =
        combineBoolean(this.childrenAreMutuallyDisjoint, other.childrenAreMutuallyDisjoint),
      closeMappings = combineSeq(this.closeMappings, other.closeMappings),
      comments = combineSeq(this.comments, other.comments),
      conformsTo = combineOption(this.conformsTo, other.conformsTo, combineFallback),
      contributors = combineSeq(this.contributors, other.contributors),
      createdBy = combineOption(this.createdBy, other.createdBy, combineFallback),
      createdOn = combineOption(this.createdOn, other.createdOn, combineFallback),
      definitionUri = combineOption(this.definitionUri, other.definitionUri, combineFallback),
      deprecated = combineOption(this.deprecated, other.deprecated, combineFallback),
      deprecatedElementHasExactReplacement = combineOption(
        this.deprecatedElementHasExactReplacement,
        other.deprecatedElementHasExactReplacement,
        combineFallback,
      ),
      deprecatedElementHasPossibleReplacement = combineOption(
        this.deprecatedElementHasPossibleReplacement,
        other.deprecatedElementHasPossibleReplacement,
        combineFallback,
      ),
      designatesType = combineBoolean(this.designatesType, other.designatesType),
      disjointWith = combineSeq(this.disjointWith, other.disjointWith),
      domain = combineOption(this.domain, other.domain, combineFallback),
      domainOf = combineSeq(this.domainOf, other.domainOf),
      enumRange = combineOption(this.enumRange, other.enumRange, combineFallback),
      equalsExpression =
        combineOption(this.equalsExpression, other.equalsExpression, combineFallback),
      equalsNumber = combineOption(this.equalsNumber, other.equalsNumber, combineFallback),
      equalsString = combineOption(this.equalsString, other.equalsString, combineFallback),
      equalsStringIn = combineSeq(this.equalsStringIn, other.equalsStringIn),
      exactCardinality =
        combineOption(this.exactCardinality, other.exactCardinality, combineFallback),
      exactMappings = combineSeq(this.exactMappings, other.exactMappings),
      examples = combineSeq(this.examples, other.examples),
      extensions = combineMap(this.extensions, other.extensions),
      fromSchema = combineOption(this.fromSchema, other.fromSchema, combineFallback),
      hasMember = combineOption(this.hasMember, other.hasMember, combineFallback),
      idPrefixes = combineSeq(this.idPrefixes, other.idPrefixes),
      idPrefixesAreClosed = combineBoolean(this.idPrefixesAreClosed, other.idPrefixesAreClosed),
      ifabsent = combineOption(this.ifabsent, other.ifabsent, combineFallback),
      implements = combineSeq(this.implements, other.implements),
      implicitPrefix = combineOption(this.implicitPrefix, other.implicitPrefix, combineFallback),
      importedFrom = combineOption(this.importedFrom, other.importedFrom, combineFallback),
      inLanguage = combineOption(this.inLanguage, other.inLanguage, combineFallback),
      inSubset = combineSeq(this.inSubset, other.inSubset),
      inherited = combineBoolean(this.inherited, other.inherited),
      instantiates = combineSeq(this.instantiates, other.instantiates),
      inverse = combineOption(this.inverse, other.inverse, combineFallback),
      irreflexive = combineBoolean(this.irreflexive, other.irreflexive),
      isClassField = combineBoolean(this.isClassField, other.isClassField),
      isGroupingSlot = combineBoolean(this.isGroupingSlot, other.isGroupingSlot),
      isUsageSlot = combineBoolean(this.isUsageSlot, other.isUsageSlot),
      key = combineBoolean(this.key, other.key),
      keywords = combineSeq(this.keywords, other.keywords),
      lastUpdatedOn = combineOption(this.lastUpdatedOn, other.lastUpdatedOn, combineFallback),
      listElementsOrdered = combineBoolean(this.listElementsOrdered, other.listElementsOrdered),
      listElementsUnique = combineBoolean(this.listElementsUnique, other.listElementsUnique),
      localNames = combineMap(this.localNames, other.localNames),
      locallyReflexive = combineBoolean(this.locallyReflexive, other.locallyReflexive),
      mappings = combineSeq(this.mappings, other.mappings),
      maximumCardinality =
        combineOption(this.maximumCardinality, other.maximumCardinality, combineFallback),
      maximumValue = combineOption(this.maximumValue, other.maximumValue, combineMax),
      minimumCardinality =
        combineOption(this.minimumCardinality, other.minimumCardinality, combineFallback),
      minimumValue = combineOption(this.minimumValue, other.minimumValue, combineMin),
      mixin = combineBoolean(this.mixin, other.mixin),
      modifiedBy = combineOption(this.modifiedBy, other.modifiedBy, combineFallback),
      narrowMappings = combineSeq(this.narrowMappings, other.narrowMappings),
      notes = combineSeq(this.notes, other.notes),
      owner = combineOption(this.owner, other.owner, combineFallback),
      pathRule = combineOption(this.pathRule, other.pathRule, combineFallback),
      range = combineOption(this.range, other.range, combineRange),
      rangeExpression = combineOption(this.rangeExpression, other.rangeExpression, combineFallback),
      readonly = combineOption(this.readonly, other.readonly, combineFallback),
      reflexive = combineBoolean(this.reflexive, other.reflexive),
      reflexiveTransitiveFormOf = combineOption(
        this.reflexiveTransitiveFormOf,
        other.reflexiveTransitiveFormOf,
        combineFallback,
      ),
      relatedMappings = combineSeq(this.relatedMappings, other.relatedMappings),
      relationalRole = combineOption(this.relationalRole, other.relationalRole, combineFallback),
      role = combineOption(this.role, other.role, combineFallback),
      seeAlso = combineSeq(this.seeAlso, other.seeAlso),
      shared = combineBoolean(this.shared, other.shared),
      singularName = combineOption(this.singularName, other.singularName, combineFallback),
      slotGroup = combineOption(this.slotGroup, other.slotGroup, combineFallback),
      source = combineOption(this.source, other.source, combineFallback),
      status = combineOption(this.status, other.status, combineFallback),
      stringSerialization =
        combineOption(this.stringSerialization, other.stringSerialization, combineFallback),
      structuredAliases = combineSeq(this.structuredAliases, other.structuredAliases),
      structuredPattern =
        combineOption(this.structuredPattern, other.structuredPattern, combineFallback),
      subpropertyOf = combineOption(this.subpropertyOf, other.subpropertyOf, combineFallback),
      symmetric = combineBoolean(this.symmetric, other.symmetric),
      todos = combineSeq(this.todos, other.todos),
      transitive = combineBoolean(this.transitive, other.transitive),
      transitiveFormOf =
        combineOption(this.transitiveFormOf, other.transitiveFormOf, combineFallback),
      typeMappings = combineMap(this.typeMappings, other.typeMappings),
      unionOf = combineSeq(this.unionOf, other.unionOf),
      unit = combineOption(this.unit, other.unit, combineFallback),
      usageSlotName = combineOption(this.usageSlotName, other.usageSlotName, combineFallback),
      valuePresence = combineOption(this.valuePresence, other.valuePresence, combineFallback),
      valuesFrom = combineSeq(this.valuesFrom, other.valuesFrom),
    )

  /** Unfolded slot combining procedure `for metaslot in metaslots` from the spec. This variant
    * merges INHERITED SlotDefinition slots only.
    * @see
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-combine-slots
    * @param combineRange
    *   Injected range combination function to resolve a circular dependency between metamodel and
    *   schema view
    */
  def combineInherited(
      other: SlotDefinitionImpl,
      combineRange: (Reference[Element], Reference[Element]) => Reference[Element],
  ): SlotDefinitionImpl =
    copy(
      identifier = combineBoolean(this.identifier, other.identifier),
      multivalued = combineBoolean(this.multivalued, other.multivalued),
      required = combineBoolean(this.required, other.required),
      recommended = combineBoolean(this.recommended, other.recommended),
      inlined = combineBoolean(this.inlined, other.inlined),
      inlinedAsList = combineBoolean(this.inlinedAsList, other.inlinedAsList),
      pattern = combineOption(this.pattern, other.pattern, combinePattern),
      rank = combineOption(this.rank, other.rank, combineFallback),
      array = combineOption(this.array, other.array, combineFallback),
      designatesType = combineBoolean(this.designatesType, other.designatesType),
      domain = combineOption(this.domain, other.domain, combineFallback),
      equalsExpression =
        combineOption(this.equalsExpression, other.equalsExpression, combineFallback),
      equalsNumber = combineOption(this.equalsNumber, other.equalsNumber, combineFallback),
      equalsString = combineOption(this.equalsString, other.equalsString, combineFallback),
      equalsStringIn = combineSeq(this.equalsStringIn, other.equalsStringIn),
      exactCardinality =
        combineOption(this.exactCardinality, other.exactCardinality, combineFallback),
      ifabsent = combineOption(this.ifabsent, other.ifabsent, combineFallback),
      inherited = combineBoolean(this.inherited, other.inherited),
      key = combineBoolean(this.key, other.key),
      listElementsOrdered = combineBoolean(this.listElementsOrdered, other.listElementsOrdered),
      listElementsUnique = combineBoolean(this.listElementsUnique, other.listElementsUnique),
      maximumCardinality =
        combineOption(this.maximumCardinality, other.maximumCardinality, combineFallback),
      maximumValue = combineOption(this.maximumValue, other.maximumValue, combineMax),
      minimumCardinality =
        combineOption(this.minimumCardinality, other.minimumCardinality, combineFallback),
      minimumValue = combineOption(this.minimumValue, other.minimumValue, combineMin),
      range = combineOption(this.range, other.range, combineRange),
      readonly = combineOption(this.readonly, other.readonly, combineFallback),
      relationalRole = combineOption(this.relationalRole, other.relationalRole, combineFallback),
      role = combineOption(this.role, other.role, combineFallback),
      shared = combineBoolean(this.shared, other.shared),
      structuredPattern =
        combineOption(this.structuredPattern, other.structuredPattern, combineFallback),
      valuePresence = combineOption(this.valuePresence, other.valuePresence, combineFallback),
    )
}

/** An element that describes how instances are related to other instances
  *
  * @see
  *   Aliases: slot, field, property, attribute, column, variable
  * @see
  *   From schema: https://w3id.org/linkml/meta
  */
abstract class SlotDefinition extends Definition, SlotExpression {

  /** URI of the class that provides a semantic interpretation of the slot in a linked data context.
    * The URI may come from any namespace and may be shared between schemas.
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
    *   Assigning slot_uris can provide additional hooks for interoperation, indicating a common
    *   conceptual model
    * @note
    *   To use a URI or CURIE as a range, create a class with the URI or CURIE as the class_uri
    */
  def slotUri: Option[UriOrCurie]

  /** True means that the slot is the identifier slot of its class. Such a slot uniquely identifies
    * instances of the class throughout an entire document, meaning there cannot be two (or more)
    * instances of the class (or instances of any of its descendants) with the same value for the
    * identifier slot anywhere in the document.
    *
    * @see
    *   https://en.wikipedia.org/wiki/Identifier
    * @see
    *   https://linkml.io/linkml/schemas/constraints.html#unique-keys
    * @see
    *   https://linkml.io/linkml/schemas/inlining.html
    * @see
    *   https://w3id.org/linkml/unique_keys
    * @see
    *   https://w3id.org/linkml/key
    * @see
    *   Aliases: primary key, ID, UID, code
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The identifier slot is inherited.
    * @note
    *   A domain can have at most one identifier slot OR a key slot. However a domain can have both
    *   an identifier slot and any number of compound keys.
    * @note
    *   An identifier slot is automatically required. Identifiers cannot be optional.
    * @note
    *   The presence of an identifier slot makes a class eligible for inlining as a dictionary.
    * @note
    *   The presence of an identifier slot makes a class eligible for being referenced rather than
    *   inlined.
    */
  def identifier: Boolean

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

  /** A primary parent slot from which inheritable metaslots are propagated
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def isA: Option[Reference[SlotDefinition]]

  /** A collection of secondary parent mixin slots from which inheritable metaslots are propagated
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
  def mixins: Seq[Reference[SlotDefinition]]

  /** Used to extend class or slot definitions. For example, if we have a core schema where a gene
    * has two slots for identifier and symbol, and we have a specialized schema for my_organism
    * where we wish to add a slot systematic_name, we can avoid subclassing by defining a class
    * gene_my_organism, adding the slot to this class, and then adding an apply_to pointing to the
    * gene class. The new slot will be 'injected into' the gene class.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def applyTo: Seq[Reference[SlotDefinition]]

  /** If s is antisymmetric, and i.s=v where i is different from v, v.s cannot have value i
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Asymmetry is the combination of antisymmetry and irreflexivity
    */
  def asymmetric: Boolean

  /** If true then all direct is_a children are mutually disjoint and share no instances in common
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def childrenAreMutuallyDisjoint: Boolean

  /** True means that the key slot(s) is used to determine the instantiation (types) relation
    * between objects and a ClassDefinition
    *
    * @see
    *   https://linkml.io/linkml/schemas/type-designators.html
    * @see
    *   Aliases: type designator
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def designatesType: Boolean

  /** Two classes are disjoint if they have no instances in common, two slots are disjoint if they
    * can never hold between the same two instances
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def disjointWith: Seq[Reference[SlotDefinition]]

  /** Defines the type of the subject of the slot. Given the following slot definition S1: domain:
    * C1 range: C2 the declaration X: S1: Y
    *
    * implicitly asserts that X is an instance of C1
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def domain: Option[Reference[ClassDefinition]]

  /** The class(es) that reference the slot in a "slots" or "slot_usage" context
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def domainOf: Seq[Reference[ClassDefinition]]

  /** Function that provides a default value for the slot.\n * [Tt]rue -- boolean True\n * [Ff]alse
    * -- boolean False\n * bnode -- blank node identifier\n * class_curie -- CURIE for the
    * containing class\n * class_uri -- URI for the containing class\n * default_ns -- schema
    * default namespace\n * default_range -- schema default range\n * int(value) -- integer value\n
    * * slot_uri -- URI for the slot\n * slot_curie -- CURIE for the slot\n * string(value) --
    * string value\n * EnumName(PermissibleValue) -- enum value
    *
    * @see
    *   https://w3id.org/linkml/equals_expression
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def ifabsent: Option[String]

  /** True means that the *value* of a slot is inherited by subclasses
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The slot is to be used for defining *metamodels* only
    * @note
    *   Inherited applies to slot values. Parent *slots* are always inherited by subclasses
    */
  def inherited: Boolean

  /** Indicates that any instance of d s r implies that there is also an instance of r s' d
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def inverse: Option[Reference[SlotDefinition]]

  /** If s is irreflexive, then there exists no i such i.s=i
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def irreflexive: Boolean

  /** Indicates that for any instance, i, the domain of this slot will include an assertion of i s
    * range
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def isClassField: Boolean

  /** True if this slot is a grouping slot
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def isGroupingSlot: Boolean

  /** True means that this slot was defined in a slot_usage situation
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def isUsageSlot: Boolean

  /** True means that the slot is the "singular unique key" (also known more simply as the "key
    * slot") of its class. Such a slot uniquely identifies instances of the class within a single
    * container, meaning there cannot be two (or more) instances of the class (or instances of any
    * of its descendants) with the same value for the key slot within the container.
    *
    * @see
    *   https://linkml.io/linkml/schemas/constraints.html#singular-unique-keys
    * @see
    *   https://linkml.io/linkml/schemas/inlining.html
    * @see
    *   https://w3id.org/linkml/unique_keys
    * @see
    *   https://w3id.org/linkml/identifier
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The key slot is inherited.
    * @note
    *   A domain can have at most one key slot OR one identifier slot. However a domain can have
    *   both a key slot and any number of compound keys.
    * @note
    *   A key slot is automatically required. Singular unique keys cannot be optional.
    * @note
    *   The presence of a key slot makes a class eligible for inlining as a dictionary.
    */
  def key: Boolean

  /** If True, then the order of elements of a multivalued slot is guaranteed to be preserved. If
    * False, the order may still be preserved but this is not guaranteed
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Should only be used with multivalued slots
    */
  def listElementsOrdered: Boolean

  /** If True, then there must be no duplicates in the elements of a multivalued slot
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Should only be used with multivalued slots
    */
  def listElementsUnique: Boolean

  /** If s is locally_reflexive, then i.s=i for all instances i where s is a class slot for the type
    * of i
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def locallyReflexive: Boolean

  /** The "owner" of the slot. It is the class if it appears in the slots list, otherwise the
    * declaring slot
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def owner: Option[Reference[Definition]]

  /** A rule for inferring a slot assignment based on evaluating a path through a sequence of slot
    * assignments
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def pathRule: Option[PathExpressionImpl]

  /** If present, slot is read only. Text explains why
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The slot is to be used for defining *metamodels* only
    */
  def readonly: Option[String]

  /** If s is reflexive, then i.s=i for all instances i
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   It is rare for a property to be reflexive, this characteristic is added for completeness,
    *   consider instead locally_reflexive
    */
  def reflexive: Boolean

  /** Transitive_form_of including the reflexive case
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def reflexiveTransitiveFormOf: Option[Reference[SlotDefinition]]

  /** The role a slot on a relationship class plays, for example, the subject, object or predicate
    * roles
    *
    * @see
    *   Aliases: reification_role
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   This should only be used on slots that are applicable to class that represent relationships
    * @note
    *   In the context of RDF, this should be used for slots that can be modeled using the RDF
    *   reification vocabulary
    * @note
    *   In the context of property graphs, this should be used on edge classes to indicate which
    *   slots represent the input and output nodes
    */
  def relationalRole: Option[Reference[RelationalRoleEnum]]

  /** A textual descriptor that indicates the role played by the slot range
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   The primary use case for this slot is to provide a textual descriptor of a generic slot name
    *   when used in the context of a more specific class
    */
  def role: Option[String]

  /** If True, then the relationship between the slot domain and range is many to one or many to
    * many
    *
    * @see
    *   Aliases: inverse functional, many to one or many
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def shared: Boolean

  /** A name that is used in the singular form
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   This may be used in some schema translations where use of a singular form is idiomatic, for
    *   example RDF
    */
  def singularName: Option[String]

  /** Allows for grouping of related slots into a grouping slot that serves the role of a group
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Slot groups do not change the semantics of a model but are a useful way of visually grouping
    *   related slots
    */
  def slotGroup: Option[Reference[SlotDefinition]]

  /** Ontology property which this slot is a subproperty of. Note: setting this property on a slot
    * does not guarantee an expansion of the ontological hierarchy into an enumerated list of
    * possible values in every serialization of the model.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @example
    *   `RO:HOM0000001`: this is the RO term for "in homology relationship with", and used as a
    *   value of subproperty of this means that any ontological child (related to RO:HOM0000001 via
    *   an is_a relationship), is a valid value for the slot that declares this with the
    *   subproperty_of tag. This differs from the 'values_from' meta model component in that
    *   'values_from' requires the id of a value set (said another way, if an entire ontology had a
    *   curie/identifier that was the identifier for the entire ontology, then that identifier would
    *   be used in 'values_from.')
    */
  def subpropertyOf: Option[Reference[SlotDefinition]]

  /** If s is symmetric, and i.s=v, then v.s=i
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def symmetric: Boolean

  /** If s is transitive, and i.s=z, and s.s=j, then i.s=j
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def transitive: Boolean

  /** If s transitive_form_of d, then (1) s holds whenever d holds (2) s is transitive (3) d holds
    * whenever s holds and there are no intermediates, and s is not reflexive
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   Example: ancestor_of is the transitive_form_of parent_of
    */
  def transitiveFormOf: Option[Reference[SlotDefinition]]

  /** A collection of type mappings that specify how a slot's range should be mapped or serialized
    * in different frameworks
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def typeMappings: Map[String, TypeMappingImpl]

  /** Indicates that the domain element consists exactly of the members of the element in the range.
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    * @note
    *   This only applies in the OWL generation
    */
  def unionOf: Seq[Reference[SlotDefinition]]

  /** The name of the slot referenced in the slot_usage
    *
    * @see
    *   From schema: https://w3id.org/linkml/meta
    */
  def usageSlotName: Option[String]
}
