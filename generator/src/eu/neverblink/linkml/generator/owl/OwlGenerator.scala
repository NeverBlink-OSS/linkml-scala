package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.RdfGeneratorBase
import eu.neverblink.linkml.generator.RdfGeneratorBase.{RdfFormat, RdfOptions}
import eu.neverblink.linkml.metamodel.{Annotation as _, *}
import eu.neverblink.linkml.rdf.{Iri, Literal, Owl, RdfSink, Rdf, Rdfs, Shacl, XmlSchema}
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.*
import org.virtuslab.yaml.Node.{ScalarNode, SequenceNode}

import scala.collection.mutable

/** Generates an OWL 2 ontology from a LinkML schema.
  *
  * Follows Python's `gen-owl` with its defaults, except where that would disagree with the other
  * RDF generators here, or lose information when [[OwlImporter]] reads the result back. See
  * docs/owl.md for details.
  */
class OwlGenerator(using sv: SchemaView) extends RdfGeneratorBase[OwlGenerator.Options] {
  import OwlGenerator.*

  override protected def defaultOptions: Options = Options()

  /** Builds the ontology without writing it as RDF. */
  def ontology(options: Options = defaultOptions): Ontology = new Build(options).ontology

  override def generate(sink: RdfSink, options: Options = defaultOptions): Unit =
    OwlRdfWriter(sink).write(ontology(options))
}

object OwlGenerator {

  enum PermissibleValueKind:
    /** Named individuals that are members of the enum's class. */
    case individual

    /** Subclasses of the enum's class, as `gen-owl` does. */
    case `class`

  /** Which annotation property holds `description`. */
  enum MetadataProfile:
    /** `skos:definition`, as in the LinkML metamodel and `gen-owl`. */
    case linkml

    /** `rdfs:comment`, which most OWL tools show. */
    case rdfs

  /** Options for [[OwlGenerator]].
    *
    * @param onlyRootSchema
    *   Describe only the root schema and add `owl:imports` for its imports. Off by default, which
    *   merges the imported schemas in, like `gen-owl --mergeimports`.
    * @param metadataProfile
    *   Property for descriptions: `rdfs:comment` (default) or `skos:definition`.
    * @param permissibleValues
    *   Whether permissible values become individuals (default) or classes.
    * @param format
    *   `ttl` for Turtle (default) or `nt` for N-Triples.
    */
  final case class Options(
      onlyRootSchema: Boolean = false,
      metadataProfile: MetadataProfile = MetadataProfile.rdfs,
      permissibleValues: PermissibleValueKind = PermissibleValueKind.individual,
      format: RdfFormat = RdfFormat.ttl,
  ) extends RdfOptions

  private val rdfsLabel = Rdfs.label.value

  /** Annotation properties built into OWL 2, which don't need to be declared. */
  private val builtInAnnotationProperties: Set[String] = Set(
    Rdfs.label.value,
    Rdfs.comment.value,
    Rdfs.seeAlso.value,
    Rdfs.isDefinedBy.value,
    Owl.deprecated.value,
    Owl.versionInfo.value,
    Owl.priorVersion.value,
    Owl.get("backwardCompatibleWith").value,
    Owl.get("incompatibleWith").value,
  )
  private val plainLiteral = Rdf.get("PlainLiteral").value

  /** Datatypes in the OWL 2 datatype map, which don't need to be declared. */
  private val owl2Datatypes: Set[String] = (Seq(
    "decimal",
    "integer",
    "nonNegativeInteger",
    "nonPositiveInteger",
    "positiveInteger",
    "negativeInteger",
    "long",
    "int",
    "short",
    "byte",
    "unsignedLong",
    "unsignedInt",
    "unsignedShort",
    "unsignedByte",
    "double",
    "float",
    "string",
    "normalizedString",
    "token",
    "language",
    "Name",
    "NCName",
    "NMTOKEN",
    "boolean",
    "hexBinary",
    "base64Binary",
    "anyURI",
    "dateTime",
    "dateTimeStamp",
  ).map(XmlSchema.prefix + _) ++ Seq(
    Owl.get("real").value,
    Owl.get("rational").value,
    plainLiteral,
    Rdf.get("XMLLiteral").value,
    Rdfs.Literal.value,
  )).toSet
  private[owl] val skosNotation = "http://www.w3.org/2004/02/skos/core#notation"

  private final class Build(options: Options)(using sv: SchemaView) {
    private val axioms = mutable.LinkedHashSet.empty[Axiom]

    private def add(axiom: Axiom): Unit = axioms += axiom

    private def included(el: ElementView[?, ?]): Boolean =
      !options.onlyRootSchema || (el.definingSchema eq sv.root)

