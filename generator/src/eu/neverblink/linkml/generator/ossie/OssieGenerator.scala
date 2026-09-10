package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.DocumentGenerator
import eu.neverblink.linkml.generator.util.JsonOutputFormat.yaml
import eu.neverblink.linkml.generator.ossie.expression.{
  Constraints,
  Expression,
  Literal,
  Ref,
  Verbalization,
}
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, JsonUtil, PruningMode}
import eu.neverblink.linkml.metamodel.Extensible
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.schemaview.*
import org.virtuslab.yaml.{Node, parseYaml}

import java.io.OutputStream
import scala.collection.mutable

/** Generator for [[https://github.com/apache/ossie Apache Ossie]] ontologies.
  *
  * Targets the ontology spec (`ontology/ontology.json`), which describes a conceptual model:
  * concepts and the relationships between them.
  */
class OssieGenerator(using sv: SchemaView)
    extends DocumentGenerator[OssieGenerator.Options],
      OssieRenamer {

  import OssieGenerator.*

  override protected def defaultOptions: Options = Options()

  /** Build the ontology document. Public because callers inspect and post-process it. */
  def generate(options: Options = Options()): OssieOntology = {
    given Options = options
    // Ancestors are included: `extends` has to resolve to a concept that is actually declared.
    val query = options.pruningMode.derivedQuery(inlinedOnly = false, includeClassAncestors = true)

    val classes = sv.sortedClasses.filter(cv => query.reachable(cv) && !cv.isAny)
    val enums = sv.enums.values.toVector
      .filter(ev => query.reachable(ev._enum))
      .sortBy(ev => sv.elementOrder(ev._enum))
    // A value type has to extend one of the built-in value types, so a named type whose base is
    // unknown cannot become one. Those collapse to `Any` at the point of use instead.
    val types = sv.types.values.toVector
      .filter(tv =>
        query.reachable(tv._type) && !tv.isPrimitive &&
          BuiltInConcept.valueTypes.contains(builtInForType(tv)),
      )
      .sortBy(tv => sv.elementOrder(tv._type))

    val conceptOf = (classes.map(cv => cv.name -> className(cv)) ++
      enums.map(ev => ev.name -> enumName(ev)) ++
      types.map(tv => tv.name -> typeName(tv))).toMap

    // Every class' relationships, before inherited ones are taken back out. Needed up front
    // because subtypes only redeclare relationships that differ from their supertypes.
    val declared: Map[String, Seq[Rel]] =
      classes.map(cv => cv.name -> relationships(cv, conceptOf(cv.name), conceptOf)).toMap

    val components =
      classes.map(cv => classConcept(cv, conceptOf, declared)) ++
        enums.map(ev => enumConcept(ev, conceptOf(ev.name))) ++
        types.map(tv => typeConcept(tv, conceptOf(tv.name)))

    if components.isEmpty then
      throw RuntimeException(
        "No concepts to put in the ontology – an Ossie ontology needs at least one. " +
          "Check the pruning mode, and whether the schema defines any classes, enums or types.",
      )

    OssieOntology(
      name = sv.root.name,
      description = sv.root.description.flatMapFast(_.inLanguage(options.metadataLanguage)),
      aiContext = aiContext(sv.root),
      ontology = components,
    )
  }

  /** The element's `ai_context` extension, if it declares one. */
  private def aiContext(obj: Extensible): Option[Node] =
    obj.extensions.values
      .find(_.extensionTag.original == "ai_context")
      .flatMap(ext => parseYaml(ext.extensionValue.toString).toOption)

  override def serialize(options: Options = Options()): String =
    JsonUtil.write(OssieOntology.codec.encode(generate(options)), options.outputFormat)

  override def writeTo(out: OutputStream, options: Options = Options()): Unit =
    JsonUtil.write(OssieOntology.codec.encode(generate(options)), options.outputFormat, out)

  /** A class, plus the relationships it declares rather than inherits. */
  private def classConcept(
      cv: ClassView,
      conceptOf: Map[String, String],
      declared: Map[String, Seq[Rel]],
  )(using options: Options): Concept = {
    val name = conceptOf(cv.name)
    val own = ownRelationships(cv, declared)
    Concept(
      concept = name,
      conceptType = ConceptType.EntityType,
      description = cv.cls.description.flatMapFast(_.inLanguage(options.metadataLanguage)),
      // Ancestors that were pruned away are simply not extended.
      extendsConcepts = cv.parents.flatMap(p => conceptOf.get(p.name)).distinct,
      identifyBy = identifyingSlots(cv)
        .flatMap(slot => declared(cv.name).find(_.slotName == slot))
        .map(_.relationship.name),
      // Only this concept's own required relationships - a supertype already states its own.
      requires =
        own.filter(_.required).map(r => Expression.Member(Ref(name), r.relationship.name).render),
      relationships = own.map(_.relationship),
    )
  }

  /** The relationships `cv` declares: everything it derives, minus what a supertype declared. */
  private def ownRelationships(cv: ClassView, declared: Map[String, Seq[Rel]]): Seq[Rel] = {
    // Direct parents are enough: their own entries were built from their derived slots, so each one
    // already covers everything it inherited in turn.
    //
    // `required` is part of the comparison: a subtype that only makes an inherited slot mandatory
    // has to keep the relationship, or the `requires` entry built from it would be lost.
    val inherited = cv.parents.iterator
      .flatMap(p => declared.getOrElse(p.name, Nil))
      .map(r => (r.relationship.shape, r.required))
      .toSet
    declared(cv.name).filterNot(r => inherited.contains((r.relationship.shape, r.required)))
  }

  /** The slots that identify `cv`, in the order Ossie's `identify_by` wants them. */
  private def identifyingSlots(cv: ClassView): Seq[String] =
    singleIdentifierSlot(cv).map(Seq(_)).getOrElse(
      cv.cls.uniqueKeys.toSeq
        .sortBy(_._1)
        .headOption
        .toSeq
        .flatMap(_._2.uniqueKeySlots.map(_.value)),
    )

  /** The slot that identifies `cv` on its own, via `identifier` or `key`.
    *
    * Only these two make a relationship one-to-one. A member of a compound `unique_keys` does not:
    * the tuple is unique, but each slot in it is still many-to-one on its own.
    */
  private def singleIdentifierSlot(cv: ClassView): Option[String] =
    cv.identifier.mapFast(_.slot.name).orElse(
      cv.sortedAttributeViews.collectFirst {
        case av if av.slotView.slot.key => av.slotView.slot.name
      },
    )

  /** One relationship per derived slot of `cv`. */
  private def relationships(
      cv: ClassView,
      conceptName: String,
      conceptOf: Map[String, String],
  )(using options: Options): Seq[Rel] = {
    val singleIdentifier = singleIdentifierSlot(cv)

    cv.sortedAttributeViews.map { av =>
      val slot = av.slotView.slot
      val name = slotName(av.slotView)

      val range = rangeOf(av, conceptOf)
      // The one case where the role player is ambiguous: a slot pointing back at its own class.
      // Ossie then requires a role name to tell the two roles apart.
      val selfReference = range == conceptName
      val role = Role(range, if selfReference then Some(name) else None)

      val multiplicity =
        if slot.multivalued then None
        else if singleIdentifier.contains(slot.name) then Some(Multiplicity.OneToOne)
        else Some(Multiplicity.ManyToOne)

      Rel(
        relationship = Relationship(
          name = name,
          // Ossie requires at least one verbalization.
          verbalizes = Seq(
            Verbalization(
              Ref(conceptName),
              // Title or space-cased slot name
              slot.title.flatMapFast(_.inLanguage(options.metadataLanguage))
                .getOrElse(Case.base(name).replace('_', ' ')),
              Ref(range),
              Option.when(selfReference)(name),
            ).render,
          ),
          description = slot.description.flatMapFast(_.inLanguage(options.metadataLanguage)),
          roles = Seq(role),
          multiplicity = multiplicity,
          requires = slotConstraints(av, role.ref),
        ),
        required = slot.required,
        slotName = slot.name,
      )
    }
  }

  /** An enum becomes a value type over strings, constrained to its permissible values. */
  private def enumConcept(ev: EnumView, name: String)(using options: Options): Concept = {
    val values = ev.derivedValues.map(v => permissibleValueName(ev, v.pv))
    Concept(
      concept = name,
      conceptType = ConceptType.ValueType,
      description = ev._enum.description.flatMapFast(_.inLanguage(options.metadataLanguage)),
      extendsConcepts = Seq(BuiltInConcept.string),
      requires =
        if values.isEmpty then Nil
        else Seq(Expression.InList(Ref(name), values.map(Literal.Text.apply)).render),
    )
  }

  /** A named type becomes a value type over the built-in for its base, keeping its constraints. */
  private def typeConcept(tv: TypeView, name: String)(using options: Options): Concept =
    Concept(
      concept = name,
      conceptType = ConceptType.ValueType,
      description = tv._type.description.flatMapFast(_.inLanguage(options.metadataLanguage)),
      extendsConcepts = Seq(builtInForType(tv)),
      requires = Constraints(
        tv._type.minimumValue,
        tv._type.maximumValue,
        tv._type.pattern,
      ).render(Ref(name)).map(_.render),
    )

  /** The value constraints the slot itself adds, as expressions over the role that corresponds to
    * this slot.
    *
    * Slot-level only, deliberately: whatever the range type declares is already stated on its value
    * type concept, and [[TypeAttributeView]] merges the two, so reading the merged view here would
    * say the same thing twice.
    */
  private def slotConstraints(av: AttributeView, ref: String): Seq[String] = av match {
    case _: TypeAttributeView | _: EnumAttributeView =>
      val slot = av.slotView.slot
      Constraints(slot.minimumValue, slot.maximumValue, slot.pattern)
        .render(Ref(ref))
        .map(_.render)
    case _ => Nil
  }

  /** The concept that plays the role in the range of a slot. */
  private def rangeOf(av: AttributeView, conceptOf: Map[String, String]): String = av match {
    case _: AnyView => BuiltInConcept.any
    case inline: ClassInlineAttributeView =>
      conceptOf.getOrElse(inline.classView.name, BuiltInConcept.any)
    case ref: ClassReferenceAttributeView =>
      conceptOf.getOrElse(ref.classView.name, BuiltInConcept.any)
    case ev: EnumAttributeView =>
      conceptOf.getOrElse(ev.enumView.name, BuiltInConcept.string)
    case tv: TypeAttributeView =>
      conceptOf.getOrElse(tv.typeView.name, builtInForType(tv.typeView))
  }

  /** The Ossie built-in behind a type, following `typeof` until something declares a `base`.
    *
    * TODO LNK-126: this should probably use type derivation?
    */
  private def builtInForType(tv: TypeView): String = {
    val seen = mutable.Set.empty[String]
    var current: Option[TypeView] = Some(tv)
    var result = BuiltInConcept.any
    while current.isDefined do {
      val view = current.get
      if !seen.add(view.name) then current = None // `typeof` cycle
      else
        view.runtimeType match {
          case _: UnknownType.type =>
            current = view._type.typeof.flatMap(parent => sv.types.get(parent.value))
          case rt =>
            result = builtInFor(rt)
            current = None
        }
    }
    result
  }
}

