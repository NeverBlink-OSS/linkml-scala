package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview
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

  /** Element type name, e.g. "class", "slot", "type", "enum", "subset", used for error messages.
    */
  def elementType: String

  /** Schema definition that defined this Element. This schema should be used for prefixes and
    * default ranges.
    */
  def definingSchema: SchemaDefinition

  /** The underlying Element, as defined in [[definingSchema]]
    */
  def inner: E

  /** The name of the underlying Element
    */
  final def name: String = inner.name

  /** The defining schema's prefix resolver */
  given definingPrefixResolver: PrefixResolver = sv.prefixResolvers(definingSchema)

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

private object ClassView:
  // Used to avoid as many allocations as possible when deriving slots.
  val emptySlotDef: SlotDefinitionImpl = SlotDefinitionImpl(name = "!!! invalid, internal !!!")

final case class ClassView(cls: ClassDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[ClassDefinition] {
  def elementType: String = "class"

  def inner: ClassDefinition = cls

  def uriOrCurie: UriOrCurie =
    cls.classUri.getOrElse(Uri.synthetic(defaultPrefixUri, Case.PascalCase(cls.name)))

  /** Derived attributes for this class and the identifier slot of a class, if it has one.
    */
  lazy val (derivedAttributes: Map[String, SlotView], identifier: Option[SlotView]) = {
    var idSv: SlotView = null
    val das = applicableSlots.foldLeft(Map.empty[String, SlotView]) { (acc, v) =>
      val sv = derivedSlot(v.ref, v.source)
      if (sv.slot.identifier) {
        idSv = sv
      }
      acc.updated(v.ref.value, sv)
    }
    (das, Option(idSv))
  }

  lazy val resolvedAttributes: Map[String, AttributeView] = {
    derivedAttributes.map((k, slot) =>
      k -> (slot.derivedRangeView.resolve.get match {
        case classView: ClassView =>
          if classView.uriStr == "https://w3id.org/linkml/Any" then AnyView(slot)
          else if !slot.derivedInlined then
            ClassReferenceAttributeView(
              slot,
              classView,
              classView.identifier.get.derivedRangeView.resolve.get match {
                case tv: TypeView => tv
                case _ => throw RuntimeException("ID slot is not type")
              },
            )
          else ClassInlineAttributeView(slot, classView, InlineType(slot))
        case tv: TypeView => TypeAttributeView(slot, tv)
        case ev: EnumView => EnumAttributeView(slot, ev)
        case _ => throw RuntimeException(s"Invalid range for ${cls.name}.${slot.name}")
      }),
    )
  }

  def collectionForm: CollectionForm = CollectionForm.of(this)

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
    slotView.derivedRangeView.resolve.collect { case tv: TypeView =>
      tv.subjectType match {
        // Fallback if the type does not define the prefix but the slot does
        case SubjectType.base =>
          slotView.implicitPrefixReference match {
            case Some(prefix) => SubjectType.implicitPrefix(prefix)
            case _ => SubjectType.base
          }
        case subject => subject
      }
    }
  })

  private def getParents(view: ClassView): Iterable[ClassView] =
    (view.cls.mixins ++ view.cls.isA).flatMap { r =>
      sv.resolve(r.asInstanceOf[Reference[ClassView]])
    }

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
    // Use empty slot base here to avoid an extra allocation
    var currentSlot = ClassView.emptySlotDef
    currentSlot = sv.applySlotUsage(currentSlot, slotRef.value, cls)
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
    val finalSlot = currentSlot.copy(
      name = slotRef.value,
      slotUri = Some(
        SlotView.uri(
          currentSlot.slotUri,
          slotRef.value,
          // For prefix resolution use the context of the original slot definition
          source,
        ),
      ),
      inlined = currentSlot.inlinedAsList || currentSlot.inlined,
      required = currentSlot.identifier || currentSlot.key || currentSlot.required,
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
  lazy val hasIdentifier: Boolean = identifier.isDefined

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
          fromSchema = Some(slot.definingSchema.id),
        ),
      ),
      slots = Seq.empty,
      slotUsage = Map.empty,
      fromSchema = Some(definingSchema.id),
    )
  }
}