    private val descriptionProperty = options.metadataProfile match {
      case MetadataProfile.linkml => "http://www.w3.org/2004/02/skos/core#definition"
      case MetadataProfile.rdfs => Rdfs.comment.value
    }

    def ontology: Ontology = {
      // Keep the schemas' order, so a schema read back from the ontology has the same order.
      val classes = sv.schemas.flatMap(_.classes.keys).distinct.map(sv.classes)
        .filter(c => included(c) && !c.isAny)
      classes.foreach(addClass)
      sortedSlots.foreach(addSlot)
      classes.foreach(addAttributes)
      sv.schemas.flatMap(_.enums.keys).distinct.map(sv.enums).filter(included).foreach(addEnum)
      sv.schemas.flatMap(_.types.keys).distinct.map(sv.types)
        .filter(t => included(t) && !isLinkmlOwn(t))
        .foreach(addType)

      val root = sv.root
      val imports =
        if !options.onlyRootSchema then Nil
        else
          sv.schemas.tail.map(_.id.original)
            .filterNot(_.startsWith("https://w3id.org/linkml/")).distinct
      val header = metadataAnnotations(Metadata.of(root), sv.rootPrefixResolver) ++
        linkmlAnnotations(root, sv.rootPrefixResolver) ++ preferredNamespace
      val (defaultName, defaultNamespace) = defaultPrefix
      val prefixes =
        root.prefixes.values.toSeq.map(p => p.prefixPrefix -> p.prefixReference.original)
      val withDefault =
        if prefixes.exists(_._2 == defaultNamespace) then prefixes
        else (defaultName -> defaultNamespace) +: prefixes
      declareAnnotationProperties(header, withDefault.nonEmpty)
      declareDatatypes()
      Ontology(
        iri = Some(root.id.original),
        imports = imports,
        annotations = header,
        prefixes = withDefault,
        axioms = axioms.toSeq,
      )
    }

    /** OWL 2 DL requires every annotation property to be declared, except the built-in ones. */
    private def declareAnnotationProperties(header: Seq[Annotation], prefixes: Boolean): Unit = {
      val declared = axioms.collect { case Declaration(_, iri, _) => iri }.toSet
      val used = (header.map(_.property) ++ axioms.collect { case AnnotationAssertion(_, a, _) =>
        a.property
      }) ++
        (if prefixes then Seq(Shacl.declare.value, shaclPrefix.value, Shacl.namespace.value)
         else Nil)
      // Never declare RDF, RDFS, OWL or XSD terms. An annotation like `rdf:type voaf:Vocabulary`
      // is just written back as plain RDF.
      used.distinct.filterNot(p => declared(p) || builtInAnnotationProperties(p) || isReserved(p))
        .foreach(p => add(Declaration(EntityKind.AnnotationProperty, p)))
    }

    private def isReserved(iri: String): Boolean =
      Seq(Rdf.prefix, Rdfs.prefix, Owl.prefix, XmlSchema.prefix).exists(iri.startsWith)

    /** Declare custom datatypes (outside the OWL 2 DL datatype map). */
    private def declareDatatypes(): Unit = {
      val declared = axioms.collect { case Declaration(EntityKind.Datatype, iri, _) => iri }.toSet
      val used = axioms.toSeq.flatMap {
        case Range(_, r, _) => OwlAxiomIndex.datatypesIn(r)
        case DatatypeDefinition(_, r, _) => OwlAxiomIndex.datatypesIn(r)
        case SubClassOf(a, b, _) => OwlAxiomIndex.datatypesIn(a) ++ OwlAxiomIndex.datatypesIn(b)
        case EquivalentClasses(ops, _) => ops.flatMap(OwlAxiomIndex.datatypesIn)
        case _ => Nil
      }
      used.distinct.filterNot(d => declared(d) || owl2Datatypes(d))
        .foreach(d => add(Declaration(EntityKind.Datatype, d)))
    }

    /** The default prefix as VANN annotations, which is how vocabularies usually state it. */
    private def preferredNamespace: Seq[Annotation] = {
      val vann = "http://purl.org/vocab/vann/"
      val (prefix, namespace) = defaultPrefix
      Seq(
        Annotation.text(vann + "preferredNamespacePrefix", prefix),
        Annotation(vann + "preferredNamespaceUri", Literal(namespace, XmlSchema.anyURI)),
      )
    }

