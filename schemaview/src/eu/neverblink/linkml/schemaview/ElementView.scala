package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.SchemaView.defaultRangeResolved

import scala.collection.mutable

/** Element views provide a rich interface for working with schema elements. They require an
  * implicit [[SchemaView]] and are always linked to a defining schema, which is the schema in which
  * the element was originally defined. This allows you to both have the full context of all
  * imported schemas and the ability to get back to the original definition of an element, which is
  * important for things like checking the default ranges or prefixes of the defining schema.
  *
  * @param sv
  *   Root SchemaView that (transitively) imported this Element
  */
sealed trait ElementView[E <: Element](using val sv: SchemaView) {

  /** Schema definition that defined this Element. This schema should be used for prefixes and
    * default ranges.
    */
  def definingSchema: SchemaDefinition

  /** The underlying Element, as defined in [[definingSchema]]
    */
  def inner: E

  /** The defining schema's prefix resolver */
  given definingPrefixResolver: PrefixResolver = SchemaView.createPrefixResolver(definingSchema)
  
  /** Get the URI of this element, using the default prefix of the implicit [[SchemaView]] if not
    * explicitly defined.
    */
  def uriOrCurie: UriOrCurie

  /** Get the URI of this element in string form, using the default prefix of the implicit
    * [[SchemaView]] if not explicitly defined.
    */
  final def uriStr: String = uriOrCurie.uri

  /** Get the default URI prefix (prefix map value) for the schema, with a fallback to the schema ID
    * (this fallback mirrors the python implementation).
    */
  final def defaultPrefixUri: String =
    val schema = definingSchema
    schema.defaultPrefix // NCName / CURIE prefix
      .flatMap(schema.prefixes.get)
      .map(_.prefixReference.uri) // URI prefix value
      .getOrElse {
        // fallback
        val uri = schema.id.uri
        if (uri.endsWith("#") || uri.endsWith("/")) uri else uri + "/"
      }
}

