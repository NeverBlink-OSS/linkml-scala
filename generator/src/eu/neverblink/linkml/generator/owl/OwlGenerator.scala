package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.options.{
  OwlGeneratorOptions,
  OwlMetadataProfile,
  OwlPermissibleValueKind,
}
import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.RdfGeneratorBase
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
  * https://linkml.ohi.dev/docs/generators/owl/ for details.
  */
class OwlGenerator(using sv: SchemaView) extends RdfGeneratorBase[OwlGeneratorOptions] {
  import OwlGenerator.*

  override protected def defaultOptions: OwlGeneratorOptions = OwlGeneratorOptions()

  /** Builds the ontology without writing it as RDF. */
  def ontology(options: OwlGeneratorOptions = defaultOptions): Ontology =
    new Build(options).ontology

  override def generate(sink: RdfSink, options: OwlGeneratorOptions = defaultOptions): Unit =
    OwlRdfWriter(sink).write(ontology(options))
}

object OwlGenerator {

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
  private val owl2Datatypes: Set[String] = {
    val datatypes = Set.newBuilder[String]
    Array(
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
    ).foreach(local => datatypes.addOne(XmlSchema.prefix.concat(local)))
    datatypes.addOne(Owl.get("real").value)
    datatypes.addOne(Owl.get("rational").value)
    datatypes.addOne(plainLiteral)
    datatypes.addOne(Rdf.get("XMLLiteral").value)
    datatypes.addOne(Rdfs.Literal.value)
    datatypes.result()
  }
  private[owl] val skosNotation = "http://www.w3.org/2004/02/skos/core#notation"

  private val noIris: Array[String] = Array.empty

  private val noRanges: mutable.ArrayBuffer[Filler] = mutable.ArrayBuffer.empty

  /** Stands for a cardinality that is not set. */
  private val noCardinality: Int = Int.MinValue

  private final class Build(options: OwlGeneratorOptions)(using sv: SchemaView) {

    /** The axioms, once each, in the order they were first added. */
    private val axioms = mutable.ArrayBuffer.empty[Axiom]
    private val added = mutable.HashSet.empty[Axiom]
    // The IRIs of the declarations in `axioms`, to check for one without scanning all axioms.
    private val declared = mutable.HashSet.empty[String]

    private def add(axiom: Axiom): Unit =
      if (added.add(axiom)) {
        axioms.addOne(axiom)
        axiom match {
          case d: Declaration => declared.addOne(d.iri)
          case _ =>
        }
      }

    private def included(el: ElementView[?, ?]): Boolean =
      !options.onlyRootSchema || (el.definingSchema eq sv.root)