    /** The default prefix and its namespace. Without a default prefix, the schema name is used. */
    private def defaultPrefix: (String, String) = {
      val namespace = sv.getDefaultPrefix(sv.root)
      val prefix = sv.root.defaultPrefix
        .orElse(sv.root.prefixes.values.collectFirst {
          case p if p.prefixReference.original == namespace => p.prefixPrefix
        })
        .getOrElse(sv.root.name)
      prefix -> namespace
    }

    // Metadata

    /** Annotates `iri` with the element's name and metadata. Exact mappings already written as
      * equivalence axioms are skipped.
      */
    private def annotate(
        iri: String,
        el: ElementView[?, ?],
        equivalent: Set[String] = Set.empty,
    ): Unit =
      annotate(iri, el.inner, el.definingPrefixResolver, equivalent)

    private def annotate(
        iri: String,
        metadata: CommonMetadata & Annotatable,
        resolver: PrefixResolver,
        equivalent: Set[String],
    ): Unit = {
      val m = Metadata.of(metadata)
      val kept =
        m.copy(exactMappings = m.exactMappings.filterNot(e => equivalent(e.uri(using resolver))))
      (metadataAnnotations(kept, resolver) ++ linkmlAnnotations(metadata, resolver))
        .foreach(a => add(AnnotationAssertion(iri, a)))
    }

    private def annotate(
        iri: String,
        metadata: CommonMetadata & Annotatable,
        resolver: PrefixResolver,
    ): Unit = annotate(iri, metadata, resolver, Set.empty)

    private lazy val classIris: Set[String] = sv.classes.values.filter(included).map(_.uriStr).toSet
    private lazy val slotIris: Set[String] =
      sv.slotDefinitions.values.filter(included).map(_.uriStr).toSet

    /** Exact mappings to another element of the same kind in this ontology. These become OWL
      * equivalences. The rest stay `skos:exactMatch`.
      */
    private def equivalentsOf(el: ElementView[?, ?], among: Set[String]): Set[String] =
      el.inner.exactMappings.map(_.uri(using el.definingPrefixResolver)).filter(
        among,
      ).toSet - el.uriStr

    /** `rdfs:label` is the title in each of its languages. OWL tools show `rdfs:label`, so the
      * title goes there and not to `dcterms:title`.
      */
    private def metadataAnnotations(
        metadata: Metadata,
        resolver: PrefixResolver,
    ): Seq[Annotation] = {
      // Use the element's language, or the schema's, as `gen-owl` does with `in_language`.
      val language = metadata.inLanguage.orElse(sv.root.inLanguage)
      val labels = metadata.title match {
        case Some(PlainText(t)) => Seq(Annotation.text(rdfsLabel, t, language))
        case Some(MultilingualText(m)) =>
          m.toSeq.sortBy(_._1).map((lang, t) => Annotation.text(rdfsLabel, t, Some(lang)))
        case None => Nil
      }
      labels ++ Metadata.annotations(
        metadata.copy(title = None),
        _.uri(using resolver),
        descriptionProperty,
        language,
      )
    }

    /** LinkML `annotations` with plain values, with the tag as the property. A tag that is not a
      * CURIE goes in the default namespace, as in `gen-owl`.
      */
    private def linkmlAnnotations(el: Annotatable, resolver: PrefixResolver): Seq[Annotation] =
      el.annotations.values.toSeq.flatMap { a =>
        val tag = a.extensionTag.original
        val property =
          if tag.contains(':') then scala.util.Try(a.extensionTag.uri(using resolver)).toOption
          else Some(sv.getDefaultPrefix(sv.root) + tag)
        val values = a.extensionValue.yaml.toOption.toSeq.flatMap {
          case ScalarNode(v, _) => Seq(v)
          case SequenceNode(items, _) => items.collect { case ScalarNode(v, _) => v }
          case _ => Nil
        }
        val language = sv.root.inLanguage
        for p <- property.toSeq; v <- values
        yield Annotation(p, Metadata.inLanguage(annotationValue(v, resolver), language))
      }

    /** Reads a value as an IRI if it is a full IRI or a CURIE with a declared prefix, since the
      * importer writes IRIs that way. Anything else is text.
      */
    private def annotationValue(v: String, resolver: PrefixResolver): Value =
      if v.contains("://") || v.startsWith("urn:") || v.startsWith("mailto:") then
        if v.exists(_.isWhitespace) then Literal(v) else Iri(v)
      else {
        val colon = v.indexOf(':')
        val prefix = if colon > 0 then v.substring(0, colon) else ""
        if prefix.nonEmpty && prefix.forall(c => Case.isStandard(c) || c == '-') &&
          !v.exists(_.isWhitespace) && resolver.resolvePrefix(prefix).isDefined
        then Iri(resolver.expand(v))
        else Literal(v)
      }

