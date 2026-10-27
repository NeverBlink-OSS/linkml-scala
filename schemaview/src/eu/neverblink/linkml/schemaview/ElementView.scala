package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml
import eu.neverblink.linkml.metamodel.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview
import eu.neverblink.linkml.schemaview.CollectionForm.{CompactDict, SimpleDict}
import eu.neverblink.linkml.schemaview.expression.ConstructorExpression

import scala.collection.mutable
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

  /** Schema definition that defined this Element. This schema should be used for prefixes and
    * default ranges.
    */
  def definingSchema: SchemaDefinition

  /** The underlying Element, as defined in [[definingSchema]]
    */
  def inner: E

  /** The raw name of the underlying Element. In generators, this should be only used for lookups
    * within LinkML. Symbolic names should use the `Renamer` framework, serialized names should use
    * [[aliasedName]] or [[uriStr]] instead.
    */
  final def name: String = inner.name

  /** The base name: [[name]] converted to strict snake_case. This should be used as the base for
    * the `Renamer` methods.
    * @see
    *   [[Case.base]]
    * @see
    *   [[Case.baseToPascal]]
    */
  final lazy val baseName: String = Case.base(name)

  /** The "canonical" name of this element: [[name]] re-cased as is usual for its kind of element,
    * such as `PascalCase` for classes. It is the last part of [[modelUri]]. Generators should use
    * [[aliasedName]] for serialization instead.
    */
  def canonicalName: String

  /** Model URI of this element. This is the fallback URI to be used for an element that does not
    * have an explicit `???_uri`, like `class_uri`.
    */
  private[schemaview] final def modelUri: Uri = Uri.synthetic(defaultPrefixUri, canonicalName)

  /** The name of the underlying Element, overridden with the `alias` slot if defined, re-cased
    * appropriately.
    *
    * This MUST be used when serializing to non-RDF formats.
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

  /** The prefixes of the defining schema */
  def definingPrefixes: PrefixMap = sv.getPrefixes(definingSchema)

  /** Get the URI of this element, using the default prefix of the implicit [[SchemaView]] if not
    * explicitly defined.
    */
  def uriOrCurie: UriOrCurie

  /** Get the URI of this element in string form, using the default prefix of the implicit
    * [[SchemaView]] if not explicitly defined.
    *
    * This MUST be used when serializing to RDF formats.
    */
  lazy val uriStr: String = uriOrCurie.original

  /** Get the default URI prefix (prefix map value) for the defining schema, with a fallback to the
    * schema ID (this fallback mirrors the python implementation).
    */
  final private[schemaview] def defaultPrefixUri: String = sv.getDefaultPrefix(definingSchema)
}

private object ClassView:
  // Used to avoid as many allocations as possible when deriving slots.
  val emptySlotDef: SlotDefinition = SlotDefinition(name = "!!! invalid, internal !!!")

