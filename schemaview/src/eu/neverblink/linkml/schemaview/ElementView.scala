package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview
import eu.neverblink.linkml.schemaview.CollectionForm.{CompactDict, SimpleDict}
import eu.neverblink.linkml.schemaview.expression.ConstructorExpression

import scala.collection.mutable.ListBuffer

/** Element views provide a rich interface for working with schema elements. They require an
  * implicit [[SchemaView]] and are always linked to a defining schema, which is the schema in which
  * the element was originally defined. This allows you to both have the full context of all
  * imported schemas and the ability to get back to the original definition of an element, which is
  * important for things like checking the default ranges or prefixes of the defining schema.
  *
  * @param sv
  *   Root SchemaView that (transitively) imported this Element
  * @tparam E
  *   The type of the underlying Element
  * @tparam R
  *   The type of default values for this element when resolving constructor expressions (e.g. from
  *   the `ifabsent` metaslot).
  */
sealed trait ElementView[E <: Element, R](using val sv: SchemaView) {

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

  /** The base name
    */
  final lazy val baseName: String = Case.base(name)

  def canonicalName: String

  final def modelUri: Uri = Uri.synthetic(defaultPrefixUri, canonicalName)

  /** The name of the underlying Element, aliased with the `alias` slot if defined, re-cased
    * appropriately if needed.
    */
  def aliasedName: String

  /** Evaluate a constructor expression (e.g. from the `ifabsent` metaslot) treating this element as
    * the range.
    *
    * May throw a [[ConstructorExpression.EvaluationException]] if the expression is invalid or
    * cannot be parsed into a value of type [[R]].
    *
    * @return
    *   The evaluated value, or None if constructor expressions are not supported for this element
    *   type.
    */
  // TODO LNK-63: implement other types in ifabsent
  private[schemaview] def evaluateConstructor(expr: String): Option[R] = None

  /** The defining schema's prefix resolver */
  given definingPrefixResolver: PrefixResolver = sv.getPrefixResolver(definingSchema)

  /** Get the URI of this element, using the default prefix of the implicit [[SchemaView]] if not
    * explicitly defined.
    */
  def uriOrCurie: UriOrCurie

  /** Get the URI of this element in string form, using the default prefix of the implicit
    * [[SchemaView]] if not explicitly defined.
    */
  lazy val uriStr: String = uriOrCurie.uri

  /** Get the default URI prefix (prefix map value) for the defining schema, with a fallback to the
    * schema ID (this fallback mirrors the python implementation).
    */
  final def defaultPrefixUri: String = sv.getDefaultPrefix(definingSchema)
}

private object ClassView:
  // Used to avoid as many allocations as possible when deriving slots.
  val emptySlotDef: SlotDefinitionImpl = SlotDefinitionImpl(name = "!!! invalid, internal !!!")