    // Classes

    private def addClass(cv: ClassView): Unit = {
      val iri = cv.uriStr
      val self = ClassExpr.Named(iri)
      // Don't declare classes that are RDF or OWL terms, such as `rdf:Property`.
      if !isReserved(iri) then add(Declaration(EntityKind.Class, iri))
      val equivalent = equivalentsOf(cv, classIris)
      annotate(iri, cv, equivalent)
      equivalent.toSeq.sorted.foreach(e => add(EquivalentClasses(Seq(self, ClassExpr.Named(e)))))

      (cv.cls.isA.toSeq ++ cv.cls.mixins).flatMap(r => sv.classes.get(r.value))
        .filterNot(_.isAny)
        .foreach(parent => add(SubClassOf(self, ClassExpr.Named(parent.uriStr))))

      ownSlots(cv).foreach { name =>
        restrictions(cv, cv.derivedAttributes(name)).foreach(r => add(SubClassOf(self, r)))
      }

      cv.cls.uniqueKeys.values.foreach { key =>
        val properties = key.uniqueKeySlots.flatMap(s => cv.derivedAttributes.get(s.value))
          .filterNot(_.slot.identifier).map(_.uriStr)
        if properties.nonEmpty then add(HasKey(self, properties))
      }

      val children = sv.schemas.flatMap(_.classes.keys).distinct.map(sv.classes).filter(c =>
        included(c) && c.cls.isA.exists(_.value == cv.name),
      )
      // Every instance of an abstract class is an instance of one of its children. With only one
      // child, that would make the two classes equivalent, so we skip it.
      if cv.cls.`abstract` && children.sizeIs > 1 then
        add(SubClassOf(self, ClassExpr.UnionOf(children.map(c => ClassExpr.Named(c.uriStr)))))
      if cv.cls.childrenAreMutuallyDisjoint && children.sizeIs > 1 then
        add(DisjointClasses(children.map(c => ClassExpr.Named(c.uriStr))))
      cv.cls.disjointWith.flatMap(r => sv.classes.get(r.value)).foreach { other =>
        add(DisjointClasses(Seq(self, ClassExpr.Named(other.uriStr))))
      }
    }

    /** Slots the class declares or refines itself, not ones it just inherits. */
    private def ownSlots(cv: ClassView): Seq[String] =
      (cv.cls.slots.map(_.value) ++ cv.cls.attributes.keys ++ cv.cls.slotUsage.keys).distinct
        .filter(cv.derivedAttributes.contains)

    private def restrictions(cv: ClassView, s: SlotView): Seq[ClassExpr] =
      if s.slot.identifier then Nil
      else {
        val name = s.slot.name
        // The class's own slot_usage, then inherited attributes, then the slot. An ancestor's
        // slot_usage is skipped, because OWL passes it down through the subclass axiom anyway.
        val sources: Seq[SlotDefinition] =
          cv.cls.slotUsage.get(name).toSeq ++
            cv.ancestorsWithSelf.flatMap(_.cls.attributes.get(name)) ++
            sv.slotDefinitions.get(name).toSeq.flatMap(_.ancestorsWithSelf).map(_.slot)
        def first[A](f: SlotDefinition => Option[A]): Option[A] =
          sources.iterator.flatMap(f).nextOption()
        def any(f: SlotDefinition => Boolean): Boolean = sources.exists(f)

        // Annotation properties only get the restrictions the schema states explicitly.
        val annotation = kindOf(s) == EntityKind.AnnotationProperty
        val p = PropertyRef(s.uriStr)
        val range =
          if annotation then
            cv.cls.slotUsage.get(name).flatMap(_.range).flatMap(r => sv.getElement(r.value))
              .flatMap(rangeOfElement(_, s.slot))
          else rangeOf(s, Some(cv))
        // With `slot_usage` on an inherited slot, state the range only if it is narrowed. For a
        // slot from an imported schema, the importer can't tell a repeated range from a new one.
        val declares = cv.cls.slots.exists(_.value == name) || cv.cls.attributes.contains(name)
        val narrows = cv.cls.slotUsage.get(name).exists(u => u.range.isDefined || u.anyOf.nonEmpty)
        val all = range.filter(_ => declares || narrows || annotation)
          .map(ClassExpr.AllValuesFrom(p, _))
        val exact = first(_.exactCardinality)
        val min = exact.orElse(first(_.minimumCardinality))
          .orElse(Option.when(any(d => d.required || d.key))(1))
        val max = exact.orElse(first(_.maximumCardinality))
          .orElse(Option.when(!annotation && !any(_.multivalued))(1))
        val cardinalities = (min, max) match {
          // OWL 2 DL allows no cardinality here, so a required value becomes `someValuesFrom`.
          case _ if nonSimple(s.uriStr) =>
            min.filter(_ > 0).map(_ => ClassExpr.SomeValuesFrom(p, ClassExpr.Thing)).toSeq
          case (Some(a), Some(b)) if a == b =>
            Seq(ClassExpr.Cardinality(ClassExpr.Bound.Exact, a, p))
          case _ =>
            min.filter(_ > 0).map(ClassExpr.Cardinality(ClassExpr.Bound.Min, _, p)).toSeq ++
              max.map(ClassExpr.Cardinality(ClassExpr.Bound.Max, _, p))
        }
        val equals = first(_.equalsString).flatMap(valueOf(s, _)).orElse(
          first(_.equalsNumber).map(n => Literal(n.toString, XmlSchema.integer)),
        ).map(ClassExpr.HasValue(p, _))
        // `has_member`, plus any under `all_of`. The importer writes a class that needs values of
        // several kinds that way.
        val members = (first(_.hasMember).toSeq ++
          cv.cls.slotUsage.get(name).toSeq.flatMap(_.allOf).flatMap(_.hasMember))
          .flatMap(m => expressionRange(s, m)).map(ClassExpr.SomeValuesFrom(p, _))
        val stated = all.toSeq ++ cardinalities ++ equals ++ members
        // A class that uses a slot without constraining it still records the use, with a
        // restriction that any value meets.
        if stated.nonEmpty || annotation || !declares then stated
        else {
          val anything = kindOf(s) match {
            case EntityKind.DataProperty => DataRange.Literal
            case _ => ClassExpr.Thing
          }
          Seq(ClassExpr.AllValuesFrom(p, anything))
        }
      }