    private val descriptionProperty = options.metadataProfile match {
      case _: OwlMetadataProfile.Linkml.type => "http://www.w3.org/2004/02/skos/core#definition"
      case _: OwlMetadataProfile.Rdfs.type => Rdfs.comment.value
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
          val ids = Vector.newBuilder[String]
          val seen = mutable.HashSet.empty[String]
          sv.schemas.iterator.drop(1).foreach { s =>
            val id = s.id.original
            if (!id.startsWith("https://w3id.org/linkml/") && seen.add(id)) ids.addOne(id)
          }
          ids.result()
        } else Nil
      val headerBuilder = Vector.newBuilder[Annotation]
      addMetadataAnnotations(Metadata.of(root), headerBuilder.addOne)
      addLinkmlAnnotations(root, headerBuilder.addOne)
      addPreferredNamespace(headerBuilder)
      val header = headerBuilder.result()
      val prefixes = Vector.newBuilder[(String, String)]
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
        axioms = Vector.from(axioms),
      )
    }

    /** OWL 2 DL requires every annotation property to be declared, except the built-in ones. */
    private def declareAnnotationProperties(header: Seq[Annotation]): Unit = {
      val used = mutable.ArrayBuffer.empty[String]
      val seen = mutable.HashSet.empty[String]
      def use(p: String): Unit = if (seen.add(p)) used.addOne(p)
      header.foreach(a => use(a.property))
      axioms.foreach {
        case a: AnnotationAssertion => use(a.annotation.property)
        case _ =>
      }
      // The ontology always has prefixes, which are written with these SHACL terms.
      use(Shacl.declare.value)
      use(shaclPrefix.value)
      use(Shacl.namespace.value)
      // Never declare RDF, RDFS, OWL or XSD terms. An annotation like `rdf:type voaf:Vocabulary`
      // is just written back as plain RDF. Declaring adds to `axioms`, so it is done after the
      // loop over them.
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
      val used = mutable.ArrayBuffer.empty[String]
      val seen = mutable.HashSet.empty[String]
      def use(r: Filler): Unit =
        OwlAxiomIndex.foreachDatatypeIn(r)(d => if (seen.add(d)) used.addOne(d))
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
    private def addPreferredNamespace(out: mutable.Growable[Annotation]): Unit = {
      out.addOne(
        Annotation.text("http://purl.org/vocab/vann/preferredNamespacePrefix", defaultPrefix.prefix),
      )
      out.addOne(
        Annotation(
          "http://purl.org/vocab/vann/preferredNamespaceUri",
          Literal(defaultPrefix.namespace, XmlSchema.anyURI),
        ),
      )
    }

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
    private def annotate(iri: String, el: ElementView[?, ?], equivalent: Array[String]): Unit =
      annotate(iri, el.inner, equivalent)

    private def annotate(iri: String, el: ElementView[?, ?]): Unit =
      annotate(iri, el.inner, noIris)

    /** Annotates `iri` with the metadata. Exact mappings in `equivalent`, which are written as
      * equivalence axioms, are skipped.
      */
    private def annotate(
        iri: String,
        metadata: CommonMetadata & Annotatable,
        equivalent: Array[String],
    ): Unit = {
      val m = Metadata.of(metadata)
      val kept =
        if (equivalent.isEmpty) m
        else m.copy(exactMappings = m.exactMappings.filterNot(e => equivalent.contains(e.original)))
      val add: Annotation => Unit = a => this.add(AnnotationAssertion(iri, a))
      addMetadataAnnotations(kept, add)
      addLinkmlAnnotations(metadata, add)
    }

    private def annotate(iri: String, metadata: CommonMetadata & Annotatable): Unit =
      annotate(iri, metadata, noIris)

    private lazy val classIris: mutable.HashSet[String] = {
      val b = mutable.HashSet.empty[String]
      sv.classes.foreachEntry((_, c) => if (included(c)) b.addOne(c.uriStr))
      b
    }

    private lazy val slotIris: mutable.HashSet[String] = {
      val b = mutable.HashSet.empty[String]
      sv.slotDefinitions.foreachEntry((_, s) => if (included(s)) b.addOne(s.uriStr))
      b
    }

    /** Exact mappings to another element of the same kind in this ontology, sorted and without
      * duplicates. These become OWL equivalences. The rest stay `skos:exactMatch`.
      */
    private def equivalentsOf(
        el: ElementView[?, ?],
        among: mutable.HashSet[String],
    ): Array[String] = {
      val b = mutable.ArrayBuffer.empty[String]
      el.inner.exactMappings.foreach { m =>
        val uri = m.original
        if (among(uri) && uri != el.uriStr && !b.contains(uri)) b.addOne(uri)
      }
      val sorted = b.toArray
      java.util.Arrays.sort(sorted, Ordering.String)
      sorted
    }

    /** `rdfs:label` is the title in each of its languages. OWL tools show `rdfs:label`, so the
      * title goes there and not to `dcterms:title`.
      */
    private def addMetadataAnnotations(metadata: Metadata, b: Annotation => Unit): Unit = {
      // Use the element's language, or the schema's, as `gen-owl` does with `in_language`.
      val language = metadata.inLanguage.orElseFast(sv.root.inLanguage)
      metadata.title.foreachFast {
        case t: PlainText => b(Annotation.text(rdfsLabel, t.value, language))
        case t: MultilingualText =>
          val languages = t.mapping.keys.toArray
          java.util.Arrays.sort(languages, Ordering.String)
          languages.foreach { lang =>
            b(Annotation.text(rdfsLabel, t.mapping(lang), Some(lang)))
          }
      }
      Metadata.annotations(metadata.copy(title = None), descriptionProperty, language).foreach(b)
    }

    /** LinkML `annotations` with plain values, with the tag as the property. A tag without a colon
      * is put in the default namespace of its schema when the schema is read.
      */
    private def addLinkmlAnnotations(el: Annotatable, b: Annotation => Unit): Unit = {
      val language = sv.root.inLanguage
      el.annotations.valuesIterator.foreach { a =>
        val property = a.extensionTag.original
        def add(v: String): Unit =
          b(Annotation(property, Metadata.inLanguage(annotationValue(v), language)))
        a.extensionValue.yaml.foreach {
          case n: ScalarNode => add(n.value)
          case n: SequenceNode =>
            n.nodes.foreach {
              case item: ScalarNode => add(item.value)
              case _ =>
            }
          case _ =>
        }
      }
    }

    /** Reads a value as an IRI if it is a full IRI, as the importer writes IRIs that way. Anything
      * else is text, even if it looks like a CURIE.
      */
    private def annotationValue(v: String): Value =
      if (UriOrCurie.isFullUri(v)) Iri(v)
      else Literal(v)

    // Classes

    /** The included classes, once each, in the schemas' order. */
    private lazy val includedClasses: mutable.ArrayBuffer[ClassView] = {
      val seen = mutable.HashSet.empty[String]
      val b = mutable.ArrayBuffer.empty[ClassView]
      sv.schemas.foreach(_.classes.keys.foreach { name =>
        if (seen.add(name)) {
          val c = sv.classes(name)
          if (included(c)) b.addOne(c)
        }
      })
      b
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
      equivalent.foreach(e => add(EquivalentClasses(Vector(self, ClassExpr.Named(e)))))

      def addParent(r: Reference[ClassDefinition]): Unit =
        sv.classes.get(r.value).foreachFast { parent =>
          if (!parent.isAny) add(SubClassOf(self, ClassExpr.Named(parent.uriStr)))
        }

      cv.cls.isA.foreachFast(addParent)
      cv.cls.mixins.foreach(addParent)
      val seen = mutable.HashSet.empty[String]
      def restrict(name: String): Unit =
        if (seen.add(name)) {
          cv.derivedAttributes.get(name).foreachFast(s => addRestrictions(cv, s, self))
        }
      // The slots the class declares or refines itself, not ones it just inherits.
      cv.cls.slots.foreach(s => restrict(s.value))
      cv.cls.attributes.keysIterator.foreach(restrict)
      cv.cls.slotUsage.keysIterator.foreach(restrict)
      cv.cls.uniqueKeys.valuesIterator.foreach { key =>
        val b = Vector.newBuilder[String]
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
        val operands = Vector.from(children)
        if (cv.cls.`abstract`) add(SubClassOf(self, ClassExpr.UnionOf(operands)))
        if (cv.cls.childrenAreMutuallyDisjoint) add(DisjointClasses(operands))
      }
      cv.cls.disjointWith.foreach { r =>
        sv.classes.get(r.value).foreachFast { other =>
          add(DisjointClasses(Vector(self, ClassExpr.Named(other.uriStr))))
        }
      }
    }

    /** Adds the restrictions that the class states on the slot, as superclasses of `self`. */
    private def addRestrictions(cv: ClassView, s: SlotView, self: ClassExpr.Named): Unit =
      if (!s.slot.identifier) {
        val name = s.slot.name
        val usage = cv.cls.slotUsage.getOrElse(name, null)
        val sources = definitionsOf(name, cv)

        def any(f: SlotDefinition => Boolean): Boolean = sources.exists(f)

        val kind = kindOf(s)
        // Annotation properties only get the restrictions the schema states explicitly.
        val annotation = kind == EntityKind.AnnotationProperty
        val p = PropertyRef(s.uriStr)
        var stated = 0
        def state(e: ClassExpr): Unit = {
          add(SubClassOf(self, e))
          stated += 1
        }
        // With `slot_usage` on an inherited slot, state the range only if it is narrowed. For a
        // slot from an imported schema, the importer can't tell a repeated range from a new one.
        val declares = cv.cls.slots.exists(_.value == name) || cv.cls.attributes.contains(name)
        val narrows = (usage ne null) && (usage.range.isDefined || usage.anyOf.nonEmpty)
        val range =
          if (annotation) {
            val el =
              if ((usage ne null) && usage.range.isDefined) {
                sv.getElement(usage.range.get.value).orNullFast
              } else null
            if (el ne null) rangeOfElement(el, s.slot) else null
          } else if (declares || narrows) nearestRange(s, sources)
          else null
        if (range ne null) state(ClassExpr.AllValuesFrom(p, range))
        val exact = firstCardinality(sources, _.exactCardinality)
        var min = exact
        if (min == noCardinality) min = firstCardinality(sources, _.minimumCardinality)
        if (min == noCardinality && any(d => d.required || d.key)) min = 1
        // A subclass inherits this limit, so a `slot_usage` there that makes the slot multivalued
        // has no effect (LNK-238).
        var max = exact
        if (max == noCardinality) max = firstCardinality(sources, _.maximumCardinality)
        if (max == noCardinality && !annotation && !any(_.multivalued) && !isText(s)) max = 1
        // OWL 2 DL allows no cardinality on a non-simple property, so a required value becomes
        // `someValuesFrom`.
        if (nonSimple(s.uriStr)) {
          if (min != noCardinality && min > 0) state(ClassExpr.SomeValuesFrom(p, ClassExpr.Thing))
        } else if (min != noCardinality && min == max) {
          state(ClassExpr.Cardinality(ClassExpr.Bound.Exact, min, p))
        } else {
          if (min != noCardinality && min > 0) {
            state(ClassExpr.Cardinality(ClassExpr.Bound.Min, min, p))
          }
          if (max != noCardinality) state(ClassExpr.Cardinality(ClassExpr.Bound.Max, max, p))
          // A single-valued `langstring` slot holds at most one plain string. OWL can't say that
          // it holds at most one text per language. The importer reads this back as single-valued.
          if (max == noCardinality && !annotation && !any(_.multivalued) && isText(s)) {
            state(
              ClassExpr.Cardinality(
                ClassExpr.Bound.Max,
                1,
                p,
                Some(DataRange.Datatype(XmlSchema.string.value)),
              ),
            )
          }
        }
        val text = firstOf(sources, _.equalsString)
        var value: Value = if (text ne null) valueOf(s, text) else null
        if (value eq null) {
          val n = firstCardinality(sources, _.equalsNumber)
          if (n != noCardinality) value = Literal(n.toString, XmlSchema.integer)
        }
        if (value ne null) state(ClassExpr.HasValue(p, value))

        // `has_member`, plus any under `all_of`. The importer writes a class that needs values of
        // several kinds that way.
        def addMember(m: AnonymousSlotExpression): Unit = {
          val r = expressionRange(s, m)
          if (r ne null) state(ClassExpr.SomeValuesFrom(p, r))
        }

        val member = firstOf(sources, _.hasMember)
        if (member ne null) addMember(member)
        if (usage ne null) usage.allOf.foreach(_.hasMember.foreachFast(addMember))
        // A class that uses a slot without constraining it still records the use, with a
        // restriction that any value meets.
        if (stated == 0 && !annotation && declares) {
          val anything = kind match {
            case _: EntityKind.DataProperty.type => DataRange.Literal
            case _ => ClassExpr.Thing
          }
          state(ClassExpr.AllValuesFrom(p, anything))
        }
      }

    /** The first value of a field in the definitions, or `null` if none has it. */
    private def firstOf[A <: AnyRef](
        sources: collection.Seq[SlotDefinition],
        f: SlotDefinition => Option[A],
    ): A = {
      val it = sources.iterator
      while (it.hasNext) {
        val v = f(it.next())
        if (v.isDefined) return v.get
      }
      null.asInstanceOf[A]
    }

    /** The first value of a number field in the definitions, or [[noCardinality]] if none has it.
      */
    private def firstCardinality(
        sources: collection.Seq[SlotDefinition],
        f: SlotDefinition => Option[Int],
    ): Int = {
      val it = sources.iterator
      while (it.hasNext) {
        val v = f(it.next())
        if (v.isDefined) return v.get
      }
      noCardinality
    }

    /** The value that `equals_string` means for the slot's range, or `null` if it is not a
      * permissible value of the slot's enum.
      *
      * @throws SchemaConversionException
      *   if the value should be an IRI but isn't a URI
      */
    private def valueOf(s: SlotView, text: String): Value = {
      def iri: Value = {
        val uri = s.uriValueOrNull(text)
        if (uri ne null) Iri(uri)
        else
          throw SchemaConversionException(
            s"The equals_string value '$text' of slot '${s.name}' isn't a valid URI or CURIE",
          )
      }
      s.derivedRange.resolve.orNullFast match {
        case ev: EnumView =>
          val meaning = ev.toMeaning.getOrElse(text, null)
          if (meaning ne null) Iri(meaning.original) else null
        case tv: TypeView =>
          // An `equals_string` of a `langstring` slot is a plain string, an `xsd:string` literal
          if (tv.isIri) iri
          else if (tv.runtimeType eq LocalizedTextType) Literal(text)
          else Literal(text, Iri(baseDatatype(tv)))
        case _: ClassView => iri
        case _ => Literal(text)
      }
    }

    // Ranges

    /** The range of a slot, in a class if `context` is not `null`, or `null` if it has none. */
    private def rangeOf(s: SlotView, context: ClassView = null): Filler =
      nearestRange(s, definitionsOf(s.slot.name, context))

    /** The definitions of a slot, nearest first: the class's `slot_usage`, the attributes of the
      * class and its ancestors, then the slot and its parents. An ancestor's `slot_usage` is
      * skipped, because OWL passes it down through the subclass axiom anyway.
      */
    private def definitionsOf(
        name: String,
        context: ClassView,
    ): mutable.ArrayBuffer[SlotDefinition] = {
      val b = mutable.ArrayBuffer.empty[SlotDefinition]
      if (context ne null) {
        context.cls.slotUsage.get(name).foreachFast(u => b.addOne(u))
        context.ancestorsWithSelf.foreach(_.cls.attributes.get(name).foreachFast(a => b.addOne(a)))
      }
      sv.slotDefinitions.get(name).foreachFast(_.ancestorsWithSelf.foreach(a => b.addOne(a.slot)))
      b
    }

    /** The range of the nearest definition that has one, or `null` if it can't be said in OWL.
      * Skipping an ancestor's `slot_usage` loses the range when the class lists the slot itself, or
      * when the slot URI changes on the way (LNK-236). The derived slot is not used, since it keeps
      * the inherited `any_of` lists in `all_of`, and OWL already requires them through subclassing.
      */
    private def nearestRange(s: SlotView, definitions: collection.Seq[SlotDefinition]): Filler = {
      val e = definitions.find(d => d.range.isDefined || d.anyOf.nonEmpty).orNullFast
      if (e eq null) {
        val el = sv.getDefaultRange(s.definingSchema).resolve.orNullFast
        if (el ne null) rangeOfElement(el, s.slot) else null
      } else if (
        e.anyOf.nonEmpty &&
        e.range.foldFast(true)(r => sv.classes.get(r.value).foldFast(false)(_.isAny))
      ) {
        val seen = mutable.HashSet.empty[AnonymousSlotExpression]
        val alternatives = mutable.ArrayBuffer.empty[Filler]
        val it = e.anyOf.iterator
        while (it.hasNext) {
          val a = it.next()
          if (seen.add(a)) {
            val r = expressionRange(s, a)
            if (r eq null) return null
            alternatives.addOne(r)
          }
        }
        union(alternatives)
      } else if (e.range.isDefined) {
        val el = sv.getElement(e.range.get.value).orNullFast
        if (el ne null) rangeOfElement(el, s.slot) else null
      } else null
    }

    /** The range of one `any_of` or `has_member` entry, or `null` if it can't be said in OWL. */
    private def expressionRange(s: SlotView, e: AnonymousSlotExpression): Filler = {
      var el: ElementView[?, ?] = null
      if (e.range.isDefined) el = sv.getElement(e.range.get.value).orNullFast
      if (el eq null) el = s.derivedRange.resolve.orNullFast
      if (el ne null) rangeOfElement(el, e) else null
    }

    /** A union of the alternatives, or the only alternative, since OWL 2 DL requires at least two
      * operands in a union. `null` if some are classes and some are data ranges.
      */
    private def union(alternatives: mutable.ArrayBuffer[Filler]): Filler =
      if (alternatives.length == 1) alternatives.head
      else if (alternatives.forall(_.isInstanceOf[DataRange])) {
        val operands = Vector.newBuilder[DataRange]
        alternatives.foreach(a => operands.addOne(a.asInstanceOf[DataRange]))
        DataRange.UnionOf(operands.result())
      } else if (alternatives.forall(_.isInstanceOf[ClassExpr])) {
        val operands = Vector.newBuilder[ClassExpr]
        alternatives.foreach(a => operands.addOne(a.asInstanceOf[ClassExpr]))
        ClassExpr.UnionOf(operands.result())
      } else null

    /** The range for an element, or `null` if it is `Any` or an IRI type. */
    private def rangeOfElement(el: ElementView[?, ?], constraints: SlotExpression): Filler =
      el match {
        case cv: ClassView => if (cv.isAny) null else ClassExpr.Named(cv.uriStr)
        case ev: EnumView => ClassExpr.Named(ev.uriStr)
        // Values with an implicit prefix are IRIs in RDF, as in the SHACL generator.
        case tv: TypeView if tv.isIri || constraints.implicitPrefix.isDefined => null
        case tv: TypeView =>
          val own = facets(
            constraints.minimumValue,
            constraints.maximumValue,
            constraints.minimumLength,
            constraints.maximumLength,
            LinkmlPattern.option(constraints.pattern),
            tv,
          )
          if (own.isEmpty) datatypeOf(tv)
          else {
            // OWL only allows facets on built-in datatypes, so repeat the type's own constraints
            // next to the slot's.
            val d = tv.derivedType
            val inherited = facets(
              d.minimumValue,
              d.maximumValue,
              d.minimumLength,
              d.maximumLength,
              tv.pattern,
              tv,
            )
            val all = Vector.newBuilder[(String, Literal)]
            all.addAll(own)
            inherited.foreach(f => if (!own.exists(_._1 == f._1)) all.addOne(f))
            DataRange.Restriction(baseDatatype(tv), all.result())
          }
        case _ => null
      }

    /** The data range of a type. A `LocalizedText` type, such as `langstring`, is
      * `rdf:PlainLiteral`, which has both plain strings and texts in a language.
      */
    private def datatypeOf(tv: TypeView): DataRange =
      if (tv.runtimeType eq LocalizedTextType) DataRange.Datatype(plainLiteral)
      else if (tv.isPrimitive) {
        if (tv.name == "date_or_datetime") {
          DataRange.UnionOf(
            Vector(
              DataRange.Datatype(XmlSchema.date.value),
              DataRange.Datatype(XmlSchema.dateTime.value),
            ),
          )
        } else DataRange.Datatype(tv.uriStr)
      } else {
        // A type without its own `uri` stands for the ancestor it inherits the `uri` from.
        val origin = tv.ancestorsWithSelf.find(_._type.typeUri.isDefined).orNullFast
        if ((origin ne null) && origin.isPrimitive && !hasFacets(tv)) datatypeOf(origin)
        else if (hasFacets(tv) && OwlRdfReader.isBuiltInDatatype(tv.uriStr)) {
          val d = tv.derivedType
          DataRange.Restriction(
            tv.uriStr,
            facets(d.minimumValue, d.maximumValue, d.minimumLength, d.maximumLength, tv.pattern, tv),
          )
        } else DataRange.Datatype(tv.uriStr)
      }

    /** Whether the type comes from LinkML's own schemas, such as `linkml:types`. */
    private def isLinkmlOwn(tv: TypeView): Boolean =
      tv.definingSchema.id.original.startsWith("https://w3id.org/linkml/")

    private def hasFacets(tv: TypeView): Boolean = {
      val d = tv.derivedType
      d.minimumValue.isDefined || d.maximumValue.isDefined || d.minimumLength.isDefined ||
      d.maximumLength.isDefined || d.pattern.isDefined
    }

    /** The datatype that a type's values have in RDF: an XSD datatype, or `rdf:PlainLiteral` for a
      * `LocalizedText` type, such as `langstring`.
      */
    private def baseDatatype(tv: TypeView): String = {
      val uri = tv.uriStr
      if (OwlRdfReader.isBuiltInDatatype(uri)) uri
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
          case _: LocalizedTextType.type => plainLiteral
          case _ => XmlSchema.string.value
        }
    }

    private def facets(
        min: Option[LinkmlAny],
        max: Option[LinkmlAny],
        minLength: Option[Int],
        maxLength: Option[Int],
        pattern: Option[LinkmlPattern],
        tv: TypeView,
    ): Seq[(String, Literal)] = {
      val datatype = Iri(baseDatatype(tv))
      val b = Vector.newBuilder[(String, Literal)]
      min.foreachFast(v =>
        b.addOne(XmlSchema.minInclusive.value -> Literal(v.value.strip, datatype)),
      )
      max.foreachFast(v =>
        b.addOne(XmlSchema.maxInclusive.value -> Literal(v.value.strip, datatype)),
      )
      minLength.foreachFast(n =>
        b.addOne(XmlSchema.minLength.value -> Literal(n.toString, XmlSchema.nonNegativeInteger)),
      )
      maxLength.foreachFast(n =>
        b.addOne(XmlSchema.maxLength.value -> Literal(n.toString, XmlSchema.nonNegativeInteger)),
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

    /** Whether the slot's range is a `LocalizedText` type, such as `langstring`. A text in several
      * languages is one literal per language, so such a slot is not limited to one value.
      */
    private def isText(s: SlotView): Boolean = s.derivedRange.resolve.foldFast(false) {
      case tv: TypeView => tv.runtimeType eq LocalizedTextType
      case _ => false
    }

    /** Whether each property is single-valued in every class that uses it. */
    private lazy val singleValued: collection.Map[String, Boolean] = {
      val b = mutable.HashMap.empty[String, Boolean]
      sv.classes.foreachEntry { (_, c) =>
        c.derivedAttributes.foreachEntry { (_, a) =>
          val iri = a.uriStr
          b.update(iri, !a.slot.multivalued && !isText(a) && b.getOrElse(iri, true))
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
        val own = if (sv.slotDefinitions.contains(s.slot.name)) rangeOf(s) else null
        val uses = rangesByProperty.getOrElse(s.uriStr, noRanges)
        if ((own ne null) || uses.exists(_ ne null)) {
          if (
            ((own eq null) || own.isInstanceOf[DataRange]) &&
            uses.forall(u => (u eq null) || u.isInstanceOf[DataRange])
          ) EntityKind.DataProperty
          else EntityKind.ObjectProperty
        } else if (s.parents.nonEmpty) {
          // Nothing to go on, so use the parent's kind.
          kindOf(s.parents.head)
        } else EntityKind.ObjectProperty
      }
    }

    /** The range of each use of each property, in every class, or `null` for a use without one. */
    private lazy val rangesByProperty: mutable.HashMap[String, mutable.ArrayBuffer[Filler]] = {
      val b = mutable.HashMap.empty[String, mutable.ArrayBuffer[Filler]]
      sv.classes.foreachEntry { (_, c) =>
        c.derivedAttributes.foreachEntry { (_, a) =>
          b.getOrElseUpdate(a.uriStr, mutable.ArrayBuffer.empty).addOne(rangeOf(a, c))
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
      equivalent.foreach(e => add(EquivalentProperties(Vector(iri, e))))
      val annotation = kind == EntityKind.AnnotationProperty
      // A slot without its own range gets its parent's through `rdfs:subPropertyOf`. An annotation
      // property only gets a range it states, since its values aren't the schema's data.
      val range =
        if (annotation) {
          val el =
            if (slot.range.isDefined) sv.getElement(slot.range.get.value).orNullFast else null
          if (el ne null) rangeOfElement(el, slot) else null
        } else if (
          slot.range.isDefined || slot.anyOf.nonEmpty || (slot.isA.isEmpty && slot.mixins.isEmpty)
        ) rangeOf(slotView)
        else null
      if (range ne null) add(Range(iri, range))
      slot.domain.foreachFast { d =>
        sv.classes.get(d.value).foreachFast(c => add(Domain(iri, ClassExpr.Named(c.uriStr))))
      }
      // `domain` holds one class, so a union of classes as the domain is kept in `domain_of`.
      val b = Vector.newBuilder[ClassExpr]
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
        if (
          !slot.multivalued && !isText(slotView) && singleValued.getOrElse(iri, true) &&
          !nonSimple(iri)
        ) {
          add(Characteristic(iri, PropertyCharacteristic.Functional))
        }
      }
    }

    /** Declares properties for attributes, which have no top-level slot. Their range and
      * cardinality are stated on their class.
      */
    private def addAttributes(cv: ClassView): Unit =
      cv.cls.attributes.foreachEntry { (name, attribute) =>
        val derived = cv.derivedAttributes(name)
        val iri = derived.uriStr
        if (!derived.slot.identifier && !declared(iri)) {
          val kind = kindOf(derived)
          add(Declaration(kind, iri))
          annotate(iri, attribute)
          if (kind != EntityKind.AnnotationProperty) {
            // If every use of the property has the same range, that becomes its range.
            val uses = rangesByProperty.getOrElse(iri, noRanges)
            if (uses.nonEmpty) {
              val range = uses.head
              if ((range ne null) && uses.forall(_ == range)) add(Range(iri, range))
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
      val valuesBuilder = Vector.newBuilder[String]
      ev.derivedValues.foreach { pvv =>
        val pv = pvv.pv
        val pvIri = pvv.uriStr
        options.permissibleValues match {
          case OwlPermissibleValueKind.Individual =>
            add(Declaration(EntityKind.NamedIndividual, pvIri))
            add(ClassAssertion(ClassExpr.Named(iri), pvIri))
          case OwlPermissibleValueKind.Class =>
            add(Declaration(EntityKind.Class, pvIri))
            add(SubClassOf(ClassExpr.Named(pvIri), ClassExpr.Named(iri)))
        }
        // The text goes in `skos:notation`. The importer falls back to the local name, so the
        // notation is only written when they differ. This keeps it off IRIs that are also
        // classes, which Schema.org has.
        if (OwlImportNames.localName(pvIri) != pv.text) {
          add(AnnotationAssertion(pvIri, Annotation.text(skosNotation, pv.text)))
        }
        annotate(pvIri, pv)
        valuesBuilder.addOne(pvIri)
      }
      val values = valuesBuilder.result()
      if (values.nonEmpty)
        options.permissibleValues match {
          case OwlPermissibleValueKind.Individual =>
            add(EquivalentClasses(Vector(ClassExpr.Named(iri), ClassExpr.OneOf(values))))
          case OwlPermissibleValueKind.Class =>
            val union = ClassExpr.UnionOf(values.map(ClassExpr.Named(_)))
            add(EquivalentClasses(Vector(ClassExpr.Named(iri), union)))
        }
    }

    // Types

    /** Declares a type whose `uri` OWL doesn't know, defined by its constraints if it has any. A
      * type over a built-in datatype needs no declaration.
      */
    private def addType(tv: TypeView): Unit =
      datatypeOf(tv) match {
        case d: DataRange.Datatype => addType(tv, d.iri)
        case _ =>
      }

    private def addType(tv: TypeView, iri: String): Unit =
      if (!OwlRdfReader.isBuiltInDatatype(iri)) {
        add(Declaration(EntityKind.Datatype, iri))
        annotate(iri, tv)
        if (hasFacets(tv)) {
          val d = tv.derivedType
          val restriction = DataRange.Restriction(
            baseDatatype(tv),
            facets(
              d.minimumValue,
              d.maximumValue,
              d.minimumLength,
              d.maximumLength,
              tv.pattern,
              tv,
            ),
          )
          add(DatatypeDefinition(iri, restriction))
        }
      } else if (!Metadata.of(tv._type).isEmpty && typesByUri.getOrElse(iri, 0) == 1) {
        // No declaration needed, but keep what the schema says about the datatype.
        annotate(iri, tv)
      }

    /** The number of types of the schema, not LinkML's own, with each `uri`. */
    private lazy val typesByUri: mutable.HashMap[String, Int] = {
      val counts = mutable.HashMap.empty[String, Int]
      sv.types.valuesIterator.foreach { t =>
        if (!isLinkmlOwn(t)) counts.update(t.uriStr, counts.getOrElse(t.uriStr, 0) + 1)
      }
      counts
    }
  }
}