final case class ClassView(cls: ClassDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[ClassDefinition, AnyRef] {
  def elementType: String = "class"

  def inner: ClassDefinition = cls

  def canonicalName: String = Case.PascalCase(baseName)

  def uriOrCurie: UriOrCurie =
    cls.classUri.getOrElseFast(modelUri)

  def aliasedName: String =
    cls.alias.getOrElseFast(canonicalName)

  /** Derived attributes for this class and the identifier slot of a class, if it has one.
    */
  lazy val (derivedAttributes: Map[String, SlotView], identifier: Option[SlotView]) = {
    var idSv: SlotView = null
    var das = Map.empty[String, SlotView]
    // Get all slots that are applicable to this class definition.
    // Returns a sequence of pairs of slot name and the derived slot definition,
    // where the source is either a ClassView (if the slot is defined as an attribute) or
    // a SlotView (if the slot is defined as a top-level slot). The source is used
    // for default prefix and range resolution according to the original schema file.
    // See: https://linkml.io/linkml-model/latest/docs/specification/04derived-schemas/#function-applicable-slots
    ancestorsWithSelf.foreach { anc =>
      val keys = anc.cls.attributes.keysIterator
      while (keys.hasNext) {
        val name = keys.next()
        if (!das.contains(name)) {
          val ref = new Reference[SlotDefinition](name)
          val sv = derivedSlot(ref, anc)
          if (sv.slot.identifier) idSv = sv
          das = das.updated(ref.value, sv)
        }
      }
      val slots = anc.cls.slots.iterator
      while (slots.hasNext) {
        val ref = slots.next()
        val name = ref.value
        if (!das.contains(name)) {
          val sv = derivedSlot(ref, ref.asInstanceOf[Reference[SlotView]].resolve.get)
          if (sv.slot.identifier) idSv = sv
          das = das.updated(name, sv)
        }
      }
    }
    (das, Option(idSv))
  }

  /** The slot/type bundle for the identifier of this class, if it exists */
  lazy val identifierView: Option[TypeAttributeView] = identifier.mapFast(idSlot => {
    idSlot.derivedRange.resolve.get match {
      case tv: TypeView => new TypeAttributeView(idSlot, this, tv)
      case x =>
        throw RuntimeException(s"Invalid identifier slot: ${cls.name}.${idSlot.name} -> ${x.name}")
    }
  })

  /** Derived attribute views for this class. Generators should prefer using these bundles if
    * possible.
    */
  lazy val attributeViews: Map[String, AttributeView] = {
    derivedAttributes.map((k, slot) =>
      (
        k,
        slot.derivedRange.resolve.get match {
          case classView: ClassView =>
            if classView.isAny then new AnyView(slot, this)
            else if !slot.derivedInlined then
              new ClassReferenceAttributeView(
                slot,
                this,
                classView,
                classView.identifierView.get,
              )
            else new ClassInlineAttributeView(slot, this, classView, InlineType(slot))
          case tv: TypeView => new TypeAttributeView(slot, this, tv)
          case ev: EnumView => new EnumAttributeView(slot, this, ev)
          case x => throw RuntimeException(s"Invalid range: ${cls.name}.${slot.name} -> ${x.name}")
        },
      ),
    )
  }

  /** @return
    *   true if this class should be treated as an `Any`
    */
  def isAny: Boolean = "https://w3id.org/linkml/Any".equals(uriStr)

  /** The collection form of this class, checking whether dict inlines are applicable.
    */
  lazy val collectionForm: CollectionForm = CollectionForm.of(this)

  /** Direct parents (mixins + inheritance) of this class
    */
  lazy val parents: Seq[ClassView] = {
    val parents = new ListBuffer[ClassView]
    cls.mixins.foreach { r =>
      sv.resolve(r.asInstanceOf[Reference[ClassView]]).foreachFast(parents.addOne)
    }
    cls.isA.foreachFast { r =>
      sv.resolve(r.asInstanceOf[Reference[ClassView]]).foreachFast(parents.addOne)
    }
    parents.toList
  }

  /** Ancestors of the class in LinkML's "depth-first" order, starting from self:
    * `ancestors(x) = x, x.mixins, x.isA, ancestors(x.isA), ancestors(x.mixins)`
    */
  lazy val ancestorsWithSelf: Iterable[ClassView] =
    Closure.get(Seq(this), _.parents, true, Vector.newBuilder, true, _.cls.name)

  /** Get the subject type for this class, if possible. Uses the class' identifier slot's range.
    * @return
    *   The subject type, or None if the class does not have an identifier
    */
  def subjectType: Option[SubjectType] = identifierView.mapFast(_.subjectType)

  /** Get the slots that are directly defined in this class.
    */
  def directSlots: Seq[Reference[SlotDefinition]] = getDirectSlots(cls)

  private def getDirectSlots(cls: ClassDefinition): Seq[Reference[SlotDefinition]] = {
    val slots = new ListBuffer[Reference[SlotDefinition]]
    slots.addAll(cls.slots)
    cls.attributes.keys.foreach(a => slots.addOne(Reference[SlotDefinition](a)))
    slots.toList
  }

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
    ancestorsWithSelf.exists {
      val name = slotRef.value
      anc => anc.cls.attributes.contains(name)
    }

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
      source: ElementView[?, ?],
  ): SlotView = {
    // Use empty slot base here to avoid an extra allocation
    var currentSlot = ClassView.emptySlotDef
    currentSlot = sv.applySlotUsage(currentSlot, slotRef.value, cls)
    sv.resolve(slotRef.asInstanceOf[Reference[SlotView]]).foreachFast { (resolved: SlotView) =>
      // Note this is a bit off-spec, but it's a pretty reasonable
      if (!isSlotFromAttributes(slotRef)) {
        resolved.ancestorsWithSelf.foreach {
          var i = 0
          ancestorSlotView =>
            currentSlot =
              val slotDef = ancestorSlotView.slot.asInstanceOf[SlotDefinitionImpl]
              if (i > 0) currentSlot.combineInherited(slotDef, sv.combineRange)
              else currentSlot.combineWith(slotDef, sv.combineRange)
            i += 1
        }
      }
    }
    val finalSlot = currentSlot.copy(
      name = slotRef.value,
      slotUri = new Some(
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
    new SlotView(finalSlot, source.definingSchema)
  }

  /** Test whether this class definition has an identifier slot
    *
    * @return
    *   true if the class has an identifier
    */
  lazy val hasIdentifier: Boolean = identifier.isDefined

  /** Check the tree_root_as extension for this class and return the corresponding InlineType. If
    * the extension is not present, return InlineType.plain as the default.
    *
    * @param overrideType
    *   An optional override for the tree_root_as extension value. If provided, this value will be
    *   used instead of checking the class extensions.
    */
  def treeRootInlineType(overrideType: Option[String]): InlineType =
    overrideType.orElseFast {
      cls.extensions.get("tree_root_as").mapFast(_.extensionValue.value.strip)
    }.mapFast { v =>
      Case.camelCase(v) match {
        case "plain" => InlineType.plain
        case "optional" => InlineType.optional
        case "list" => InlineType.list
        case "simpleDict" =>
          collectionForm match {
            case form: SimpleDict => InlineType.dict(form)
            case _ =>
              throw new IllegalArgumentException(
                s"Class '$name' has 'tree_root_as: $v', but it cannot be inlined in simpleDict form",
              )
          }
        case "compactDict" =>
          collectionForm match {
            case form: DictForm =>
              // override simpledict inference to
              InlineType.dict(CompactDict(form.key))
            case _ =>
              throw new IllegalArgumentException(
                s"Class '$name' has 'tree_root_as: $v', but it cannot be inlined in compactDict form",
              )
          }
        case _ =>
          throw new IllegalArgumentException({
            if (overrideType.isEmpty)
              s"Class '$name' has unknown 'tree_root_as' extension value: '$v'"
            else s"Class '$name' has unknown 'tree_root_as' override value: '$v'"
          })
      }
    }.getOrElseFast(InlineType.plain)

  /** Materialize this [[ClassView]] into a derived [[ClassDefinition]]. This inlines all slots as
    * attributes, and clears any inheritance slots. Additionally, sets the class uri using
    * [[SchemaView]] logic.
    */
  def materialize: ClassDefinitionImpl =
    inner.asInstanceOf[ClassDefinitionImpl].copy(
      classUri = new Some(uriOrCurie),
      isA = None,
      mixins = Nil,
      attributes = derivedAttributes.map((slotKey, slot) =>
        (
          slotKey,
          slot.inner.asInstanceOf[SlotDefinitionImpl].copy(
            isA = None,
            mixins = Nil,
            fromSchema = new Some(slot.definingSchema.id),
          ),
        ),
      ),
      slots = Nil,
      slotUsage = Map.empty,
      fromSchema = new Some(definingSchema.id),
    )
}

final case class SlotView(slot: SlotDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[SlotDefinition, Nothing] {
  def elementType: String = "slot"

  def inner: SlotDefinition = slot

  override def canonicalName: String = {
    if name.forall(Case.isAlphanumeric) then Case.baseToCapital(baseName, false)
    else baseName
  }

  override def aliasedName: String =
    slot.alias.getOrElseFast(canonicalName)

  /** Resolved URI string for the implicit_prefix metaslot for this slot, if defined
    */
  def implicitPrefixReference: Option[String] =
    slot.implicitPrefix.flatMapFast(definingPrefixResolver.resolvePrefix)

  /** Direct parents (mixins + inheritance) of this slot
    */
  lazy val parents: Iterable[SlotView] = {
    val parents = new ListBuffer[SlotView]
    slot.mixins.foreach { r =>
      sv.resolve(r.asInstanceOf[Reference[SlotView]]).foreachFast(parents.addOne)
    }
    slot.isA.foreachFast { r =>
      sv.resolve(r.asInstanceOf[Reference[SlotView]]).foreachFast(parents.addOne)
    }
    parents.toList
  }

  /** Ancestors of the slot in LinkML's "depth-first" order, starting from self:
    * `ancestors(x) = x, x.mixins, x.isA, ancestors(x.isA), ancestors(x.mixins)`
    */
  lazy val ancestorsWithSelf: Iterable[SlotView] =
    Closure.get(Seq(this), _.parents, true, Vector.newBuilder, true, _.slot.name)

  /** Test whether this slot is declared as inlined, or is implicitly inlined as its range is a
    * type, enum, or class without an identifier
    *
    * @return
    *   true if the slot is inlined
    */
  def derivedInlined: Boolean =
    slot.inlined || (sv.resolve(derivedRange) match {
      case Some(cls: ClassView) => !cls.hasIdentifier
      case _ => true
    })

  /** Get the range of this slot as a reference to an [[ElementView]], with missing values filled
    * with `default_range` from the implicit [[SchemaView]]. Does NOT take inheritance into account:
    * Make sure you use this method after class/slot derivation is performed.
    */
  def derivedRange: Reference[ElementView[?, ?]] =
    slot.range.getOrElseFast {
      sv.getDefaultRange(definingSchema)
    }.asInstanceOf[Reference[ElementView[?, ?]]]

  /** Get the default value of this slot if the `ifabsent` metaslot is defined.
    *
    * May throw a [[ConstructorExpression.EvaluationException]] if the `ifabsent` expression is
    * invalid or cannot be parsed into a value of type R.
    *
    * @param range
    *   The range of the slot. Obtain it from [[SlotView.derivedRange]].
    */
  def ifAbsent[R](range: ElementView[?, R]): Option[R] =
    slot.ifabsent.flatMapFast { ia =>
      range.evaluateConstructor(ia)
    }

  /** Get the URI of this slot, using the default prefix of the implicit [[SchemaView]] if not
    * explicitly defined.
    */
  def uriOrCurie: UriOrCurie = SlotView.uri(slot.slotUri, slot.name, this)
}

private object SlotView:
  // Exposed for slot derivation in ClassView.
  def uri(slotUri: Option[UriOrCurie], slotName: String, context: ElementView[?, ?]): UriOrCurie =
    slotUri.getOrElseFast {
      Uri.synthetic(
        // TODO LNK-159: make it by spec and migrate triples instead
        context.defaultPrefixUri,
        if slotName.forall(Case.isAlphanumeric) then Case.baseToCamel(Case.base(slotName))
        else Case.base(slotName),
      )
    }

final case class EnumView(_enum: EnumDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[EnumDefinition, PermissibleValue] {
  def elementType: String = "enum"

  def inner: EnumDefinition = _enum

  def canonicalName: String = Case.PascalCase(baseName)

  override def aliasedName: String = canonicalName

  override private[schemaview] def evaluateConstructor(expr: String): Option[PermissibleValue] =
    new Some(ConstructorExpression.evaluateEnum(expr, this))

  def uriOrCurie: UriOrCurie =
    _enum.enumUri.getOrElseFast(Uri.synthetic(defaultPrefixUri, Case.baseToPascal(canonicalName)))

  /** Permissible values of this enum and their (possibly synthetic) meanings */
  lazy val derivedValues: Seq[(pv: PermissibleValue, meaning: UriOrCurie)] =
    _enum.permissibleValues.values
      .foldLeft(new ListBuffer[(pv: PermissibleValue, meaning: UriOrCurie)]) { (acc, x) =>
        acc.addOne(
          (
            x,
            x.meaning.getOrElseFast {
              Uri.synthetic(defaultPrefixUri, canonicalName + "." + Case.base(x.text).toUpperCase)
            },
          ),
        )
      }.toList

  lazy val toMeaning: Map[String, UriOrCurie] =
    derivedValues.foldLeft(Map.newBuilder[String, UriOrCurie]) { case (acc, (x, meaning)) =>
      acc.addOne((x.text, meaning))
    }.result()

  lazy val fromMeaning: Map[UriOrCurie, String] =
    derivedValues.foldLeft(Map.newBuilder[UriOrCurie, String]) { case (acc, (x, meaning)) =>
      acc.addOne((meaning, x.text))
    }.result()
}

// TODO LNK-63:
//  This might be incorrect, to be finalized when we implement default values for datatypes.
type RuntimeScalar = String | Int | Boolean | Float | Double | BigDecimal | UriOrCurie

final case class TypeView(_type: TypeDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[TypeDefinition, RuntimeScalar] {
  def elementType: String = "type"

  def inner: TypeDefinition = _type

  def canonicalName: String = baseName

  override def aliasedName: String = canonicalName

  /** Return the RDF subject type that corresponds to this type. This is used to create subjects in
    * the RDF representations.
    */
  def subjectType: SubjectType = runtimeType match {
    case _: UriType.type => SubjectType.uri
    case _: CurieType.type => SubjectType.curie
    case _: UriOrCurieType.type => SubjectType.uriOrCurie
    case _ =>
      inner.implicitPrefix.foldFast(SubjectType.base) { prefix =>
        val reference =
          definingPrefixResolver.resolvePrefix(prefix).getOrElseFast {
            throw RuntimeException(s"Unknown implicit prefix for type $name: $prefix")
          }
        new SubjectType.implicitPrefix(reference)
      }
  }

  /** @return
    *   true if this type was defined as part of metamodel types (linkml:types)
    */
  def isPrimitive: Boolean = definingSchema.id.original.startsWith("https://w3id.org/linkml/types")

  /** Whether this type should be an RDF IRI or an RDF Literal
    *
    * @return
    *   true if this type should be an RDF IRI
    */
  def isIri: Boolean = subjectType.isIri

  /** The [[RuntimeType]] representation of this type. Translates Python-ese and LinkML-py runtime
    * names into the enum. Falls back to [[UnknownType]].
    */
  def runtimeType: RuntimeType = inner.base.foldFast(UnknownType) {
    case "str" => StringType
    case "int" => IntegerType
    case "Bool" => BooleanType
    case "double" => DoubleType
    case "float" =>
      // thanks, python
      if (inner.typeUri.contains("xsd:double")) DoubleType
      else FloatType
    case "Decimal" => DecimalType
    case "URI" => UriType
    case "Curie" => CurieType
    case "URIorCURIE" => UriOrCurieType
    case "NCName" => NcNameType
    case "LocalizedText" => LocalizedTextType
    case "XSDDateTime" => DateTimeType
    case "XSDDate" => DateType
    case "XSDTime" => TimeType
    case _ => UnknownType
  }

  /** The [[CoreType]] representation of this type.
    */
  def coreType: CoreType = runtimeType.repr

  def uriOrCurie: UriOrCurie = _type.typeUri.getOrElseFast(modelUri)
}

final case class SubsetView(subset: SubsetDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[SubsetDefinition, Nothing] {
  def elementType: String = "subset"

  def inner: SubsetDefinition = subset

  override def aliasedName: String = Case.escaped(name)

  def uriOrCurie: UriOrCurie =
    // there is no subset_uri in the metamodel
    Uri.synthetic(defaultPrefixUri, Case.escaped(subset.name))

  override def canonicalName: String = baseName
}