    /** The value that `equals_string` means for the slot's range. */
    private def valueOf(s: SlotView, text: String): Option[Value] =
      s.derivedRange.resolve match {
        case Some(ev: EnumView) =>
          ev.toMeaning.get(text).map(m => Iri(m.uri(using ev.definingPrefixResolver)))
        case Some(tv: TypeView) if tv.isIri =>
          Some(Iri(UriOrCurie(text).uri(using s.definingPrefixResolver)))
        case Some(tv: TypeView) => Some(Literal(text, Iri(baseDatatype(tv))))
        case Some(_: ClassView) => Some(Iri(UriOrCurie(text).uri(using s.definingPrefixResolver)))
        case _ => Some(Literal(text))
      }

    // Ranges

    private def rangeOf(s: SlotView, context: Option[ClassView] = None): Option[Filler] = {
      // The nearest definition with a range wins: the class's slot_usage or attribute, an
      // ancestor's attribute, then the slot and its parents. An ancestor's slot_usage is skipped,
      // because OWL passes it down anyway. The derived slot is not used, since it joins the
      // `any_of` of all of these together.
      val inClasses = context.toSeq.flatMap(c =>
        c.cls.slotUsage.get(s.slot.name).toSeq ++
          c.ancestorsWithSelf.flatMap(_.cls.attributes.get(s.slot.name)),
      )
      val inSlots =
        sv.slotDefinitions.get(s.slot.name).toSeq.flatMap(_.ancestorsWithSelf).map(_.slot)
      val nearest = (inClasses ++ inSlots).find(e => e.range.isDefined || e.anyOf.nonEmpty)
      nearest match {
        case Some(e)
            if e.anyOf.nonEmpty && e.range.forall(r => sv.classes.get(r.value).exists(_.isAny)) =>
          val alternatives = e.anyOf.distinct.flatMap(a => expressionRange(s, a))
          if alternatives.sizeIs != e.anyOf.distinct.size then None else union(alternatives)
        case Some(e) =>
          e.range.flatMap(r => sv.getElement(r.value)).flatMap(rangeOfElement(_, s.slot))
        case None => sv.getDefaultRange(s.definingSchema).resolve.flatMap(rangeOfElement(_, s.slot))
      }
    }

    /** The range of one `any_of` or `has_member` entry. */
    private def expressionRange(s: SlotView, e: AnonymousSlotExpression): Option[Filler] =
      e.range.flatMap(r => sv.getElement(r.value)).orElse(s.derivedRange.resolve)
        .flatMap(rangeOfElement(_, e))

    private def union(alternatives: Seq[Filler]): Option[Filler] =
      if alternatives.forall(_.isInstanceOf[DataRange]) then
        Some(DataRange.UnionOf(alternatives.map(_.asInstanceOf[DataRange])))
      else if alternatives.forall(_.isInstanceOf[ClassExpr]) then
        Some(ClassExpr.UnionOf(alternatives.map(_.asInstanceOf[ClassExpr])))
      else None