object OssieGenerator {

  /** A relationship, plus the bits of its slot that the containing concept needs. */
  private final case class Rel(relationship: Relationship, required: Boolean, slotName: String)

  /** Options for [[OssieGenerator]].
    *
    * @param pruningMode
    *   Which elements become concepts.
    * @param outputFormat
    *   Output serialization format to use.
    * @param metadataLanguage
    *   Which language to use for metadata fields (description) in the generated ontology.
    */
  final case class Options(
      pruningMode: PruningMode = PruningMode.skip,
      outputFormat: JsonOutputFormat = yaml,
      metadataLanguage: String = "en",
  )

  /** The Ossie built-in concept standing in for a LinkML type's base. */
  private def builtInFor(rt: RuntimeType): String = rt match {
    case _: StringType.type => BuiltInConcept.string
    case _: IntegerType.type => BuiltInConcept.integer
    case _: FloatType.type => BuiltInConcept.float
    // Ossie's Float is "the most general floating point value type" - there is no separate Double.
    case _: DoubleType.type => BuiltInConcept.float
    case _: DecimalType.type => BuiltInConcept.decimal
    case _: BooleanType.type => BuiltInConcept.boolean
    case _: DateType.type => BuiltInConcept.date
    case _: DateTimeType.type => BuiltInConcept.dateTime
    // Ossie has no Time built-in, so the ISO 8601 text is the closest thing.
    case _: TimeType.type => BuiltInConcept.string
    case _: UriType.type => BuiltInConcept.string
    case _: UriOrCurieType.type => BuiltInConcept.string
    case _: CurieType.type => BuiltInConcept.string
    case _: NcNameType.type => BuiltInConcept.string
    case _: LocalizedTextType.type => BuiltInConcept.string // TODO LNK-195
    case _: AnyType.type => BuiltInConcept.any
    case _: UnknownType.type => BuiltInConcept.any
  }
}
