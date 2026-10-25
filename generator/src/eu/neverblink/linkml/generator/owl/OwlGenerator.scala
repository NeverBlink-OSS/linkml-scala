package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.RdfGeneratorBase
import eu.neverblink.linkml.generator.RdfGeneratorBase.{RdfFormat, RdfOptions}
import eu.neverblink.linkml.metamodel.{Annotation as _, *}
import eu.neverblink.linkml.rdf.{Iri, Literal, Owl, RdfSink, Rdf, Rdfs, Shacl, XmlSchema}
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.runtime.FastUtils.*
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
  private val owl2Datatypes: Set[String] = Seq(
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
  ).map(XmlSchema.prefix.concat).concat(
    Seq(
      Owl.get("real").value,
      Owl.get("rational").value,
      plainLiteral,
      Rdf.get("XMLLiteral").value,
      Rdfs.Literal.value,
    ),
  ).toSet
  private[owl] val skosNotation = "http://www.w3.org/2004/02/skos/core#notation"

  private final class Build(options: Options)(using sv: SchemaView) {
    private val axioms = mutable.LinkedHashSet.empty[Axiom]
    // The IRIs of the declarations in `axioms`, to check for one without scanning all axioms.
    private val declared = mutable.HashSet.empty[String]

    private def add(axiom: Axiom): Unit = {
      axioms.addOne(axiom)
      axiom match {
        case d: Declaration => declared.addOne(d.iri)
        case _ =>
      }
    }

    private def included(el: ElementView[?, ?]): Boolean =
      !options.onlyRootSchema || (el.definingSchema eq sv.root)

    private val descriptionProperty = options.metadataProfile match {
      case _: MetadataProfile.linkml.type => "http://www.w3.org/2004/02/skos/core#definition"
      case _: MetadataProfile.rdfs.type => Rdfs.comment.value
    }

    def ontology: Ontology = {
      // Keep the schemas' order, so a schema read back from the ontology has the same order. A
      // name that several schemas define is visited once for each of them, which adds nothing
      // new, since `axioms` is a set.
      includedClasses.foreach(c => if (!c.isAny) addClass(c))
      // Only slots and attributes declare properties, so a `slot_uri` that only a `slot_usage`
      // sets is never declared (LNK-236).
      sv.schemas.foreach(_.slotDefinitions.keys.foreach { name =>
        val s = sv.slotDefinitions(name)
        if (included(s) && !s.slot.identifier) addSlot(s)
      })
      includedClasses.foreach(c => if (!c.isAny) addAttributes(c))
      sv.schemas.foreach(_.enums.keys.foreach { name =>
        val e = sv.enums(name)
        if (included(e)) addEnum(e)
      })
      sv.schemas.foreach(_.types.keys.foreach { name =>
        val t = sv.types(name)
        if (included(t) && !isLinkmlOwn(t)) addType(t)
      })
      val root = sv.root
      val imports =
        if (options.onlyRootSchema) {
          val ids = mutable.LinkedHashSet.empty[String]
          sv.schemas.tail.foreach { s =>
            val id = s.id.original
            if (!id.startsWith("https://w3id.org/linkml/")) ids.addOne(id)
          }
          ids.toSeq
        } else Nil
      val header = metadataAnnotations(Metadata.of(root))
        .concat(linkmlAnnotations(root, sv.rootPrefixes)).concat(preferredNamespace)
      val prefixes = Seq.newBuilder[(String, String)]
      if (!root.prefixes.values.exists(_.prefixReference.original == defaultPrefix.namespace)) {
        prefixes.addOne(defaultPrefix.prefix -> defaultPrefix.namespace)
      }
      root.prefixes.values.foreach(p =>
        prefixes.addOne(p.prefixPrefix -> p.prefixReference.original),
      )
      declareAnnotationProperties(header)
      declareDatatypes()
      Ontology(
        iri = Some(root.id.original),
        imports = imports,
        annotations = header,
        prefixes = prefixes.result(),
        axioms = axioms.toSeq,
      )
    }

    /** OWL 2 DL requires every annotation property to be declared, except the built-in ones. */
    private def declareAnnotationProperties(header: Seq[Annotation]): Unit = {
      val used = mutable.LinkedHashSet.empty[String]
      header.foreach(a => used.addOne(a.property))
      axioms.foreach {
        case a: AnnotationAssertion => used.addOne(a.annotation.property)
        case _ =>
      }
      // The ontology always has prefixes, which are written with these SHACL terms.
      used.addOne(Shacl.declare.value).addOne(shaclPrefix.value).addOne(Shacl.namespace.value)
      // Never declare RDF, RDFS, OWL or XSD terms. An annotation like `rdf:type voaf:Vocabulary`
      // is just written back as plain RDF.
      used.foreach { p =>
        if (!declared(p) && !builtInAnnotationProperties(p) && !isReserved(p)) {
          add(Declaration(EntityKind.AnnotationProperty, p))
        }
      }
    }

    private def isReserved(iri: String): Boolean =
      iri.startsWith(Rdf.prefix) || iri.startsWith(Rdfs.prefix) || iri.startsWith(Owl.prefix) ||
        iri.startsWith(XmlSchema.prefix)

    /** Declare custom datatypes (outside the OWL 2 DL datatype map). */
    private def declareDatatypes(): Unit = {
      val declaredDatatypes = mutable.HashSet.empty[String]
      val used = mutable.LinkedHashSet.empty[String]
      def use(r: Filler): Unit = used.addAll(OwlAxiomIndex.datatypesIn(r))
      axioms.foreach {
        case d: Declaration => if (d.kind == EntityKind.Datatype) declaredDatatypes.addOne(d.iri)
        case r: Range => use(r.range)
        case d: DatatypeDefinition => use(d.range)
        case s: SubClassOf =>
          use(s.sub)
          use(s.sup)
        case e: EquivalentClasses => e.operands.foreach(use)
        case _ =>
      }
      used.foreach { d =>
        if (!declaredDatatypes(d) && !owl2Datatypes(d)) add(Declaration(EntityKind.Datatype, d))
      }
    }

    /** The default prefix as VANN annotations, which is how vocabularies usually state it. */
    private def preferredNamespace: Seq[Annotation] =
      Seq(
        Annotation.text(
          "http://purl.org/vocab/vann/preferredNamespacePrefix",
          defaultPrefix.prefix,
        ),
        Annotation(
          "http://purl.org/vocab/vann/preferredNamespaceUri",
          Literal(defaultPrefix.namespace, XmlSchema.anyURI),
        ),
      )

    /** The default prefix and its namespace. Without a default prefix, the schema name is used. */
    private lazy val defaultPrefix: (prefix: String, namespace: String) = {
      val namespace = sv.getDefaultPrefix(sv.root)
      val prefix = sv.root.defaultPrefix
        .orElseFast(sv.root.prefixes.values.collectFirst {
          case p if p.prefixReference.original == namespace => p.prefixPrefix
        })
        .getOrElseFast(sv.root.name)
      (prefix = prefix, namespace = namespace)
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
      annotate(iri, el.inner, el.definingPrefixes, equivalent)

    /** @param prefixes
      *   The prefixes of the schema that defines `metadata`, to expand CURIEs in annotation values
      */
    private def annotate(
        iri: String,
        metadata: CommonMetadata & Annotatable,
        prefixes: PrefixMap,
        equivalent: Set[String],
    ): Unit = {
      val m = Metadata.of(metadata)
      val kept =
        if (equivalent.isEmpty) m
        else m.copy(exactMappings = m.exactMappings.filterNot(e => equivalent(e.original)))
      metadataAnnotations(kept).foreach(a => add(AnnotationAssertion(iri, a)))
      linkmlAnnotations(metadata, prefixes).foreach(a => add(AnnotationAssertion(iri, a)))
    }

    private def annotate(
        iri: String,
        metadata: CommonMetadata & Annotatable,
        prefixes: PrefixMap,
    ): Unit = annotate(iri, metadata, prefixes, Set.empty)

    private lazy val classIris: Set[String] = {
      val b = Set.newBuilder[String]
      sv.classes.foreachEntry((_, c) => if (included(c)) b.addOne(c.uriStr))
      b.result()
    }

    private lazy val slotIris: Set[String] = {
      val b = Set.newBuilder[String]
      sv.slotDefinitions.foreachEntry((_, s) => if (included(s)) b.addOne(s.uriStr))
      b.result()
    }

    /** Exact mappings to another element of the same kind in this ontology. These become OWL
      * equivalences. The rest stay `skos:exactMatch`.
      */
    private def equivalentsOf(el: ElementView[?, ?], among: Set[String]): Set[String] = {
      val b = Set.newBuilder[String]
      el.inner.exactMappings.foreach { m =>
        val uri = m.original
        if (among(uri) && uri != el.uriStr) b.addOne(uri)
      }
      b.result()
    }

    /** `rdfs:label` is the title in each of its languages. OWL tools show `rdfs:label`, so the
      * title goes there and not to `dcterms:title`.
      */
    private def metadataAnnotations(metadata: Metadata): Seq[Annotation] = {
      // Use the element's language, or the schema's, as `gen-owl` does with `in_language`.
      val language = metadata.inLanguage.orElseFast(sv.root.inLanguage)
      val b = Seq.newBuilder[Annotation]
      metadata.title.foreachFast {
        case t: PlainText => b.addOne(Annotation.text(rdfsLabel, t.value, language))
        case t: MultilingualText =>
          val languages = t.mapping.keys.toArray
          scala.util.Sorting.quickSort(languages)
          languages.foreach { lang =>
            b.addOne(Annotation.text(rdfsLabel, t.mapping(lang), Some(lang)))
          }
      }
      b.addAll(Metadata.annotations(metadata.copy(title = None), descriptionProperty, language))
      b.result()
    }

    /** LinkML `annotations` with plain values, with the tag as the property. A tag without a colon
      * goes in the default namespace. An annotation whose tag isn't a URI is skipped, as the tag
      * can't be an IRI in RDF.
      *
      * @param prefixes
      *   The prefixes of the schema that defines `el`, to expand CURIEs in values
      */
    private def linkmlAnnotations(el: Annotatable, prefixes: PrefixMap): Seq[Annotation] = {
      val b = Seq.newBuilder[Annotation]
      val language = sv.root.inLanguage
      el.annotations.values.foreach { a =>
        val tag = a.extensionTag.original
        val property =
          if (tag.contains(':')) tag
          else sv.getDefaultPrefix(sv.root).concat(tag)
        def add(v: String): Unit =
          b.addOne(
            Annotation(property, Metadata.inLanguage(annotationValue(v, prefixes), language)),
          )
        if (UriCurieValidator.isValidUri(property)) a.extensionValue.yaml match {
          case Right(node) =>
            node match {
              case ScalarNode(v, _) => add(v)
              case SequenceNode(items, _) =>
                items.foreach {
                  case ScalarNode(v, _) => add(v)
                  case _ =>
                }
              case _ =>
            }
          case _ =>
        }
      }
      b.result()
    }

    /** Reads a value as an IRI if it is a full IRI or a CURIE with a declared prefix, since the
      * importer writes IRIs that way. Anything else is text.
      */
    private def annotationValue(v: String, prefixes: PrefixMap): Value =
      if (v.contains("://") || v.startsWith("urn:") || v.startsWith("mailto:")) {
        if (UriCurieValidator.isValidUri(v)) Iri(v)
        else Literal(v)
      } else {
        val colon = v.indexOf(':')
        val prefix = if colon > 0 then v.substring(0, colon) else ""
        val uri =
          if prefix.nonEmpty && prefix.forall(c => Case.isStandard(c) || c == '-') &&
            !v.exists(_.isWhitespace)
          then prefixes.expandOrNull(v)
          else null
        if uri ne null then Iri(uri) else Literal(v)
      }

    // Classes

    /** The included classes, once each, in the schemas' order. */
    private lazy val includedClasses: Seq[ClassView] = {
      val seen = mutable.HashSet.empty[String]
      val b = Seq.newBuilder[ClassView]
      sv.schemas.foreach(_.classes.keys.foreach { name =>
        if (seen.add(name)) {
          val c = sv.classes(name)
          if (included(c)) b.addOne(c)
        }
      })
      b.result()
    }

    /** The included classes that name each class in `is_a`, in the schemas' order. */
    private lazy val childrenOf: collection.Map[String, collection.Seq[ClassExpr]] = {
      val b = mutable.HashMap.empty[String, mutable.ArrayBuffer[ClassExpr]]
      includedClasses.foreach { c =>
        c.cls.isA.foreachFast { parent =>
          b.getOrElseUpdate(parent.value, mutable.ArrayBuffer.empty)
            .addOne(ClassExpr.Named(c.uriStr))
        }
      }
      b
    }

    private def addClass(cv: ClassView): Unit = {
      val iri = cv.uriStr
      val self = ClassExpr.Named(iri)
      // Don't declare classes that are RDF or OWL terms, such as `rdf:Property`.
      if (!isReserved(iri)) add(Declaration(EntityKind.Class, iri))
      val equivalent = equivalentsOf(cv, classIris)
      annotate(iri, cv, equivalent)
      equivalent.toSeq.sorted.foreach(e => add(EquivalentClasses(Seq(self, ClassExpr.Named(e)))))

      def addParent(r: Reference[ClassDefinition]): Unit =
        sv.classes.get(r.value).foreachFast { parent =>
          if (!parent.isAny) add(SubClassOf(self, ClassExpr.Named(parent.uriStr)))
        }

      cv.cls.isA.foreachFast(addParent)
      cv.cls.mixins.foreach(addParent)
      ownSlots(cv).foreach { name =>
        cv.derivedAttributes.get(name).foreachFast { s =>
          restrictions(cv, s).foreach(r => add(SubClassOf(self, r)))
        }
      }
      cv.cls.uniqueKeys.values.foreach { key =>
        val b = Seq.newBuilder[String]
        key.uniqueKeySlots.foreach { s =>
          cv.derivedAttributes.get(s.value).foreachFast { a =>
            if (!a.slot.identifier) b.addOne(a.uriStr)
          }
        }
        val properties = b.result()
        if (properties.nonEmpty) add(HasKey(self, properties))
      }
      val children = childrenOf.getOrElse(cv.name, Nil)
      // Every instance of an abstract class is an instance of one of its children. With only one
      // child, that would make the two classes equivalent, so we skip it.
      if (children.sizeIs > 1 && (cv.cls.`abstract` || cv.cls.childrenAreMutuallyDisjoint)) {
        val operands = children.toSeq
        if (cv.cls.`abstract`) add(SubClassOf(self, ClassExpr.UnionOf(operands)))
        if (cv.cls.childrenAreMutuallyDisjoint) add(DisjointClasses(operands))
      }
      cv.cls.disjointWith.foreach { r =>
        sv.classes.get(r.value).foreachFast { other =>
          add(DisjointClasses(Seq(self, ClassExpr.Named(other.uriStr))))
        }
      }
    }

    /** Names of the slots the class declares or refines itself, not ones it just inherits. */
    private def ownSlots(cv: ClassView): mutable.LinkedHashSet[String] = {
      val names = mutable.LinkedHashSet.empty[String]
      cv.cls.slots.foreach(s => names.addOne(s.value))
      names.addAll(cv.cls.attributes.keys).addAll(cv.cls.slotUsage.keys)
    }

    private def restrictions(cv: ClassView, s: SlotView): Seq[ClassExpr] =
      if (s.slot.identifier) Nil
      else {
        val name = s.slot.name
        val usage = cv.cls.slotUsage.get(name)
        val sources = definitionsOf(name, Some(cv))

        def first[A](f: SlotDefinition => Option[A]): Option[A] =
          sources.iterator.flatMap(f).nextOption()

        def any(f: SlotDefinition => Boolean): Boolean = sources.exists(f)

        val kind = kindOf(s)
        // Annotation properties only get the restrictions the schema states explicitly.
        val annotation = kind == EntityKind.AnnotationProperty
        val p = PropertyRef(s.uriStr)
        val stated = Seq.newBuilder[ClassExpr]
        // With `slot_usage` on an inherited slot, state the range only if it is narrowed. For a
        // slot from an imported schema, the importer can't tell a repeated range from a new one.
        val declares = cv.cls.slots.exists(_.value == name) || cv.cls.attributes.contains(name)
        val narrows = usage.foldFast(false)(u => u.range.isDefined || u.anyOf.nonEmpty)
        val range =
          if (annotation) {
            usage.flatMapFast(_.range).flatMapFast(r => sv.getElement(r.value))
              .flatMapFast(rangeOfElement(_, s.slot))
          } else if (declares || narrows) nearestRange(s, sources)
          else None
        range.foreachFast(r => stated.addOne(ClassExpr.AllValuesFrom(p, r)))
        val exact = first(_.exactCardinality)
        val min = exact.orElseFast(first(_.minimumCardinality))
          .orElseFast(if (any(d => d.required || d.key)) Some(1) else None)
        // A subclass inherits this limit, so a `slot_usage` there that makes the slot multivalued
        // has no effect (LNK-238).
        val max = exact.orElseFast(first(_.maximumCardinality))
          .orElseFast(if (!annotation && !any(_.multivalued)) Some(1) else None)
        // OWL 2 DL allows no cardinality on a non-simple property, so a required value becomes
        // `someValuesFrom`.
        if (nonSimple(s.uriStr)) {
          if (min.foldFast(false)(_ > 0)) {
            stated.addOne(ClassExpr.SomeValuesFrom(p, ClassExpr.Thing))
          }
        } else if (min.isDefined && min == max) {
          min.foreachFast(m => stated.addOne(ClassExpr.Cardinality(ClassExpr.Bound.Exact, m, p)))
        } else {
          min.foreachFast { m =>
            if (m > 0) stated.addOne(ClassExpr.Cardinality(ClassExpr.Bound.Min, m, p))
          }
          max.foreachFast(m => stated.addOne(ClassExpr.Cardinality(ClassExpr.Bound.Max, m, p)))
        }
        first(_.equalsString).flatMapFast(valueOf(s, _))
          .orElseFast(first(_.equalsNumber).mapFast(n => Literal(n.toString, XmlSchema.integer)))
          .foreachFast(v => stated.addOne(ClassExpr.HasValue(p, v)))

        // `has_member`, plus any under `all_of`. The importer writes a class that needs values of
        // several kinds that way.
        def addMember(m: AnonymousSlotExpression): Unit =
          expressionRange(s, m).foreachFast(r => stated.addOne(ClassExpr.SomeValuesFrom(p, r)))

        first(_.hasMember).foreachFast(addMember)
        usage.foreachFast(_.allOf.foreach(_.hasMember.foreachFast(addMember)))
        val result = stated.result()
        // A class that uses a slot without constraining it still records the use, with a
        // restriction that any value meets.
        if (result.nonEmpty || annotation || !declares) result
        else {
          val anything = kind match {
            case _: EntityKind.DataProperty.type => DataRange.Literal
            case _ => ClassExpr.Thing
          }
          Seq(ClassExpr.AllValuesFrom(p, anything))
        }
      }

    /** The value that `equals_string` means for the slot's range. Nothing for a value that should
      * be an IRI but isn't a URI, as it can't be an IRI in RDF.
      */
    private def valueOf(s: SlotView, text: String): Option[Value] = {
      def iri(uri: String): Option[Value] =
        if (UriCurieValidator.isValidUri(uri)) new Some(Iri(uri))
        else None

      def expanded: Option[Value] = iri(UriOrCurie.expanded(text, s.definingPrefixes).original)

      s.derivedRange.resolve.orNullFast match {
        case ev: EnumView => ev.toMeaning.get(text).flatMapFast(m => iri(m.original))
        case tv: TypeView =>
          if (tv.isIri) expanded
          else new Some(Literal(text, Iri(baseDatatype(tv))))
        case _: ClassView => expanded
        case _ => new Some(Literal(text))
      }
    }

    // Ranges

    private def rangeOf(s: SlotView, context: Option[ClassView] = None): Option[Filler] =
      nearestRange(s, definitionsOf(s.slot.name, context))

    /** The definitions of a slot, nearest first: the class's `slot_usage`, the attributes of the
      * class and its ancestors, then the slot and its parents. An ancestor's `slot_usage` is
      * skipped, because OWL passes it down through the subclass axiom anyway.
      */
    private def definitionsOf(name: String, context: Option[ClassView]): Seq[SlotDefinition] = {
      val b = Seq.newBuilder[SlotDefinition]
      context.foreachFast { c =>
        c.cls.slotUsage.get(name).foreachFast(u => b.addOne(u))
        c.ancestorsWithSelf.foreach(_.cls.attributes.get(name).foreachFast(a => b.addOne(a)))
      }
      sv.slotDefinitions.get(name).foreachFast(_.ancestorsWithSelf.foreach(a => b.addOne(a.slot)))
      b.result()
    }

    /** The range of the nearest definition that has one. Skipping an ancestor's `slot_usage` loses
      * the range when the class lists the slot itself, or when the slot URI changes on the way
      * (LNK-236). The derived slot is not used, since it keeps the inherited `any_of` lists in
      * `all_of`, and OWL already requires them through subclassing.
      */
    private def nearestRange(s: SlotView, definitions: Seq[SlotDefinition]): Option[Filler] = {
      val e = definitions.find(d => d.range.isDefined || d.anyOf.nonEmpty).orNullFast
      if (e eq null) {
        sv.getDefaultRange(s.definingSchema).resolve.flatMapFast(rangeOfElement(_, s.slot))
      } else if (
        e.anyOf.nonEmpty &&
        e.range.foldFast(true)(r => sv.classes.get(r.value).foldFast(false)(_.isAny))
      ) {
        val anyOf = e.anyOf.distinct
        val alternatives = anyOf.flatMap(a => expressionRange(s, a))
        if (alternatives.sizeIs != anyOf.size) None
        else union(alternatives)
      } else e.range.flatMapFast(r => sv.getElement(r.value)).flatMapFast(rangeOfElement(_, s.slot))
    }

    /** The range of one `any_of` or `has_member` entry. */
    private def expressionRange(s: SlotView, e: AnonymousSlotExpression): Option[Filler] =
      e.range.flatMapFast(r => sv.getElement(r.value)).orElseFast(s.derivedRange.resolve)
        .flatMapFast(rangeOfElement(_, e))

    /** A union of the alternatives, or the only alternative, since OWL 2 DL requires at least two
      * operands in a union.
      */
    private def union(alternatives: Seq[Filler]): Option[Filler] =
      if (alternatives.sizeIs == 1) alternatives.headOption
      else if (alternatives.forall(_.isInstanceOf[DataRange])) {
        Some(DataRange.UnionOf(alternatives.asInstanceOf[Seq[DataRange]]))
      } else if (alternatives.forall(_.isInstanceOf[ClassExpr])) {
        Some(ClassExpr.UnionOf(alternatives.asInstanceOf[Seq[ClassExpr]]))
      } else None

    private def rangeOfElement(el: ElementView[?, ?], constraints: SlotExpression): Option[Filler] =
      el match {
        case cv: ClassView =>
          if (cv.isAny) None
          else Some(ClassExpr.Named(cv.uriStr))
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
          Some(
            if (own.isEmpty) datatypeOf(tv)
            else {
              // OWL only allows facets on built-in datatypes, so repeat the type's own constraints
              // next to the slot's.
              val d = tv.derivedType
              val inherited = facets(d.minimumValue, d.maximumValue, tv.pattern, tv)
                .filterNot(f => own.exists(_._1 == f._1))
              DataRange.Restriction(baseDatatype(tv), own.concat(inherited))
            },
          )
        case _ => None
      }

    private def datatypeOf(tv: TypeView): DataRange =
      if (tv.isPrimitive) {
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
      } else {
        // A type without its own `uri` stands for the ancestor it inherits the `uri` from.
        tv.ancestorsWithSelf.find(_._type.typeUri.isDefined) match {
          case Some(origin) if origin.isPrimitive && !hasFacets(tv) => datatypeOf(origin)
          case _ if hasFacets(tv) && OwlRdfReader.isBuiltInDatatype(tv.uriStr) =>
            val d = tv.derivedType
            DataRange.Restriction(tv.uriStr, facets(d.minimumValue, d.maximumValue, tv.pattern, tv))
          case _ => DataRange.Datatype(tv.uriStr)
        }
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
          case _: IntegerType.type => XmlSchema.integer.value
          case _: FloatType.type => XmlSchema.float.value
          case _: DoubleType.type => XmlSchema.double.value
          case _: DecimalType.type => XmlSchema.decimal.value
          case _: BooleanType.type => XmlSchema.boolean.value
          case _: DateType.type => XmlSchema.date.value
          case _: DateTimeType.type => XmlSchema.dateTime.value
          case _: TimeType.type => XmlSchema.time.value
          case _: UriType.type | _: UriOrCurieType.type => XmlSchema.anyURI.value
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
      val b = Seq.newBuilder[(String, Literal)]
      min.foreachFast(v =>
        b.addOne(XmlSchema.minInclusive.value -> Literal(v.value.strip, datatype)),
      )
      max.foreachFast(v =>
        b.addOne(XmlSchema.maxInclusive.value -> Literal(v.value.strip, datatype)),
      )
      pattern.foreachFast(p => b.addOne(XmlSchema.pattern.value -> Literal(p.xsd)))
      b.result()
    }

    // Slots

    /** Properties OWL 2 DL calls non-simple: transitive ones, their parents, and inverses of these.
      * OWL 2 DL allows no cardinality or functional axioms on them.
      */
    private lazy val nonSimple: Set[String] = {
      // The slots that name each slot as their `inverse`.
      val namedAsInverse = mutable.HashMap.empty[String, mutable.ArrayBuffer[SlotView]]
      sv.slotDefinitions.foreachEntry { (_, s) =>
        s.slot.inverse.foreachFast { i =>
          namedAsInverse.getOrElseUpdate(i.value, mutable.ArrayBuffer.empty).addOne(s)
        }
      }
      val found = mutable.HashSet.empty[SlotView]
      val pending = mutable.ArrayBuffer.empty[SlotView]

      def addWithAncestors(s: SlotView): Unit =
        s.ancestorsWithSelf.foreach(a => if (found.add(a)) pending.addOne(a))

      sv.slotDefinitions.foreachEntry((_, s) => if (s.slot.transitive) addWithAncestors(s))
      // Add the inverses of each slot found, with their ancestors, until no new slot is found.
      while (pending.nonEmpty) {
        val s = pending.remove(pending.length - 1)
        s.slot.inverse.foreachFast(i =>
          sv.slotDefinitions.get(i.value).foreachFast(addWithAncestors),
        )
        namedAsInverse.get(s.slot.name).foreachFast(_.foreach(addWithAncestors))
      }
      val b = Set.newBuilder[String]
      found.foreach(s => b.addOne(s.uriStr))
      b.result()
    }

    /** Whether each property is single-valued in every class that uses it. */
    private lazy val singleValued: collection.Map[String, Boolean] = {
      val b = mutable.HashMap.empty[String, Boolean]
      sv.classes.foreachEntry { (_, c) =>
        c.derivedAttributes.foreachEntry { (_, a) =>
          val iri = a.uriStr
          b.update(iri, !a.slot.multivalued && b.getOrElse(iri, true))
        }
      }
      b
    }

    private def kindOf(s: SlotView): EntityKind = {
      val owl = "http://www.w3.org/2002/07/owl#"
      def implements(uri: String): Boolean = s.slot.implements.exists(_.original == uri)
      if (implements(owl.concat("AnnotationProperty"))) EntityKind.AnnotationProperty
      else if (implements(owl.concat("DatatypeProperty"))) EntityKind.DataProperty
      else if (implements(owl.concat("ObjectProperty"))) EntityKind.ObjectProperty
      else {
        // Look at every use of the property, since a slot may only get its range in a class.
        // An attribute has no range outside its classes.
        val own = if (sv.slotDefinitions.contains(s.slot.name)) rangeOf(s) else None
        val uses = rangesByProperty.getOrElse(s.uriStr, Nil)
        if (own.isDefined || uses.exists(_.isDefined)) {
          if (
            own.foldFast(true)(_.isInstanceOf[DataRange]) &&
            uses.forall(_.foldFast(true)(_.isInstanceOf[DataRange]))
          ) EntityKind.DataProperty
          else EntityKind.ObjectProperty
        } else {
          // Nothing to go on, so use the parent's kind.
          s.parents.headOption.mapFast(kindOf).getOrElseFast(EntityKind.ObjectProperty)
        }
      }
    }

    /** The range of each use of each property, in every class. */
    private lazy val rangesByProperty: collection.Map[String, collection.Seq[Option[Filler]]] = {
      val b = mutable.HashMap.empty[String, mutable.ArrayBuffer[Option[Filler]]]
      sv.classes.foreachEntry { (_, c) =>
        c.derivedAttributes.foreachEntry { (_, a) =>
          b.getOrElseUpdate(a.uriStr, mutable.ArrayBuffer.empty).addOne(rangeOf(a, Some(c)))
        }
      }
      b
    }

    private def addSlot(slotView: SlotView): Unit = {
      val slot = slotView.slot
      val iri = slotView.uriStr
      val kind = kindOf(slotView)
      add(Declaration(kind, iri))
      val equivalent = equivalentsOf(slotView, slotIris)
      annotate(iri, slotView, equivalent)
      equivalent.toSeq.sorted.foreach(e => add(EquivalentProperties(Seq(iri, e))))
      val annotation = kind == EntityKind.AnnotationProperty
      // A slot without its own range gets its parent's through `rdfs:subPropertyOf`. An annotation
      // property only gets a range it states, since its values aren't the schema's data.
      if (annotation)
        slot.range.flatMapFast(r => sv.getElement(r.value)).flatMapFast(rangeOfElement(_, slot))
          .foreachFast(r => add(Range(iri, r)))
      else if (
        slot.range.isDefined || slot.anyOf.nonEmpty || (slot.isA.isEmpty && slot.mixins.isEmpty)
      ) rangeOf(slotView).foreachFast(r => add(Range(iri, r)))
      slot.domain.foreachFast { d =>
        sv.classes.get(d.value).foreachFast(c => add(Domain(iri, ClassExpr.Named(c.uriStr))))
      }
      // `domain` holds one class, so a union of classes as the domain is kept in `domain_of`.
      val b = Seq.newBuilder[ClassExpr]
      slot.domainOf.foreach { d =>
        sv.classes.get(d.value).foreachFast(c => b.addOne(ClassExpr.Named(c.uriStr)))
      }
      val domains = b.result()
      if (domains.sizeIs == 1) add(Domain(iri, domains.head))
      else if (domains.nonEmpty) add(Domain(iri, ClassExpr.UnionOf(domains)))

      def addParent(r: Reference[SlotDefinition]): Unit =
        sv.slotDefinitions.get(r.value).foreachFast(p => add(SubPropertyOf(iri, p.uriStr)))

      slot.isA.foreachFast(addParent)
      slot.mixins.foreach(addParent)
      if (!annotation) {
        slot.inverse.foreachFast { i =>
          sv.slotDefinitions.get(i.value).foreachFast { inverse =>
            val other = inverse.uriStr
            add(
              if (iri.compareTo(other) <= 0) InverseProperties(iri, other)
              else InverseProperties(other, iri),
            )
          }
        }
        if (slot.transitive) add(Characteristic(iri, PropertyCharacteristic.Transitive))
        if (slot.symmetric) add(Characteristic(iri, PropertyCharacteristic.Symmetric))
        if (slot.asymmetric) add(Characteristic(iri, PropertyCharacteristic.Asymmetric))
        if (slot.reflexive) add(Characteristic(iri, PropertyCharacteristic.Reflexive))
        if (slot.irreflexive) add(Characteristic(iri, PropertyCharacteristic.Irreflexive))
        if (!slot.multivalued && singleValued.getOrElse(iri, true) && !nonSimple(iri)) {
          add(Characteristic(iri, PropertyCharacteristic.Functional))
        }
      }
    }

    /** Declares properties for attributes, which have no top-level slot. Their range and
      * cardinality are stated on their class.
      */
    private def addAttributes(cv: ClassView): Unit =
      cv.cls.attributes.foreach { (name, attribute) =>
        val derived = cv.derivedAttributes(name)
        val iri = derived.uriStr
        if (!derived.slot.identifier && !declared(iri)) {
          val kind = kindOf(derived)
          add(Declaration(kind, iri))
          annotate(iri, attribute, cv.definingPrefixes)
          if (kind != EntityKind.AnnotationProperty) {
            // If every use of the property has the same range, that becomes its range.
            val uses = rangesByProperty.getOrElse(iri, Nil)
            if (uses.nonEmpty) {
              val range = uses.head
              if (uses.forall(_ == range)) range.foreachFast(r => add(Range(iri, r)))
            }
            if (singleValued.getOrElse(iri, false) && !nonSimple(iri)) {
              add(Characteristic(iri, PropertyCharacteristic.Functional))
            }
          }
        }
      }

    // Enums

    private def addEnum(ev: EnumView): Unit = {
      val iri = ev.uriStr
      add(Declaration(EntityKind.Class, iri))
      annotate(iri, ev)
      ev.parents.foreach { parent =>
        add(SubClassOf(ClassExpr.Named(iri), ClassExpr.Named(parent.uriStr)))
      }
      val values = ev.derivedValues.map { pvv =>
        val pv = pvv.pv
        val pvIri = pvv.uriStr
        options.permissibleValues match {
          case _: PermissibleValueKind.individual.type =>
            add(Declaration(EntityKind.NamedIndividual, pvIri))
            add(ClassAssertion(ClassExpr.Named(iri), pvIri))
          case _: PermissibleValueKind.`class`.type =>
            add(Declaration(EntityKind.Class, pvIri))
            add(SubClassOf(ClassExpr.Named(pvIri), ClassExpr.Named(iri)))
        }
        // The text goes in `skos:notation`. The importer falls back to the local name, so the
        // notation is only written when they differ. This keeps it off IRIs that are also
        // classes, which Schema.org has.
        if (OwlImportNames.localName(pvIri) != pv.text) {
          add(AnnotationAssertion(pvIri, Annotation.text(skosNotation, pv.text)))
        }
        annotate(pvIri, pv, ev.definingPrefixes)
        pvIri
      }
      if (values.nonEmpty)
        options.permissibleValues match {
          case _: PermissibleValueKind.individual.type =>
            add(EquivalentClasses(Seq(ClassExpr.Named(iri), ClassExpr.OneOf(values))))
          case _: PermissibleValueKind.`class`.type =>
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
          if (hasFacets(tv)) {
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