    private def rangeOfElement(el: ElementView[?, ?], constraints: SlotExpression): Option[Filler] =
      el match {
        case cv: ClassView => Option.unless(cv.isAny)(ClassExpr.Named(cv.uriStr))
        case ev: EnumView => Some(ClassExpr.Named(ev.uriStr))
        // Values with an implicit prefix are IRIs in RDF, as in the SHACL generator.
        case tv: TypeView if tv.isIri || constraints.implicitPrefix.isDefined => None
        case tv: TypeView =>
          val own =
            facets(
              constraints.minimumValue,
              constraints.maximumValue,
              LinkmlPattern.option(constraints.pattern),
              tv,
            )
          if own.isEmpty then Some(datatypeOf(tv))
          else {
            // OWL only allows facets on built-in datatypes, so repeat the type's own constraints
            // next to the slot's.
            val d = tv.derivedType
            val inherited = facets(d.minimumValue, d.maximumValue, tv.pattern, tv)
              .filterNot(f => own.exists(_._1 == f._1))
            Some(DataRange.Restriction(baseDatatype(tv), own ++ inherited))
          }
        case _ => None
      }

    private def datatypeOf(tv: TypeView): DataRange =
      if tv.isPrimitive then
        tv.name match {
          case "langstring" => DataRange.Datatype(plainLiteral)
          case "date_or_datetime" =>
            DataRange.UnionOf(
              Seq(
                DataRange.Datatype(XmlSchema.date.value),
                DataRange.Datatype(XmlSchema.dateTime.value),
              ),
            )
          case _ => DataRange.Datatype(tv.uriStr)
        }
      else
        // A type without its own `uri` stands for the ancestor it inherits the `uri` from.
        tv.ancestorsWithSelf.find(_._type.typeUri.isDefined) match {
          case Some(origin) if origin.isPrimitive && !hasFacets(tv) => datatypeOf(origin)
          case _ if hasFacets(tv) && OwlRdfReader.isBuiltInDatatype(tv.uriStr) =>
            val d = tv.derivedType
            DataRange.Restriction(tv.uriStr, facets(d.minimumValue, d.maximumValue, tv.pattern, tv))
          case _ => DataRange.Datatype(tv.uriStr)
        }

    /** Whether the type comes from LinkML's own schemas, such as `linkml:types`. */
    private def isLinkmlOwn(tv: TypeView): Boolean =
      tv.definingSchema.id.original.startsWith("https://w3id.org/linkml/")

    private def hasFacets(tv: TypeView): Boolean = {
      val d = tv.derivedType
      d.minimumValue.isDefined || d.maximumValue.isDefined || d.pattern.isDefined
    }

    /** The XSD datatype that a type's values have in RDF. */
    private def baseDatatype(tv: TypeView): String = {
      val uri = tv.uriStr
      if OwlRdfReader.isBuiltInDatatype(uri) then uri
      else
        tv.runtimeType match {
          case IntegerType => XmlSchema.integer.value
          case FloatType => XmlSchema.float.value
          case DoubleType => XmlSchema.double.value
          case DecimalType => XmlSchema.decimal.value
          case BooleanType => XmlSchema.boolean.value
          case DateType => XmlSchema.date.value
          case DateTimeType => XmlSchema.dateTime.value
          case TimeType => XmlSchema.time.value
          case UriType | UriOrCurieType => XmlSchema.anyURI.value
          case _ => XmlSchema.string.value
        }
    }

    private def facets(
        min: Option[LinkmlAny],
        max: Option[LinkmlAny],
        pattern: Option[LinkmlPattern],
        tv: TypeView,
    ): Seq[(String, Literal)] = {
      val datatype = Iri(baseDatatype(tv))
      min.map(v => XmlSchema.minInclusive.value -> Literal(v.value.strip, datatype)).toSeq ++
        max.map(v => XmlSchema.maxInclusive.value -> Literal(v.value.strip, datatype)) ++
        pattern.map(p => XmlSchema.pattern.value -> Literal(p.xsd))
    }

    // Slots

    private def sortedSlots: Seq[SlotView] =
      sv.schemas.flatMap(_.slotDefinitions.keys).distinct.map(sv.slotDefinitions)
        .filter(s => included(s) && !s.slot.identifier)