final case class ClassView(cls: ClassDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[ClassDefinition, AnyRef] {
  def inner: ClassDefinition = cls

  def canonicalName: String = Case.baseToPascal(baseName)

  def uriOrCurie: UriOrCurie =
    cls.classUri.getOrElseFast(modelUri)

  def aliasedName: String =
    cls.alias.getOrElseFast(name)

  /** All slots of this class, its own and inherited ones, computed together in one pass:
    *   - `derivedAttributes`: the slots, keyed by slot name
    *   - `identifier`: the slot with `identifier: true`, if there is one
    *   - `typeDesignator`: the slot with `designates_type: true`, if there is one
    */
  lazy val (
    derivedAttributes: Map[String, SlotView],
    identifier: Option[SlotView],
    typeDesignator: Option[SlotView],
  ) = {
    var idSv: SlotView = null
    var tdSv: SlotView = null
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
          if (sv.slot.designatesType) tdSv = sv
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
          if (sv.slot.designatesType) tdSv = sv
          das = das.updated(name, sv)
        }
      }
    }
    (das, Option(idSv), Option(tdSv))
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

  /** Values of the [[attributeViews]] map, with the common sorting.
    */
  lazy val sortedAttributeViews: Seq[AttributeView] = {
    attributeViews.values.toVector.sortBy(x => sv.elementOrder(x.slotView.slot))
  }

  /** @return
    *   true if this class should be treated as an `Any`
    */
  def isAny: Boolean = "https://w3id.org/linkml/Any".equals(uriStr)

  /** @return
    *   true if the class can have instances of its own: it is neither `abstract` nor a `mixin`
    */
  def isConcrete: Boolean = !cls.`abstract` && !cls.mixin

  /** @return
    *   true if another class in the loaded schemas names this class in its `is_a` or `mixins`
    */
  def hasDescendants: Boolean = sv.classesWithDescendants.contains(name)

  /** Whether instances of this class may have data beyond its slots, as set by its `extra_slots`.
    *
    * An explicit `allowed` wins, even over a `range_expression`. Without it, a `range_expression`
    * allows data matching it, so generators that cannot check the data against it allow any. Only
    * the class' own `extra_slots` counts, since LinkML does not inherit it.
    *
    * @see
    *   https://w3id.org/linkml/extra_slots
    */
  def allowsExtraSlots: Boolean =
    cls.extraSlots.foldFast(false)(e => e.allowed.getOrElseFast(e.rangeExpression.isDefined))

  /** The collection form of this class, checking whether dict inlines are applicable.
    */
  lazy val collectionForm: CollectionForm = CollectionForm.of(this)

  /** The slot/type bundle for the [[typeDesignator type designator]] of this class, if it has one:
    * the slot whose value in an instance tells which class the instance belongs to, with its range
    * resolved.
    *
    * @see
    *   https://linkml.io/linkml/schemas/type-designators.html
    */
  lazy val typeDesignatorView: Option[AttributeView] =
    typeDesignator.flatMapFast(sv => attributeViews.get(sv.name))

  /** The classes whose instances a slot with this class as its range can hold, if this class has a
    * type designator: this class, if it is concrete, and all its concrete descendants, in the
    * common order. The type designator value of an instance tells which of them it is. Empty if
    * this class has no type designator.
    */
  lazy val typeDesignatorMembers: Seq[ClassView] =
    if typeDesignator.isEmpty then Nil
    else sv.sortedClasses.filter(c => c.isConcrete && c.ancestorsWithSelf.exists(_.name == name))

  /** Whether a slot with this class as its range can hold instances of more than one class, chosen
    * by the type designator value. This is true when [[typeDesignatorMembers]] has another class
    * than this one, so it is false for a class without a type designator, and for a concrete class
    * without concrete descendants.
    */
  def isTypeDesignatorUnion: Boolean =
    if isConcrete then typeDesignatorMembers.sizeIs > 1 else typeDesignatorMembers.nonEmpty

  /** The values of the type designator that mark an instance as being of this class. They depend on
    * the range of the type designator:
    *   - `string`: the class name
    *   - `uri`: the class URI
    *   - `curie`: the class CURIE, made with the prefixes of the defining schema, or the class URI
    *     if no prefix matches it
    *   - `uriorcurie`: the class URI and the class CURIE, or just one value when they are the same
    *
    * Empty if this class has no type designator, or its range is some other type.
    */
  lazy val typeDesignatorValues: Seq[String] =
    typeDesignatorView match {
      case Some(tav: TypeAttributeView) =>
        tav.typeView.runtimeType match {
          case _: StringType.type => cls.name :: Nil
          case _: UriType.type => uriStr :: Nil
          case _: CurieType.type => compactUri :: Nil
          case _: UriOrCurieType.type =>
            val curie = compactUri
            if (uriStr.equals(curie)) uriStr :: Nil else uriStr :: curie :: Nil
          case _ => Nil
        }
      case _ => Nil
    }

  /** The class URI as a CURIE, made with the prefixes of the defining schema, or the class URI if
    * no prefix covers it.
    */
  lazy val compactUri: String = {
    val curie = definingPrefixes.compactOrNull(uriStr)
    if (curie ne null) curie else uriStr
  }

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
              val slotDef = ancestorSlotView.slot
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
      Case.baseToCamel(Case.base(v)) match {
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
  def materialize: ClassDefinition =
    inner.copy(
      classUri = new Some(uriOrCurie),
      isA = None,
      mixins = Nil,
      attributes = derivedAttributes.map((slotKey, slot) =>
        (
          slotKey,
          slot.inner.copy(
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
  def inner: SlotDefinition = slot

  override def canonicalName: String =
    baseName

  override def aliasedName: String =
    slot.alias.getOrElseFast(name)

  /** @see [[slot.pattern]] */
  def pattern: Option[LinkmlPattern] = LinkmlPattern.option(slot.pattern)

  /** Resolved URI string for the implicit_prefix metaslot for this slot, if defined
    */
  def implicitPrefixReference: Option[String] =
    slot.implicitPrefix.flatMapFast(p => Option(definingPrefixes.namespaceOrNull(p)))

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
        context.defaultPrefixUri,
        Case.base(slotName),
      )
    }

final case class EnumView(_enum: EnumDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[EnumDefinition, PermissibleValue] {
  def inner: EnumDefinition = _enum

  def canonicalName: String = Case.baseToPascal(baseName)

  override def aliasedName: String = _enum.alias.getOrElse(name)

  lazy val parents: Iterable[EnumView | ClassView] =
    (_enum.isA ++ _enum.mixins).flatMap(_.resolve).collect {
      case definition: ClassDefinition => sv.classes(definition.name)
      case definition: EnumDefinition => sv.enums(definition.name)
    }

  override private[schemaview] def evaluateConstructor(expr: String): Option[PermissibleValue] =
    new Some(ConstructorExpression.evaluateEnum(expr, this))

  def uriOrCurie: UriOrCurie =
    _enum.enumUri.getOrElseFast(Uri.synthetic(defaultPrefixUri, Case.baseToPascal(canonicalName)))

  /** Permissible values of this enum and their (possibly synthetic) meanings */
  lazy val derivedValues: Seq[PermissibleValueView] =
    _enum.permissibleValues.values
      .foldLeft(new ListBuffer[PermissibleValueView]) { (acc, pv) =>
        acc.addOne(PermissibleValueView(pv, this))
      }.toList

  /** The aliased names (non-RDF serialization form) of the permissible values of this enum.
    */
  def aliasedNames: Seq[String] =
    derivedValues.map(_.aliasedName)

  /** The meanings (RDF serialization form) of the permissible values of this enum.
    */
  def meanings: Seq[Uri] =
    derivedValues.map(_.meaning)

  /** Map of aliased names to meanings
    */
  lazy val toMeaning: Map[String, Uri] =
    derivedValues.foldLeft(Map.newBuilder[String, Uri]) { case (acc, valueView) =>
      acc.addOne((valueView.aliasedName, valueView.meaning))
    }.result()

  /** Map of meanings to aliased names
    */
  lazy val fromMeaning: Map[Uri, String] =
    derivedValues.foldLeft(Map.newBuilder[Uri, String]) { case (acc, valueView) =>
      acc.addOne((valueView.meaning, valueView.aliasedName))
    }.result()
}

/** View of a [[PermissibleValue]], which is not an element, but has quite a large overlap in
  * methods
  */
final case class PermissibleValueView(pv: PermissibleValue, enumView: EnumView) {

  /** @see
    *   [[ElementView.baseName]]
    */
  def baseName: String = Case.base(pv.text)

  /** @see
    *   [[ElementView.canonicalName]]
    */
  def canonicalName: String = Case.baseToScreamingSnake(baseName)

  /** @see
    *   [[ElementView.aliasedName]]
    */
  def aliasedName: String = pv.alias.getOrElseFast(pv.text)

  /** @see
    *   [[ElementView.modelUri]]
    */
  def modelUri: Uri = Uri.synthetic(
    enumView.defaultPrefixUri,
    enumView.canonicalName + "." + Case.base(pv.text).toUpperCase,
  )

  /** @see
    *   [[PermissibleValue.meaning]]
    */
  def meaning: Uri = pv.meaning.foldFast(modelUri)(x => Uri(x.original))

  /** @see
    *   [[ElementView.uriStr]]
    */
  def uriStr: String = meaning.original
}

// TODO LNK-63:
//  This might be incorrect, to be finalized when we implement default values for datatypes.
type RuntimeScalar = String | Int | Boolean | Float | Double | BigDecimal | UriOrCurie

final case class TypeView(_type: TypeDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[TypeDefinition, RuntimeScalar] {
  def inner: TypeDefinition = _type

  def canonicalName: String = baseName

  override def aliasedName: String = _type.alias.getOrElseFast(name)

  /** The parent declared through `typeof`, if present and a type. */
  lazy val parents: Seq[TypeView] =
    _type.typeof.flatMapFast(ref => sv.types.get(ref.value)).toList

  /** The types declared in `union_of`, in declaration order, skipping references to other kinds of
    * elements. Members do not supply inherited scalar properties. See [[unionAlternatives]] for the
    * flattened members used by the generators.
    */
  lazy val unionMembers: Seq[TypeView] =
    _type.unionOf.flatMap(ref => sv.types.get(ref.value))

  /** The members of the union this type is: its own `union_of`, or else the `union_of` of its
    * nearest `typeof` ancestor that declares one. Nested unions are not flattened. Empty for types
    * that are not unions.
    */
  lazy val inheritedUnionMembers: Seq[TypeView] =
    ancestorsWithSelf.find(_._type.unionOf.nonEmpty).foldFast(Nil)(_.unionMembers)

  /** The non-union types a value of this type can take, if this type is a union, in declaration
    * order and without duplicates. Nested unions are flattened. A type that does not declare
    * `union_of` itself takes the union of its nearest `typeof` ancestor that does. Empty for types
    * that are not unions.
    *
    * A union that contains itself is rejected by [[SchemaValidator]]. Here such a member is
    * skipped, so that this always terminates.
    */
  lazy val unionAlternatives: Seq[TypeView] = {
    def flatten(members: Seq[TypeView], path: List[String]): Seq[TypeView] =
      members.flatMap { member =>
        val nested = member.inheritedUnionMembers
        if (nested.isEmpty) member :: Nil
        else if (path.contains(member.name)) Nil
        else flatten(nested, member.name :: path)
      }

    flatten(inheritedUnionMembers, name :: Nil).distinctBy(_.name)
  }

  /** Whether values of this type can take more than one type, through `union_of`. */
  def isUnion: Boolean = unionAlternatives.nonEmpty

  /** The types a value of this type can take, each with the constraints that apply to it. See
    * [[ValueAlternative]].
    *
    * A type that is not a union has one alternative: itself. A union has one alternative per
    * member, in declaration order and without duplicates, with nested unions flattened. The union's
    * own constraints have precedence over the ones of its members. A member that contains this
    * union is skipped, as in [[unionAlternatives]].
    */
  lazy val alternatives: Seq[ValueAlternative] = alternativesAvoiding(Nil)

  private def alternativesAvoiding(unions: List[String]): Seq[ValueAlternative] = {
    val members = inheritedUnionMembers
    if (members.isEmpty) ValueAlternative.of(this) :: Nil
    else {
      val path = name :: unions
      val definition = derivedType
      val b = Vector.newBuilder[ValueAlternative]
      val seen = mutable.HashSet.empty[ValueAlternative]
      members.foreach { member =>
        if (!path.contains(member.name)) {
          member.alternativesAvoiding(path).foreach { a =>
            val alternative = a.withConstraintsOf(definition)
            if (seen.add(alternative)) b.addOne(alternative)
          }
        }
      }
      b.result()
    }
  }

  /** This type followed by its `typeof` ancestors, nearest first. A `typeof` cycle is rejected by
    * [[SchemaValidator]]. Here each type is visited once, so that this always terminates.
    */
  lazy val ancestorsWithSelf: Iterable[TypeView] =
    Closure.get(
      Seq(this),
      _.parents,
      reflexive = true,
      resultBuilder = Vector.newBuilder,
      useHashCode = true,
      toUniqueValue = _.name,
    )

  /** The pattern of this type or the nearest ancestor that has one. */
  def pattern: Option[LinkmlPattern] = LinkmlPattern.option(derivedType.pattern)

  /** Fill the metamodel's inheritable type properties, keeping the closest explicit value. The
    * original declaration remains available through [[inner]].
    */
  lazy val derivedType: TypeDefinition =
    ancestorsWithSelf.iterator.drop(1)
      .foldLeft(_type) { (derived, ancestor) =>
        val parent = ancestor._type
        derived.copy(
          base = combineOption(derived.base, parent.base, combineFallback),
          typeUri = derived.typeUri.orElseFast(parent.typeUri),
          repr = combineOption(derived.repr, parent.repr, combineFallback),
          pattern = combineOption(derived.pattern, parent.pattern, combinePattern),
          structuredPattern =
            combineOption(derived.structuredPattern, parent.structuredPattern, combineFallback),
          equalsString = combineOption(derived.equalsString, parent.equalsString, combineFallback),
          equalsStringIn =
            if derived.equalsStringIn.nonEmpty then derived.equalsStringIn
            else parent.equalsStringIn,
          equalsNumber = combineOption(derived.equalsNumber, parent.equalsNumber, combineFallback),
          minimumValue = combineOption(derived.minimumValue, parent.minimumValue, combineMin),
          maximumValue = combineOption(derived.maximumValue, parent.maximumValue, combineMax),
          minimumLength =
            combineOption(derived.minimumLength, parent.minimumLength, combineFallback),
          maximumLength =
            combineOption(derived.maximumLength, parent.maximumLength, combineFallback),
        )
      }

  /** Return the RDF subject type that corresponds to this type. This is used to create subjects in
    * the RDF representations.
    */
  def subjectType: SubjectType = runtimeType match {
    case _: UriType.type => SubjectType.uri
    case _: CurieType.type => SubjectType.curie
    case _: UriOrCurieType.type => SubjectType.uriOrCurie
    case _ =>
      inner.implicitPrefix.foldFast(SubjectType.base) { prefix =>
        val reference = definingPrefixes.namespaceOrNull(prefix)
        if (reference eq null) {
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
  def runtimeType: RuntimeType = derivedType.base.foldFast(UnknownType) {
    case "str" => StringType
    case "int" => IntegerType
    case "Bool" => BooleanType
    case "double" => DoubleType
    case "float" =>
      // "thanks, python" => LinkML's float and double both use the Python base `float`.
      val typeUri = derivedType.typeUri.foldFast("")(_.original)
      if (typeUri == "http://www.w3.org/2001/XMLSchema#double") DoubleType
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

  def uriOrCurie: UriOrCurie = derivedType.typeUri.getOrElseFast(modelUri)
}

final case class SubsetView(subset: SubsetDefinition, definingSchema: SchemaDefinition)(using
    sv: SchemaView,
) extends ElementView[SubsetDefinition, Nothing] {
  def inner: SubsetDefinition = subset

  override def aliasedName: String = baseName

  def uriOrCurie: UriOrCurie =
    // there is no subset_uri in the metamodel
    Uri.synthetic(defaultPrefixUri, baseName)

  override def canonicalName: String = baseName
}