final case class SlotView(slot: SlotDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[SlotDefinition] {
  def elementType: String = "slot"

  def inner: SlotDefinition = slot

  /** Resolved URI string for the implicit_prefix metaslot for this slot, if defined
    */
  def implicitPrefixReference: Option[String] =
    slot.implicitPrefix.flatMap(definingPrefixResolver.resolvePrefix)

  /** Get and dereference the direct parents (mixins + inheritance) of this slot
    *
    * @return
    *   Direct parents of the slot, mixins before inheritance
    */
  def parents: Iterable[SlotDefinition] = getParents(slot)

  private def getParents(slot: SlotDefinition): Iterable[SlotDefinition] =
    (slot.mixins ++ slot.isA).flatMap(sv.resolve)

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
    * type, enum, or class without an identifier
    *
    * @return
    *   true if the slot is inlined
    */
  def derivedInlined: Boolean =
    slot.inlined || (sv.resolve(derivedRangeView) match {
      case Some(cls: ClassView) => !cls.hasIdentifier
      case _ => true
    })

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
  def uriOrCurie: UriOrCurie = SlotView.uri(slot.slotUri, slot.name, this)
}

private object SlotView:
  // Exposed for slot derivation in ClassView.
  def uri(slotUri: Option[UriOrCurie], slotName: String, context: ElementView[?]): UriOrCurie =
    slotUri.getOrElse(Uri.synthetic(context.defaultPrefixUri, Case.deSpaceCase(slotName)))

final case class EnumView(_enum: EnumDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[EnumDefinition] {
  def elementType: String = "enum"

  def inner: EnumDefinition = _enum

  def uriOrCurie: UriOrCurie =
    _enum.enumUri.getOrElse(Uri.synthetic(defaultPrefixUri, Case.PascalCase(_enum.name)))

  /** Permissible values of this enum and their (possibly synthetic) meanings */
  lazy val derivedValues: Seq[(pv: PermissibleValue, meaning: UriOrCurie)] =
    _enum.permissibleValues.values.map(x =>
      x -> x.meaning.getOrElse(Uri.synthetic(defaultPrefixUri, Case.deSpaceCase(x.text))),
    ).toSeq

  lazy val toMeaning: Map[String, UriOrCurie] =
    derivedValues.map((x, meaning) => x.text -> meaning).toMap

  lazy val fromMeaning: Map[UriOrCurie, String] =
    derivedValues.map((x, meaning) => meaning -> x.text).toMap
}

final case class TypeView(_type: TypeDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[TypeDefinition] {
  def elementType: String = "type"

  def inner: TypeDefinition = _type

  /** Return the RDF subject type that corresponds to this type. This is used to create subjects in
    * the RDF representations.
    */
  def subjectType: SubjectType = runtimeType match {
    case UriType => SubjectType.uri
    case CurieType => SubjectType.curie
    case UriOrCurieType => SubjectType.uriOrCurie
    case _ =>
      inner.implicitPrefix match {
        case Some(prefix) =>
          val reference =
            definingPrefixResolver.resolvePrefix(prefix).getOrElse(
              sys.error(s"Unknown prefix: $prefix"),
            )
          SubjectType.implicitPrefix(reference)
        case None => SubjectType.base
      }
  }

  /** @return
    *   true if this type was defined as part of metamodel types (linkml:types)
    */
  def isPrimitive: Boolean = definingSchema.id.original.startsWith("https://w3id.org/linkml/types")

  /** @return
    *   true if this type should be represented as an RDF IRI
    */
  def isIri: Boolean = runtimeType match {
    case UriOrCurieType => true
    case UriType => true
    case CurieType => true
    case _ =>
      subjectType match {
        case SubjectType.implicitPrefix(_) => true
        case _ => false
      }
  }

  /** The [[RuntimeType]] representation of this type. Translates Python-ese and LinkML-py runtime
    * names into the enum. Falls back to [[UnknownType]].
    */
  def runtimeType: RuntimeType = inner.base match {
    case Some(value) =>
      value match {
        case "str" => StringType
        case "int" => IntegerType
        case "Bool" => BooleanType
        // thanks, python
        case "float" if inner.typeUri.contains("xsd:double") => DoubleType
        case "double" => DoubleType
        case "float" => FloatType
        case "Decimal" => DecimalType

        case "URI" => UriType
        case "Curie" => CurieType
        case "URIorCURIE" => UriOrCurieType
        case "NCName" => NcNameType

        case "XSDDateTime" => DateTimeType
        case "XSDDate" => DateType
        case "XSDTime" => TimeType

        case _ => UnknownType
      }
    case None => UnknownType
  }

  /** The [[CoreType]] representation of this type.
    */
  def coreType: CoreType = runtimeType.repr

  def uriOrCurie: UriOrCurie =
    _type.typeUri.getOrElse(Uri.synthetic(defaultPrefixUri, _type.name))
}

final case class SubsetView(subset: SubsetDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[SubsetDefinition] {
  def elementType: String = "subset"

  def inner: SubsetDefinition = subset

  def uriOrCurie: UriOrCurie =
    // there is no subset_uri in the metamodel
    Uri.synthetic(defaultPrefixUri, Case.deSpaceCase(subset.name))
}