    /** Properties OWL 2 DL calls non-simple: transitive ones, their parents, and inverses of these.
      * OWL 2 DL allows no cardinality or functional axioms on them.
      */
    private lazy val nonSimple: Set[String] = {
      val slots = sv.slotDefinitions.values.toSeq
      def inverses(s: SlotView): Seq[SlotView] =
        s.slot.inverse.flatMap(i => sv.slotDefinitions.get(i.value)).toSeq ++
          slots.filter(_.slot.inverse.exists(_.value == s.slot.name))
      var found = slots.filter(_.slot.transitive).flatMap(_.ancestorsWithSelf).toSet
      var more = found.flatMap(inverses).flatMap(_.ancestorsWithSelf) -- found
      while more.nonEmpty do {
        found ++= more
        more = more.flatMap(inverses).flatMap(_.ancestorsWithSelf) -- found
      }
      found.map(_.uriStr)
    }

    /** Whether each property is single-valued in every class that uses it. */
    private lazy val singleValued: Map[String, Boolean] =
      sv.classes.values.toSeq.flatMap(_.derivedAttributes.values)
        .groupMapReduce(_.uriStr)(!_.slot.multivalued)(_ && _)

    private def kindOf(s: SlotView): EntityKind = {
      val implements = s.slot.implements.map(_.uri(using s.definingPrefixResolver)).toSet
      val owl = "http://www.w3.org/2002/07/owl#"
      if implements.contains(owl + "AnnotationProperty") then EntityKind.AnnotationProperty
      else if implements.contains(owl + "DatatypeProperty") then EntityKind.DataProperty
      else if implements.contains(owl + "ObjectProperty") then EntityKind.ObjectProperty
      else {
        // Look at every use of the property, since a slot may only get its range in a class.
        // An attribute has no range outside its classes.
        val own = if sv.slotDefinitions.contains(s.slot.name) then rangeOf(s) else None
        val ranges = (rangesByProperty.getOrElse(s.uriStr, Nil) :+ own).flatten
        if ranges.nonEmpty then
          if ranges.forall(_.isInstanceOf[DataRange]) then EntityKind.DataProperty
          else EntityKind.ObjectProperty
        else
          // Nothing to go on, so use the parent's kind.
          s.parents.headOption.map(kindOf).getOrElse(EntityKind.ObjectProperty)
      }
    }

    private lazy val rangesByProperty: Map[String, Seq[Option[Filler]]] =
      sv.classes.values.toSeq
        .flatMap(c => c.derivedAttributes.values.map(a => a.uriStr -> rangeOf(a, Some(c))))
        .groupMap(_._1)(_._2)

    private def addSlot(slotView: SlotView): Unit = {
      val slot = slotView.slot
      val iri = slotView.uriStr
      val kind = kindOf(slotView)
      add(Declaration(kind, iri))
      val equivalent = equivalentsOf(slotView, slotIris)
      annotate(iri, slotView, equivalent)
      equivalent.toSeq.sorted.foreach(e => add(EquivalentProperties(Seq(iri, e))))
      val annotation = kind == EntityKind.AnnotationProperty
      val parents = slot.isA.toSeq ++ slot.mixins
      // A slot without its own range gets its parent's through `rdfs:subPropertyOf`. An annotation
      // property only gets a range it states, since its values aren't the schema's data.
      if annotation then
        slot.range.flatMap(r => sv.getElement(r.value)).flatMap(rangeOfElement(_, slot))
          .foreach(r => add(Range(iri, r)))
      else if slot.range.isDefined || slot.anyOf.nonEmpty || parents.isEmpty then
        rangeOf(slotView).foreach(r => add(Range(iri, r)))
      slot.domain.flatMap(d => sv.classes.get(d.value)).foreach(d =>
        add(Domain(iri, ClassExpr.Named(d.uriStr))),
      )
      // `domain` holds one class, so a union of classes as the domain is kept in `domain_of`.
      slot.domainOf.flatMap(d => sv.classes.get(d.value)).map(d =>
        ClassExpr.Named(d.uriStr),
      ) match {
        case Seq() =>
        case Seq(only) => add(Domain(iri, only))
        case many => add(Domain(iri, ClassExpr.UnionOf(many)))
      }
      parents.flatMap(p => sv.slotDefinitions.get(p.value))
        .foreach(p => add(SubPropertyOf(iri, p.uriStr)))
      if !annotation then {
        slot.inverse.flatMap(i => sv.slotDefinitions.get(i.value)).foreach { inverse =>
          val Seq(a, b) = Seq(iri, inverse.uriStr).sorted
          add(InverseProperties(a, b))
        }
        characteristics(slot).foreach(c => add(Characteristic(iri, c)))
        if !slot.multivalued && singleValued.getOrElse(iri, true) && !nonSimple(iri) then
          add(Characteristic(iri, PropertyCharacteristic.Functional))
      }
    }