final case class ClassView(cls: ClassDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[ClassDefinition] {
  def inner: ClassDefinition = cls

  def uriOrCurie: UriOrCurie =
    cls.classUri.getOrElse(Uri(defaultPrefixUri + Case.PascalCase(cls.name)))

  /** Derived attributes for this class.
    */
  lazy val derivedAttributes: Map[String, SlotView] =
    applicableSlots.map(v => v.ref.value -> derivedSlot(v.ref, v.source)).toMap

  /** Get and dereference the direct parents (mixins + inheritance) of this class
    *
    * @return
    *   Direct parents of the class, mixins before inheritance
    */
  def parents: Iterable[ClassView] = getParents(this)

  /** Get the subject type for this class, if possible. Uses the class' identifier slot's range.
    * @return
    *   The subject type, or None if the class does not have an identifier
    */
  def subjectType: Option[SubjectType] = identifier.flatMap(slotView => {
    slotView.derivedRangeView.resolve.collect {
      // TODO LNK-106
      case tv: TypeView =>
        tv.subjectType
    }
  })

  private def getParents(view: ClassView): Iterable[ClassView] =
    (view.cls.mixins ++ view.cls.isA).flatMap { r =>
      sv.resolve(r.asInstanceOf[Reference[ClassView]])
    } // TODO LNK-23: type refinements

  /** Get and dereference all the ancestors (transitive parents) of this class.
    *
    * @param reflexive
    *   Whether to include the class itself in the result
    * @return
    *   Ancestors of the class in LinkML's "depth-first" order -
    *   `ancestors(x) = x.mixins, x.isA, ancestors(x.isA), ancestors(x.mixins)`
    */
  def ancestors(reflexive: Boolean): Iterable[ClassView] =
    Closure.get(this, getParents, reflexive)

  /** Get the slots that are directly defined in this class.
    */
  def directSlots: Seq[Reference[SlotDefinition]] = getDirectSlots(cls)

  private def getDirectSlots(cls: ClassDefinition): Seq[Reference[SlotDefinition]] =
    cls.slots ++ cls.attributes.keys.map(Reference[SlotDefinition])

  /** Get the identifier slot of a class, if it has one.
    */
  def identifier: Option[SlotView] = {
    derivedAttributes.values.find(_.slot.identifier)
  }

  /** Get all slot references that are applicable to this class definition.
    *
    * Returns a sequence of pairs of (slot reference, source of the slot definition), where the
    * source is either a ClassView (if the slot is defined as an attribute) or a SlotView (if the
    * slot is defined as a top-level slot). The source is used for default prefix and range
    * resolution according to the original schema file.
    *
    * @see
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#function-applicable-slots
    */
  def applicableSlots: Seq[(ref: Reference[SlotDefinition], source: ElementView[?])] =
    // LinkedHashMap to preserve the order of slot definitions.
    val buffer = mutable.LinkedHashMap[Reference[SlotDefinition], ElementView[?]]()
    ancestors(true).foreach { anc =>
      val candidates: Seq[(Reference[SlotDefinition], ElementView[?])] =
        anc.cls.slots.map(ref => (ref, ref.asInstanceOf[Reference[SlotView]].resolve.get)) ++
          anc.cls.attributes.keys.map(name => (Reference[SlotDefinition](name), anc))
      for ((ref, source) <- candidates) do
        // Older ancestors take precedence, so that we resolve to the original source of the slot.
        buffer.update(ref, source)
    }
    buffer.toSeq

  /** Test whether the class or its ancestors have this slot defined as an attribute.
    *
    * This is needed so that attribute names can shadow top-level slot definitions.
    *
    * @param slotRef
    *   Slot reference to test for
    * @return
    *   True if the slot comes from class attributes, false otherwise
    */
  def isSlotFromAttributes(slotRef: Reference[SlotDefinition]): Boolean =
    ancestors(true).exists(anc => anc.cls.attributes.keys.exists(_ == slotRef.value))

  /** Derive a slot for a class, taking into account the `slotUsage` and `attributes` for the class
    * and its ancestors, as well as the schema top-level slots and its ancestors.
    *
    * @param slotRef
    *   Reference to the slot. Does not have to be resolvable, the slot may be defined only in
    *   attributes.
    * @param source
    *   The source of the slot's original definition. Either a ClassView (if it was defined as an
    *   attribute) or a SlotView (if it was defined as a top-level slot). This is used for default
    *   prefix and range resolution according to the original schema file.
    * @note
    *   This function does not check that the class should actually have this slot. Use
    *   [[ClassDerivation.applicableSlots()]] to get all slots that the class should have.
    * @see
    *   `DerivedSlot`
    *   https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#algorithm-calculate-derived-slot
    * @return
    *   The derived slot
    */
  private def derivedSlot(
      slotRef: Reference[SlotDefinition],
      source: ElementView[?],
  ): SlotView = {
    var currentSlot = SlotDefinitionImpl(name = slotRef.value)
    currentSlot = sv.applySlotUsage(currentSlot, cls)
    sv.resolve(slotRef.asInstanceOf[Reference[SlotView]]) match {
      // Note this is a bit off-spec, but it's a pretty reasonable
      case Some(resolved: SlotView) if !isSlotFromAttributes(slotRef) =>
        currentSlot =
          currentSlot.combineWith(resolved.slot.asInstanceOf[SlotDefinitionImpl], sv.combineRange)
        for slotAncestor <- resolved.ancestors(false) do {
          currentSlot = currentSlot.combineInherited(
            slotAncestor.asInstanceOf[SlotDefinitionImpl],
            sv.combineRange,
          )
        }
      case _ =>
    }
    if currentSlot.inlinedAsList && !currentSlot.inlined then {
      currentSlot = currentSlot.copy(inlined = true)
    }
    if (currentSlot.identifier || currentSlot.key) && !currentSlot.required then {
      currentSlot = currentSlot.copy(required = true)
    }
    val finalSlot = currentSlot.copy(
      slotUri = Some(
        SlotView.uri(
          currentSlot,
          // For prefix resolution use the context of the original slot definition
          source,
        ),
      ),
    )
    // Apply the original schema as the defining schema, so that default prefix / default range
    // resolution still works as defined in the original schema file.
    SlotView(finalSlot, source.definingSchema)
  }

  /** Test whether this class definition has an identifier slot
    *
    * @return
    *   true if the class has an identifier
    */
  def hasIdentifier: Boolean = identifier.isDefined

  /** Check the tree_root_as extension for this class and return the corresponding InlineType. If
    * the extension is not present, return InlineType.optional as the default.
    *
    * @param overrideType
    *   An optional override for the tree_root_as extension value. If provided, this value will be
    *   used instead of checking the class extensions.
    */
  def treeRootInlineType(overrideType: Option[String]): InlineType =
    val value = overrideType.orElse {
      cls.extensions.get("tree_root_as").map(_.extensionValue.value.trim)
    }
    value.map(v =>
      v.toLowerCase match {
        case "plain" => InlineType.plain
        case "optional" => InlineType.optional
        case "list" => InlineType.list
        case _ =>
          throw new IllegalArgumentException(
            s"Unknown tree_root_as extension value: '$v''",
          )
      },
    ).getOrElse(InlineType.plain)

  /** Materialize this [[ClassView]] into a derived [[ClassDefinition]]. This inlines all slots as
    * attributes, and clears any inheritance slots. Additionally, sets the class uri using
    * [[SchemaView]] logic.
    */
  def materialize: ClassDefinitionImpl = {
    inner.asInstanceOf[ClassDefinitionImpl].copy(
      classUri = Some(uriOrCurie),
      isA = None,
      mixins = Seq.empty,
      attributes = derivedAttributes.map((slotKey, slot) =>
        slotKey -> slot.inner.asInstanceOf[SlotDefinitionImpl].copy(
          isA = None,
          mixins = Seq.empty,
        ),
      ),
      slots = Seq.empty,
      slotUsage = Map.empty,
    )
  }
}

final case class SlotView(slot: SlotDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[SlotDefinition] {
  def inner: SlotDefinition = slot

  /** Get and dereference the direct parents (mixins + inheritance) of this slot
    *
    * @return
    *   Direct parents of the slot, mixins before inheritance
    */
  def parents: Iterable[SlotDefinition] = getParents(slot)

  private def getParents(slot: SlotDefinition): Iterable[SlotDefinition] =
    (slot.mixins ++ slot.isA).flatMap(sv.resolve) // TODO LNK-23: type refinements

  /** Get and dereference all the ancestors (transitive parents) of this slot.
    *
    * @param reflexive
    *   Whether to include the slot itself in the result
    * @return
    *   Ancestors of the slot in LinkML's "depth-first" order -
    *   `ancestors(x) = x.mixins, x.isA, ancestors(x.isA), ancestors(x.mixins)`
    */
  def ancestors(reflexive: Boolean): Iterable[SlotDefinition] =
    Closure.get(slot, getParents, reflexive)

  /** Test whether this slot is declared as inlined, or is implicitly inlined as its range is a
    * class without an identifier
    *
    * @return
    *   true if the slot is inlined
    */
  def derivedInlined: Boolean =
    slot.inlined || sv.resolve(
      slot.range.getOrElse(definingSchema.defaultRangeResolved).asInstanceOf[Reference[
        ElementView[?],
      ]],
    )
      .collect({ case cls: ClassView => !cls.hasIdentifier })
      .getOrElse(false)

  /** Get the range of this slot, with missing values filled with `default_range` from the implicit
    * [[SchemaView]]. Does NOT take inheritance into account: Make sure you use this method after
    * class/slot derivation is performed.
    */
  def derivedRange: Reference[Element] =
    slot.range.getOrElse(definingSchema.defaultRangeResolved)

  /** Get the range of this slot as a reference to an [[ElementView]], with missing values filled
    * with `default_range` from the implicit [[SchemaView]]. Does NOT take inheritance into account:
    * Make sure you use this method after class/slot derivation is performed.
    */
  def derivedRangeView: Reference[ElementView[?]] =
    derivedRange.asInstanceOf[Reference[ElementView[?]]]

  /** Get the URI of this slot, using the default prefix of the implicit [[SchemaView]] if not
    * explicitly defined.
    */
  def uriOrCurie: UriOrCurie = SlotView.uri(slot, this)
}

private object SlotView:
  // Exposed for slot derivation in ClassView.
  def uri(slot: SlotDefinition, context: ElementView[?]): UriOrCurie =
    slot.slotUri.getOrElse(Uri(context.defaultPrefixUri + Case.deSpaceCase(slot.name)))

final case class EnumView(_enum: EnumDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[EnumDefinition] {
  def inner: EnumDefinition = _enum

  def uriOrCurie: UriOrCurie =
    _enum.enumUri.getOrElse(Uri(defaultPrefixUri + Case.PascalCase(_enum.name)))
}

final case class TypeView(_type: TypeDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[TypeDefinition] {
  def inner: TypeDefinition = _type

  /** Return the RDF subject type that corresponds to this type. This is used to create subjects in
    * the RDF representations.
    */
  def subjectType: SubjectType = inner.base match {
    case Some("URI") => SubjectType.uri
    case Some("Curie") => SubjectType.curie
    case Some("URIorCURIE") => SubjectType.uriOrCurie
    case _ => SubjectType.base
  }

  def uriOrCurie: UriOrCurie =
    _type.typeUri.getOrElse(Uri(defaultPrefixUri + _type.name))
}

final case class SubsetView(subset: SubsetDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[SubsetDefinition] {
  def inner: SubsetDefinition = subset

  def uriOrCurie: UriOrCurie =
    // there is no subset_uri in the metamodel
    Uri(defaultPrefixUri + Case.deSpaceCase(subset.name))
}