    private def characteristics(slot: SlotDefinition): Seq[PropertyCharacteristic] = Seq(
      Option.when(slot.transitive)(PropertyCharacteristic.Transitive),
      Option.when(slot.symmetric)(PropertyCharacteristic.Symmetric),
      Option.when(slot.asymmetric)(PropertyCharacteristic.Asymmetric),
      Option.when(slot.reflexive)(PropertyCharacteristic.Reflexive),
      Option.when(slot.irreflexive)(PropertyCharacteristic.Irreflexive),
    ).flatten

    /** Declares properties for attributes, which have no top-level slot. Their range and
      * cardinality are stated on their class.
      */
    private def addAttributes(cv: ClassView): Unit =
      cv.cls.attributes.foreach { (name, attribute) =>
        val derived = cv.derivedAttributes(name)
        val iri = derived.uriStr
        if !derived.slot.identifier && !axioms.exists {
            case Declaration(_, `iri`, _) => true
            case _ => false
          }
        then {
          val kind = kindOf(derived)
          add(Declaration(kind, iri))
          annotate(iri, attribute, cv.definingPrefixResolver)
          if kind != EntityKind.AnnotationProperty then {
            // If every use of the property has the same range, that becomes its range.
            val uses = sv.classes.values.toSeq
              .flatMap(c =>
                c.derivedAttributes.values.filter(_.uriStr == iri).map(rangeOf(_, Some(c))),
              )
              .distinct
            uses match {
              case Seq(Some(range)) => add(Range(iri, range))
              case _ =>
            }
            if singleValued.getOrElse(iri, false) && !nonSimple(iri) then
              add(Characteristic(iri, PropertyCharacteristic.Functional))
          }
        }
      }

    // Enums

    private def addEnum(ev: EnumView): Unit = {
      val iri = ev.uriStr
      add(Declaration(EntityKind.Class, iri))
      annotate(iri, ev)
      ev.parents.map(_.uriStr).toSeq.distinct
        .foreach(parent => add(SubClassOf(ClassExpr.Named(iri), ClassExpr.Named(parent))))
      val values = ev.derivedValues.map { pvv =>
        val pv = pvv.pv
        val pvIri = pvv.meaning.uri(using ev.definingPrefixResolver)
        options.permissibleValues match {
          case PermissibleValueKind.individual =>
            add(Declaration(EntityKind.NamedIndividual, pvIri))
            add(ClassAssertion(ClassExpr.Named(iri), pvIri))
          case PermissibleValueKind.`class` =>
            add(Declaration(EntityKind.Class, pvIri))
            add(SubClassOf(ClassExpr.Named(pvIri), ClassExpr.Named(iri)))
        }
        // The text goes in `skos:notation`. The importer falls back to the local name, so the
        // notation is only written when they differ. This keeps it off IRIs that are also
        // classes, which Schema.org has.
        if OwlImportNames.localName(pvIri) != pv.text then
          add(AnnotationAssertion(pvIri, Annotation.text(skosNotation, pv.text)))
        annotate(pvIri, pv, ev.definingPrefixResolver)
        pvIri
      }
      if values.nonEmpty then
        options.permissibleValues match {
          case PermissibleValueKind.individual =>
            add(EquivalentClasses(Seq(ClassExpr.Named(iri), ClassExpr.OneOf(values))))
          case PermissibleValueKind.`class` =>
            val union = ClassExpr.UnionOf(values.map(ClassExpr.Named(_)))
            add(EquivalentClasses(Seq(ClassExpr.Named(iri), union)))
        }
    }

    // Types

    /** Declares a type whose `uri` OWL doesn't know, defined by its constraints if it has any. A
      * type over a built-in datatype needs no declaration.
      */
    private def addType(tv: TypeView): Unit =
      datatypeOf(tv) match {
        case DataRange.Datatype(iri) if !OwlRdfReader.isBuiltInDatatype(iri) =>
          add(Declaration(EntityKind.Datatype, iri))
          annotate(iri, tv)
          if hasFacets(tv) then {
            val d = tv.derivedType
            val restriction = DataRange.Restriction(
              baseDatatype(tv),
              facets(d.minimumValue, d.maximumValue, tv.pattern, tv),
            )
            add(DatatypeDefinition(iri, restriction))
          }
        case DataRange.Datatype(iri)
            if !Metadata.of(tv._type).isEmpty &&
              sv.types.values.count(t => !isLinkmlOwn(t) && t.uriStr == iri) == 1 =>
          // No declaration needed, but keep what the schema says about the datatype.
          annotate(iri, tv)
        case _ =>
      }
  }
}
