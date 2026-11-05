package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.options.{
  NameStyle,
  OwlImportConfig,
  ImportResult,
  OwlImporterOptions,
  RdfFormat,
  SomeValuesFrom,
}
import eu.neverblink.linkml.generator.SchemaImporter
import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.owl.ClassExpr.Bound
import eu.neverblink.linkml.generator.owl.OwlAnnotationReader.Documentation
import eu.neverblink.linkml.generator.owl.OwlAxiomIndex.*
import eu.neverblink.linkml.generator.owl.config.OwlImportConfigs.{
  classStyle,
  datatypeMap,
  importMap,
  permissibleValueStyle,
  prefixMap,
  slotStyle,
  someValuesFromStyle,
}
import eu.neverblink.linkml.generator.owl.OwlImportNames.*
import eu.neverblink.linkml.metamodel.{Annotation as _, *}
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.{
  Case,
  FileSystemImporter,
  Importer,
  LinkmlPattern,
  MapImporter,
  SchemaView,
}
import eu.neverblink.linkml.validation.{SchemaImportError, SchemaParseError}

import java.io.InputStream

import scala.collection.immutable.VectorMap
import scala.collection.mutable
import scala.util.control.NonFatal

/** Reads an OWL ontology (Turtle or N-Triples) and turns it into a LinkML schema.
  *
  * It reverses [[OwlGenerator]]. Use [[OwlImportConfig]] to adjust it for ontologies written in
  * other styles. In short:
  *   - Classes become classes. One named parent becomes `is_a`, preferring one in the class's own
  *     namespace. The rest become `mixins`.
  *   - Object and data properties become top-level slots. `rdfs:domain` adds the slot to a class.
  *     Restrictions add it too and narrow it with `slot_usage`.
  *   - A class defined by `owl:oneOf`, or made of individuals, becomes an enum.
  *   - Datatypes become types, and datatype restrictions become their constraints.
  *   - Annotations become documentation metaslots, see [[config.OwlImportConfigs.defaultMetadata]].
  *
  * Anything LinkML can't express is listed in [[OwlImporter.Result.warnings]]. If it is about a
  * class, it also goes in that class's `notes`.
  *
  * @param schemas
  *   Loads the LinkML schemas that the config maps `owl:imports` to, unless the options have them.
  *   Their terms are then referenced by name instead of being copied into the schema.
  * @param base
  *   Location of the output schema, used to resolve relative `imports`.
  */
class OwlImporter(schemas: Importer = FileSystemImporter, base: String = "")
    extends SchemaImporter[OwlImporterOptions] {
  import OwlImporter.*

  override protected def defaultOptions: OwlImporterOptions = OwlImporterOptions()

  override def importSchema(
      in: InputStream,
      options: OwlImporterOptions = OwlImporterOptions(),
  ): SchemaDefinition =
    importWithWarnings(in, options).schema

  def importWithWarnings(
      in: InputStream,
      options: OwlImporterOptions = OwlImporterOptions(),
  ): Result = {
    val sink = CollectingRdfSink()
    options.inputFormat match {
      case RdfFormat.Ttl => TurtleParser.parse(in, sink)
      case RdfFormat.Nt => NTriplesParser.parse(in, sink)
    }
    val read = OwlRdfReader.read(sink.triples)
    // Document prefixes override those from `sh:declare`. LinkML has no empty prefix, so skip it.
    val prefixes = Vector.newBuilder[(String, String)]
    prefixes.addAll(read.ontology.prefixes)
    sink.namespaces.foreach(n => if (n.prefix.nonEmpty) prefixes.addOne(n.prefix -> n.name))
    importOntology(read.ontology.copy(prefixes = prefixes.result()), options, read.unparsed)
  }

  override def importResult(
      in: InputStream,
      options: OwlImporterOptions = OwlImporterOptions(),
  ): ImportResult = {
    val result = importWithWarnings(in, options)
    ImportResult(
      serializeSchema(result.schema, options.format, options.fullUris),
      result.warnings,
    )
  }

  def importTriples(
      triples: IndexedSeq[Triple],
      config: OwlImportConfig = OwlImportConfig(),
  ): Result =
    importTriples(triples, OwlImporterOptions(config = Some(config)))

  def importTriples(triples: IndexedSeq[Triple], options: OwlImporterOptions): Result = {
    val read = OwlRdfReader.read(triples)
    importOntology(read.ontology, options, read.unparsed)
  }

  def importOntology(
      ontology: Ontology,
      config: OwlImportConfig = OwlImportConfig(),
      unparsed: Seq[Triple] = Nil,
  ): Result = importOntology(ontology, OwlImporterOptions(config = Some(config)), unparsed)

  def importOntology(
      ontology: Ontology,
      options: OwlImporterOptions,
      unparsed: Seq[Triple],
  ): Result = {
    val importer =
      if (options.schemas.isEmpty) schemas
      else MapImporter.from(options.schemas.valuesIterator.map(f => f.path -> f.content))
    Conversion(ontology, options, importer, base, unparsed).result
  }
}

object OwlImporter {

  /** `warnings` has one line per kind of thing that could not be imported. */
  final case class Result(schema: SchemaDefinition, warnings: Seq[String])

  /** Properties in these namespaces are documentation, even when the ontology declares them. */
  private val metadataVocabularies: Set[String] = Set(
    "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "http://www.w3.org/2000/01/rdf-schema#",
    "http://www.w3.org/2002/07/owl#",
    "http://www.w3.org/2004/02/skos/core#",
    "http://purl.org/dc/terms/",
    "http://purl.org/dc/elements/1.1/",
    "http://purl.org/vocab/vann/",
    "http://purl.org/pav/",
    "http://www.w3.org/ns/shacl#",
    "http://creativecommons.org/ns#",
    "http://www.geneontology.org/formats/oboInOwl#",
  )

  private val owlNs = "http://www.w3.org/2002/07/owl#"
  private val vann = "http://purl.org/vocab/vann/"

  private val notationProperty = "http://www.w3.org/2004/02/skos/core#notation"

  private val rangeIncludes =
    Set("http://schema.org/rangeIncludes", "https://schema.org/rangeIncludes")

  /** The datatype facets LinkML can express, on a type or in a range. */
  private val typeFacets: Set[String] = Set(
    XmlSchema.minInclusive.value,
    XmlSchema.maxInclusive.value,
    XmlSchema.minLength.value,
    XmlSchema.maxLength.value,
    XmlSchema.pattern.value,
  )

  private val regexMeta = "\\^$.|?*+()[]{}"

  /** A LinkML pattern that matches exactly one of the values. */
  private def alternationOf(values: Seq[Literal | LanguageLiteral]): String = {
    val sb = new java.lang.StringBuilder("^(")
    var first = true
    values.foreach { v =>
      if (!first) sb.append('|')
      first = false
      val value = literalValue(v)
      var i = 0
      while (i < value.length) {
        val c = value.charAt(i)
        if (regexMeta.indexOf(c) >= 0) sb.append('\\')
        sb.append(c)
        i += 1
      }
    }
    sb.append(")$").toString
  }

  private def literalValue(v: Literal | LanguageLiteral): String = v match {
    case l: Literal => l.value
    case l: LanguageLiteral => l.value
  }

  private def literalType(v: Literal | LanguageLiteral): String = v match {
    case l: Literal => l.datatype.value
    case _: LanguageLiteral => Rdf.langString.value
  }

  private val builtInTypes: Map[String, String] = Map(
    XmlSchema.string.value -> "string",
    XmlSchema.integer.value -> "integer",
    XmlSchema.boolean.value -> "boolean",
    XmlSchema.float.value -> "float",
    XmlSchema.double.value -> "double",
    XmlSchema.decimal.value -> "decimal",
    XmlSchema.time.value -> "time",
    XmlSchema.date.value -> "date",
    XmlSchema.dateTime.value -> "datetime",
    Rdf.langString.value -> "langstring",
    Rdf.get("PlainLiteral").value -> "langstring",
    XmlSchema.get("NCName").value -> "ncname",
  )

  /** Other XSD datatypes become types of their own, based on these LinkML types. */
  private val derivedTypes: Map[String, String] = {
    val types = Map.newBuilder[String, String]
    Array(
      "int",
      "long",
      "short",
      "byte",
      "nonNegativeInteger",
      "positiveInteger",
      "nonPositiveInteger",
      "negativeInteger",
      "unsignedLong",
      "unsignedInt",
      "unsignedShort",
      "unsignedByte",
    ).foreach(local => types.addOne(XmlSchema.get(local).value -> "integer"))
    Array(
      "normalizedString",
      "token",
      "language",
      "Name",
      "NMTOKEN",
      "duration",
      "dayTimeDuration",
      "yearMonthDuration",
      "gYear",
      "gYearMonth",
      "gMonth",
      "gDay",
      "gMonthDay",
      "hexBinary",
      "base64Binary",
      // Not `uri`: a LinkML `uri` is an IRI in RDF, not an `xsd:anyURI` literal.
      "anyURI",
    ).foreach(local => types.addOne(XmlSchema.get(local).value -> "string"))
    types.addOne(XmlSchema.get("dateTimeStamp").value -> "datetime")
    types.addOne(Rdf.get("XMLLiteral").value -> "string")
    types.addOne(Rdf.get("HTML").value -> "string")
    types.addOne(Rdf.get("JSON").value -> "string")
    types.addOne(Owl.get("real").value -> "decimal")
    types.addOne(Owl.get("rational").value -> "decimal")
    types.result()
  }

  private def isStandardVocabulary(iri: String): Boolean =
    iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#") ||
      iri.startsWith("http://www.w3.org/2000/01/rdf-schema#") || iri.startsWith(owlNs)

  /** The IRI without a trailing `#` and then a trailing `/`. */
  private def withoutSeparator(iri: String): String = {
    var end = iri.length
    if (end > 0 && iri.charAt(end - 1) == '#') end -= 1
    if (end > 0 && iri.charAt(end - 1) == '/') end -= 1
    iri.substring(0, end)
  }

  /** The part before the first `:`, or the whole text if it has none. */
  private def beforeColon(text: String): String = {
    val i = text.indexOf(':')
    if (i < 0) text else text.substring(0, i)
  }

  /** Stands for a missing or unreadable length facet. */
  private val noLength: Int = Int.MinValue

  /** The slot fields that an OWL range maps to. */
  private final case class SlotRange(
      range: Option[String] = None,
      anyOf: Seq[String] = Nil,
      minimumValue: Option[String] = None,
      maximumValue: Option[String] = None,
      minimumLength: Option[Int] = None,
      maximumLength: Option[Int] = None,
      pattern: Option[String] = None,
      data: Boolean = false,
  ) {

    /** True if this is a plain class, enum or type, so it can go in an `any_of`. */
    def isOneElement: Boolean =
      range.isDefined && anyOf.isEmpty && minimumValue.isEmpty && maximumValue.isEmpty &&
        minimumLength.isEmpty && maximumLength.isEmpty && pattern.isEmpty
  }

  /** A mapping with the key it is sorted by. */
  private final class KeyedMapping(val key: String, val mapping: UriOrCurie)

  private val byKey: java.util.Comparator[KeyedMapping] = (a, b) => a.key.compareTo(b.key)

  /** Whether a property is used as a data property somewhere. */
  private final class PropertyUse {
    var data: Boolean = false
  }

  /** A property's kind, and its place in the order of properties. */
  private final class PropertyInfo(var kind: EntityKind) {
    var order: Int = Int.MaxValue
  }

  /** The members of an enum by its class IRI, and whether the members are classes. */
  private type EnumMembers = (members: collection.Seq[String], classes: Boolean)

  /** The `is_a` parent of an element, or `null` if it has none, and its mixins, by name. */
  private type Parents = (isA: String, mixins: collection.Seq[String])

  private final class Conversion(
      ontology: Ontology,
      options: OwlImporterOptions,
      schemas: Importer,
      base: String,
      unparsed: Seq[Triple],
  ) {
    private val config = {
      val config = options.config.getOrElse(OwlImportConfig())
      if (options.schemaId.isDefined) config.copy(schemaId = options.schemaId) else config
    }

    /** Warning details by kind. */
    private val warnings = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]

    /** The kinds of [[warnings]], in the order of their first warning. */
    private val warningKinds = mutable.ArrayBuffer.empty[String]

    /** Warnings are grouped by kind to keep the report short. */
    private def warn(kind: String, detail: String): Unit = {
      var details = warnings.getOrElse(kind, null)
      if (details eq null) {
        details = mutable.ArrayBuffer.empty
        warnings.update(kind, details)
        warningKinds.addOne(kind)
      }
      details.addOne(detail)
    }

    private val index = OwlAxiomIndex(ontology.axioms)
    import index.*

    private val wellKnownClasses = Set(owlNs + "Thing", owlNs + "Nothing", Rdfs.Resource.value)

    private def isDatatype(iri: String): Boolean =
      isDeclared(iri, EntityKind.Datatype) || OwlRdfReader.isBuiltInDatatype(iri)

    /** Entries of an index, or an empty buffer if there are none. */
    private def entriesOf[A](
        index: mutable.HashMap[String, mutable.ArrayBuffer[A]],
        key: String,
    ): mutable.ArrayBuffer[A] = {
      val entries = index.getOrElse(key, null)
      if (entries ne null) entries else mutable.ArrayBuffer.empty
    }

    /** Classes that are declared or used. Declarations of RDF, RDFS and OWL terms are ignored
      * (FOAF, for example, declares `rdfs:Class`).
      */
    private val allClasses: mutable.ArrayBuffer[String] = {
      val out = mutable.ArrayBuffer.empty[String]
      val seen = mutable.HashSet.empty[String]
      def add(c: String): Unit =
        if (seen.add(c) && !wellKnownClasses(c) && !isDatatype(c)) out.addOne(c)
      axioms.foreach {
        case d: Declaration =>
          if (d.kind == EntityKind.Class && !isStandardVocabulary(d.iri)) add(d.iri)
        case _ =>
      }
      expressions.foreach(e => foreachClassIn(e)(add))
      out
    }

    /** The ontology IRI without a trailing `#` or `/`, or `null` if it has none. */
    private val ontologyNamespace: String =
      if (ontology.iri.isDefined) withoutSeparator(ontology.iri.get) else null

    /** True if the namespace is the ontology IRI, ignoring a trailing `#` or `/`. */
    private def isOntologyNamespace(namespace: String): Boolean =
      (ontologyNamespace ne null) && withoutSeparator(namespace) == ontologyNamespace

    /** Each property's kind, taken from its declaration or else from how it is used, and its place
      * in [[properties]].
      */
    private val propertyKinds = mutable.HashMap.empty[String, PropertyInfo]

    /** The kind of a property, or `null` if it is not one. */
    private def kindOf(p: String): EntityKind = {
      val info = propertyKinds.getOrElse(p, null)
      if (info ne null) info.kind else null
    }

    /** The properties in [[propertyKinds]], in order. */
    private val properties = mutable.ArrayBuffer.empty[String]

    {
      val order = mutable.ArrayBuffer.empty[String]
      def put(p: String, kind: EntityKind): Unit = {
        val info = propertyKinds.getOrElse(p, null)
        if (info ne null) info.kind = kind
        else {
          propertyKinds.update(p, new PropertyInfo(kind))
          order.addOne(p)
        }
      }
      axioms.foreach {
        case d: Declaration =>
          if (d.kind == EntityKind.ObjectProperty || d.kind == EntityKind.DataProperty) {
            put(d.iri, d.kind)
          }
        case _ =>
      }
      // Whether each used property is used as a data property somewhere.
      val used = mutable.HashMap.empty[String, PropertyUse]
      def use(p: String, data: Boolean): Unit =
        used.getOrElseUpdate(p, new PropertyUse).data |= data
      expressions.foreach(e => foreachPropertyIn(e)(use))
      axioms.foreach {
        case a: Range => use(a.property, a.range.isInstanceOf[DataRange])
        case a: Domain => use(a.property, false)
        case a: Characteristic => use(a.property, false)
        case a: InverseProperties => use(a.first, false)
        // An undeclared subproperty, like Hydra's `writeable`, has its parent's kind.
        case a: SubPropertyOf =>
          use(a.sub, kindOf(a.sup) == EntityKind.DataProperty)
        case _ =>
      }
      // Undeclared properties are added in the iteration order of an immutable map built from
      // `used`, as `groupMap` builds it. That order doesn't depend on the order of the axioms, so
      // the OWL generated from the schema gives the same schema.
      var byUse = Map.empty[String, PropertyUse]
      used.foreachEntry((p, u) => byUse = byUse.updated(p, u))
      // Hydra gives its datatype `Rfc6570Template` a range, which doesn't make it a property.
      byUse.foreachEntry { (p, u) =>
        if (
          !propertyKinds.contains(p) && !isDeclared(p, EntityKind.AnnotationProperty) &&
          !isDeclared(p, EntityKind.Datatype)
        ) put(p, if (u.data) EntityKind.DataProperty else EntityKind.ObjectProperty)
      }
      // An undeclared parent property gets its child's kind, so the child can keep it as a parent.
      // Skip parents used as annotations, or they would end up with two kinds.
      val annotationProperties = mutable.HashSet.empty[String]
      annotationsOf.valuesIterator.foreach(_.foreach(a => annotationProperties.addOne(a.property)))
      ontology.annotations.foreach(a => annotationProperties.addOne(a.property))
      val added = mutable.ArrayBuffer.empty[(property: String, kind: EntityKind)]
      while ({
        added.clear()
        axioms.foreach {
          case a: SubPropertyOf =>
            val sup = a.sup
            if (!propertyKinds.contains(sup)) {
              val kind = kindOf(a.sub)
              if (
                (kind ne null) && kind != EntityKind.AnnotationProperty &&
                !isDeclared(sup, EntityKind.AnnotationProperty) && !annotationProperties(sup)
              ) added.addOne((property = sup, kind = kind))
            }
          case _ =>
        }
        added.foreach(e => if (!propertyKinds.contains(e.property)) put(e.property, e.kind))
        added.nonEmpty
      }) ()
      // Keep the ontology's own annotation properties only if it says something about them. A bare
      // declaration is usually just there for OWL 2 DL.
      val described = mutable.HashSet.empty[String]
      described.addAll(annotationsOf.keysIterator)
      axioms.foreach {
        case a: SubPropertyOf => described.addOne(a.sub)
        case a: Domain => described.addOne(a.property)
        case a: Range => described.addOne(a.property)
        case _ =>
      }
      // Skip metadata vocabulary properties, unless the ontology is that vocabulary (e.g. SKOS).
      def ownAnnotationProperty(iri: String) =
        !propertyKinds.contains(iri) && described(iri) && {
          val ns = namespaceOf(iri)
          !metadataVocabularies.contains(ns) || isOntologyNamespace(ns)
        }
      axioms.foreach {
        case d: Declaration =>
          if (d.kind == EntityKind.AnnotationProperty && ownAnnotationProperty(d.iri)) {
            put(d.iri, EntityKind.AnnotationProperty)
          }
        case _ =>
      }
      // RDF, RDFS and OWL properties are defined by their specs, so they never become slots.
      order.foreach { p =>
        if (isStandardVocabulary(p)) propertyKinds.remove(p)
        else {
          propertyKinds(p).order = properties.length
          properties.addOne(p)
        }
      }
    }

    // Enums

    /** Enums by class IRI, with their members and whether the members are classes. */
    private val enumMembers = mutable.HashMap.empty[String, EnumMembers]

    /** The classes in [[enumMembers]], in order. */
    private val enumClasses = mutable.ArrayBuffer.empty[String]

    private def addEnum(c: String, members: collection.Seq[String], classes: Boolean): Unit = {
      if (!enumMembers.contains(c)) enumClasses.addOne(c)
      enumMembers.update(c, (members = members, classes = classes))
    }

    {
      allClasses.foreach { c =>
        val entry = entriesOf(equivalents, c).find(_.other.isInstanceOf[ClassExpr.OneOf])
        if (entry.isDefined) {
          take(entry.get.axiom)
          addEnum(c, entry.get.other.asInstanceOf[ClassExpr.OneOf].individuals, classes = false)
        }
      }
      // A union of plain subclasses becomes an enum whose values are those classes.
      allClasses.foreach { c =>
        if (!enumMembers.contains(c)) {
          val entry = entriesOf(equivalents, c).find { e =>
            e.other match {
              case u: ClassExpr.UnionOf =>
                u.operands.forall {
                  case n: ClassExpr.Named => isPlainSubclass(n.iri, c)
                  case _ => false
                }
              case _ => false
            }
          }
          if (entry.isDefined) {
            take(entry.get.axiom)
            val members = mutable.ArrayBuffer.empty[String]
            entry.get.other.asInstanceOf[ClassExpr.UnionOf].operands.foreach {
              case n: ClassExpr.Named => members.addOne(n.iri)
              case _ =>
            }
            members.foreach(m => subClassOf(m).foreach(e => take(e.axiom)))
            addEnum(c, members, classes = true)
          }
        }
      }
      if (config.enumsFromIndividuals) {
        val usedAsRange = mutable.HashSet.empty[String]
        def useAsRange(f: Filler): Unit = f match {
          case n: ClassExpr.Named => usedAsRange.addOne(n.iri)
          case _ =>
        }
        ranges.valuesIterator.foreach(_.foreach(e => useAsRange(e.range)))
        expressions.foreach {
          case e: ClassExpr.AllValuesFrom => useAsRange(e.filler)
          case e: ClassExpr.SomeValuesFrom => useAsRange(e.filler)
          case _ =>
        }
        // Schema.org gives ranges with `schema:rangeIncludes`.
        annotationsOf.valuesIterator.foreach(_.foreach { a =>
          if (rangeIncludes(a.property)) {
            a.value match {
              case i: Iri => usedAsRange.addOne(i.value)
              case _ =>
            }
          }
        })
        val members = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
        classAssertions.foreach { a =>
          a.cls match {
            case n: ClassExpr.Named =>
              members.getOrElseUpdate(n.iri, mutable.ArrayBuffer.empty).addOne(a.individual)
            case _ =>
          }
        }
        // Subclasses of a range count too. E.g. Schema.org's `USNonprofitType` is a subclass of
        // `NonprofitType`, which is used as a range.
        def hasNamedAncestorUsedAsRange(c: String): Boolean = {
          val seen = mutable.HashSet.empty[String]
          val stack = mutable.ArrayBuffer(c)
          var found = false
          while (!found && stack.nonEmpty) {
            val sups = entriesOf(subClassOf, stack.remove(stack.length - 1))
            var i = 0
            while (!found && i < sups.length) {
              sups(i).sup match {
                case n: ClassExpr.Named =>
                  if (seen.add(n.iri)) {
                    found = usedAsRange(n.iri)
                    stack.addOne(n.iri)
                  }
                case _ =>
              }
              i += 1
            }
          }
          found
        }
        val classes = members.keysIterator.toArray
        java.util.Arrays.sort(classes, Ordering.String)
        classes.foreach { c =>
          if (
            !enumMembers.contains(c) &&
            (usedAsRange(c) || hasNamedAncestorUsedAsRange(c)) && isOnlyASet(c)
          ) {
            val individuals = mutable.ArrayBuffer.empty[String]
            val seen = mutable.HashSet.empty[String]
            members(c).foreach(i => if (seen.add(i)) individuals.addOne(i))
            addEnum(c, individuals, classes = false)
          }
        }
      }
    }

    /** True if the only thing said about `m` is that it is a subclass of `c`. */
    private def isPlainSubclass(m: String, c: String): Boolean = {
      val sups = subClassOf.getOrElse(m, null)
      (sups ne null) && sups.length == 1 && (sups(0).sup match {
        case n: ClassExpr.Named => n.iri == c
        case _ => false
      }) && !namedSubclasses.contains(m) && !domainClasses(m) && !inEquivalence(m)
    }

    /** True if nothing is said about the class's members, so it can become an enum. Named parents
      * are allowed, and so are equivalent named classes, which just become mappings.
      */
    private def isOnlyASet(c: String): Boolean =
      entriesOf(subClassOf, c).forall(_.sup.isInstanceOf[ClassExpr.Named]) &&
        !namedSubclasses.contains(c) && !domainClasses(c) && !inExpressionEquivalence(c)

    /** Individuals that are values of an enum. */
    private val enumIndividuals = mutable.HashSet.empty[String]

    /** Classes that are values of an enum. */
    private val enumClassMembers = mutable.HashSet.empty[String]

    enumMembers.valuesIterator.foreach { m =>
      (if (m.classes) enumClassMembers else enumIndividuals).addAll(m.members)
    }

    /** If the IRI is also a permissible value (as in Schema.org and D3FEND), its `skos:notation`
      * belongs to the value, so it is left out here.
      */
    private def termDocumentation(iri: String): Documentation =
      if (!enumIndividuals(iri)) reader.documentation(iri)
      else {
        val annotations = mutable.ArrayBuffer.empty[Annotation]
        entriesOf(annotationsOf, iri)
          .foreach(a => if (a.property != notationProperty) annotations.addOne(a))
        reader.documentation(annotations)
      }

    private val classes: mutable.ArrayBuffer[String] = {
      val out = mutable.ArrayBuffer.empty[String]
      allClasses.foreach(c =>
        if (!enumMembers.contains(c) && !enumClassMembers.contains(c)) out.addOne(c),
      )
      out
    }

    // Datatypes

    /** The ontology's and the config's prefixes, as prefix and namespace. */
    private val configPrefixes: mutable.ArrayBuffer[(String, String)] = {
      val prefixes = mutable.ArrayBuffer.from(ontology.prefixes)
      config.prefixMap.foreach(prefixes.addOne)
      prefixes
    }

    private val configuredTypes: mutable.HashMap[String, String] = {
      val types = mutable.HashMap.empty[String, String]
      config.datatypeMap.foreachEntry((k, v) => types.update(expand(k, configPrefixes), v))
      types
    }

    /** Datatypes that are declared, defined or used in a range, minus those LinkML already has. */
    private val datatypes: mutable.ArrayBuffer[String] = {
      val out = mutable.ArrayBuffer.empty[String]
      val seen = mutable.HashSet.empty[String]
      def add(d: String): Unit =
        // ODRL puts classes in data ranges. Those stay classes.
        if (
          seen.add(d) && d != Rdfs.Literal.value && !builtInTypes.contains(d) &&
          !configuredTypes.contains(d) && !isDeclared(d, EntityKind.Class)
        ) out.addOne(d)
      axioms.foreach {
        case d: Declaration => if (d.kind == EntityKind.Datatype) add(d.iri)
        case _ =>
      }
      axioms.foreach {
        case a: Range => foreachDatatypeIn(a.range)(add)
        case a: DatatypeDefinition =>
          add(a.datatype)
          foreachDatatypeIn(a.range)(add)
        case _ =>
      }
      expressions.foreach(e => foreachDatatypeIn(e)(add))
      out
    }

    // Every datatype is a set of literals, so `rdfs:subClassOf rdfs:Literal` says nothing.
    subClassOf.foreachEntry { (d, sups) =>
      if (isDatatype(d)) {
        sups.foreach { e =>
          e.sup match {
            case n: ClassExpr.Named => if (n.iri == Rdfs.Literal.value) take(e.axiom)
            case _ =>
          }
        }
      }
    }

    /** The parent datatype of a datatype, from `rdfs:subClassOf`, or `null` if it has none. */
    private def parentDatatype(iri: String): String = {
      val sups = entriesOf(subClassOf, iri)
      var parent: String = null
      var i = 0
      while ((parent eq null) && i < sups.length) {
        val e = sups(i)
        e.sup match {
          case n: ClassExpr.Named =>
            if (n.iri != Rdfs.Literal.value && isDatatype(n.iri)) {
              take(e.axiom)
              parent = n.iri
            }
          case _ =>
        }
        i += 1
      }
      parent
    }

    // Prefixes and names

    private val naming = {
      val ownTerms = mutable.ArrayBuffer.empty[String]
      def own(t: String): Unit = if (declared.contains(t)) ownTerms.addOne(t)
      classes.foreach(own)
      enumClasses.foreach(own)
      properties.foreach(own)
      val allTerms = mutable.ArrayBuffer.empty[String]
      allTerms.addAll(allClasses)
      allTerms.addAll(properties)
      allTerms.addAll(datatypes)
      enumClasses.foreach(e => allTerms.addAll(enumMembers(e).members))
      annotationsOf.valuesIterator.foreach(_.foreach(a => allTerms.addOne(a.property)))
      ontology.annotations.foreach(a => allTerms.addOne(a.property))
      OwlImportNames(ontology, config, ownTerms, allTerms, warn)
    }
    import naming.{curie, defaultNamespace, ensurePrefix, explicitIri, names, uri}

    /** The LinkML schemas that `owl:imports` map to, for the schema's `imports`, sorted. */
    private val importRefs: Array[String] = {
      val importMap = config.importMap
      val refs = mutable.HashSet.empty[String]
      ontology.imports.foreach { i =>
        val ref = importMap.getOrElse(i, null)
        if (ref ne null) refs.addOne(ref)
        else warn("Import that is not mapped to a LinkML schema (see `imports` in the config)", i)
      }
      val sorted = refs.toArray
      java.util.Arrays.sort(sorted, Ordering.String)
      sorted
    }

    /** An import from the config, with a CURIE expanded as if the schema was loaded: with the
      * config's prefixes, the prefixes that the schema can declare, and those of the metamodel.
      */
    private def importOf(ref: String): UriOrCurie = UriOrCurie.expanded(ref, importPrefixes)

    private lazy val importPrefixes: PrefixMap = {
      val overrides = Vector.newBuilder[(String, String)]
      overrides.addAll(config.prefixMap)
      overrides.addAll(naming.all)
      PrefixMap(overrides = overrides.result(), defaults = Prefixes.map)
    }

    /** The mappings of both sequences without duplicates, in the order of their CURIEs. */
    private def sorted(
        mappings: collection.Seq[UriOrCurie],
        more: collection.Seq[UriOrCurie],
    ): Seq[UriOrCurie] = {
      val seen = mutable.HashSet.empty[String]
      val items = mutable.ArrayBuffer.empty[KeyedMapping]
      def add(m: UriOrCurie): Unit =
        if (seen.add(m.original)) items.addOne(KeyedMapping(curie(m.original, record = false), m))
      mappings.foreach(add)
      more.foreach(add)
      val array = items.toArray
      // A stable sort, so mappings with the same CURIE keep their order.
      java.util.Arrays.sort(array, byKey)
      val out = Vector.newBuilder[UriOrCurie]
      array.foreach(e => out.addOne(e.mapping))
      out.result()
    }

    /** Slot ranges from the imported schemas, by slot IRI. */
    private val importedRanges = mutable.HashMap.empty[String, SlotRange]

    /** Slots of each class in the imported schemas, by class IRI. */
    private val importedSlots = mutable.HashMap.empty[String, mutable.HashSet[String]]

    /** Terms defined in the imported schemas, by IRI, with their kind and name. The schema refers
      * to them by that name instead of defining its own.
      */
    private val imported = mutable.HashMap.empty[String, (group: NameGroup, name: String)]

    importRefs.foreach { ref =>
      val reason = addTermsOf(ref)
      if (reason ne null) {
        warn(
          "Imported LinkML schema that could not be read, so its terms are copied in",
          s"$ref: $reason",
        )
      }
    }

    /** Adds the terms of an imported schema to [[imported]], or returns why it can't be read. */
    private def addTermsOf(ref: String): String = {
      val prefixes = VectorMap.newBuilder[String, Prefix]
      config.prefixMap.foreachEntry((p, ns) => prefixes.addOne(p -> Prefix(p, Uri(ns))))
      val root = SchemaDefinition(
        id = Uri("urn:linkml-scala:owl-import"),
        name = "owl_import",
        prefixes = prefixes.result(),
        imports = Seq(importOf(ref)),
      )
      try
        SchemaView.loadImports(root, base, schemas).fold(
          {
            case e: SchemaImportError => e.reason
            case e: SchemaParseError => e.parserMessage
          },
          loaded => {
            val sv = SchemaView(root +: loaded)
            def own(el: eu.neverblink.linkml.schemaview.ElementView[?, ?]) =
              !el.definingSchema.id.original.startsWith("https://w3id.org/linkml/")
            sv.slotDefinitions.valuesIterator.foreach { s =>
              if (own(s)) {
                val it = s.ancestorsWithSelf.iterator
                var found = false
                while (!found && it.hasNext) {
                  val e = it.next().slot
                  if (e.range.isDefined || e.anyOf.nonEmpty) {
                    found = true
                    val anyOf = Vector.newBuilder[String]
                    e.anyOf.foreach(x => if (x.range.isDefined) anyOf.addOne(x.range.get.value))
                    importedRanges.update(
                      s.uriStr,
                      SlotRange(range = e.range.map(_.value), anyOf = anyOf.result()),
                    )
                  }
                }
              }
            }
            sv.classes.valuesIterator.foreach { c =>
              if (own(c)) {
                val slots = mutable.HashSet.empty[String]
                c.derivedAttributes.valuesIterator.foreach(a => slots.addOne(a.uriStr))
                importedSlots.update(c.uriStr, slots)
              }
            }
            sv.classes.valuesIterator.foreach { c =>
              if (own(c)) imported.update(c.uriStr, (group = NameGroup.Class, name = c.name))
            }
            sv.enums.valuesIterator.foreach { e =>
              if (own(e)) imported.update(e.uriStr, (group = NameGroup.Class, name = e.name))
            }
            sv.types.valuesIterator.foreach { t =>
              if (own(t)) imported.update(t.uriStr, (group = NameGroup.Type, name = t.name))
            }
            sv.slotDefinitions.valuesIterator.foreach { s =>
              if (own(s)) imported.update(s.uriStr, (group = NameGroup.Slot, name = s.name))
            }
            null
          },
        )
      catch { case e: Throwable if NonFatal(e) => String.valueOf(e.getMessage) }
    }

    imported.foreachEntry((iri, term) => naming.reserve(iri, term.name, term.group))

    {
      val classesAndEnums = mutable.ArrayBuffer.from(classes)
      classesAndEnums.addAll(enumClasses)
      naming.name(classesAndEnums, NameGroup.Class, config.classStyle)
      naming.name(properties, NameGroup.Slot, config.slotStyle)
      naming.name(datatypes, NameGroup.Type, NameStyle.Keep)
    }

    private val reader = OwlAnnotationReader(annotationsOf, ontology.annotations, config, naming)
    import reader.documentation

    // Types

    private lazy val types: mutable.ArrayBuffer[TypeDefinition] = {
      val out = mutable.ArrayBuffer.empty[TypeDefinition]
      datatypes.foreach(iri => if (!imported.contains(iri)) out.addOne(typeOf(iri)))
      out
    }

    private def typeOf(iri: String): TypeDefinition = {
      val name = names(iri)
      val doc = documentation(iri)
      ensurePrefix(iri)
      var typeof: String = null
      var facets: Seq[(String, Literal)] = Nil
      var valuePattern: String = null
      val definition = datatypeDefinitions.getOrElse(iri, null)
      if (definition eq null) {
        val parent = parentDatatype(iri)
        typeof =
          if (parent ne null) typeNameFor(parent) else derivedTypes.getOrElse(iri, "string")
      } else {
        take(definition)
        definition.range match {
          case r: DataRange.Restriction =>
            typeof = typeNameFor(r.datatype)
            facets = r.facets
          case r: DataRange.Datatype => typeof = typeNameFor(r.iri)
          // N possible literal values. We express this as a regex in LinkML.
          case r: DataRange.OneOf if r.values.nonEmpty =>
            val first = literalType(r.values.head)
            typeof =
              if (r.values.forall(v => literalType(v) == first)) typeNameFor(first) else "string"
            valuePattern = alternationOf(r.values)
          case other =>
            warn("Datatype definition that LinkML cannot express", s"$iri: ${render(other)}")
            typeof = "string"
        }
      }
      val seen = mutable.HashSet.empty[String]
      facets.foreach { f =>
        if (seen.add(f._1) && !typeFacets(f._1)) {
          warn("Datatype facet that LinkML cannot express", s"$name: ${short(f._1)}")
        }
      }
      val minimum = facetValue(facets, XmlSchema.minInclusive)
      val maximum = facetValue(facets, XmlSchema.maxInclusive)
      val minimumLength = lengthFacet(facets, XmlSchema.minLength)
      val maximumLength = lengthFacet(facets, XmlSchema.maxLength)
      val pattern = facetValue(facets, XmlSchema.pattern)
      doc.metadata.applyTo(
        TypeDefinition(
          name = name,
          typeUri = Some(uri(iri)),
          typeof = Some(Reference(typeof)),
          annotations = doc.annotations,
        ).copy(
          minimumValue = if (minimum ne null) Some(LinkmlAny(minimum)) else None,
          maximumValue = if (maximum ne null) Some(LinkmlAny(maximum)) else None,
          minimumLength = if (minimumLength != noLength) Some(minimumLength) else None,
          maximumLength = if (maximumLength != noLength) Some(maximumLength) else None,
          pattern =
            if (pattern ne null) Some(LinkmlPattern.fromXsd(pattern).linkml)
            else if (valuePattern ne null) Some(valuePattern)
            else None,
        ),
      )
    }

    /** The value of the first facet of a kind, or `null` if there is none. */
    private def facetValue(facets: Seq[(String, Literal)], which: Iri): String = {
      val it = facets.iterator
      while (it.hasNext) {
        val f = it.next()
        if (f._1 == which.value) return f._2.value
      }
      null
    }

    /** The first length facet of a kind, or [[noLength]] if there is none or it is no number. */
    private def lengthFacet(facets: Seq[(String, Literal)], which: Iri): Int = {
      val value = facetValue(facets, which)
      if (value eq null) noLength
      else {
        val n = value.strip.toIntOption
        if (n.isDefined) n.get else noLength
      }
    }

    private def typeNameFor(iri: String): String = {
      var name = configuredTypes.getOrElse(iri, null)
      if (name eq null) name = builtInTypes.getOrElse(iri, null)
      if (name eq null) name = names.getOrElse(iri, null)
      if (name eq null) "string" else name
    }

    // Ranges

    private def any(data: Boolean): SlotRange = SlotRange(range = Some(anyClass), data = data)

    /** The slot range for a range or restriction filler, or `null` if LinkML can't express it. */
    private def slotRange(f: Filler): SlotRange = f match {
      case n: ClassExpr.Named =>
        if (wellKnownClasses(n.iri)) any(false)
        else {
          val name = names.getOrElse(n.iri, null)
          if (name ne null) SlotRange(range = Some(name)) else null
        }
      case d: DataRange.Datatype =>
        if (d.iri == Rdfs.Literal.value) any(true)
        // ODRL puts classes in data ranges. LinkML can't express that so it maps back to OWL.
        else if (isDeclared(d.iri, EntityKind.Class)) null
        else SlotRange(range = Some(typeNameFor(d.iri)), data = true)
      case r: DataRange.Restriction =>
        if (!r.facets.forall(f => typeFacets(f._1))) null
        else {
          val facets = r.facets
          val minimum = facetValue(facets, XmlSchema.minInclusive)
          val maximum = facetValue(facets, XmlSchema.maxInclusive)
          val minimumLength = lengthFacet(facets, XmlSchema.minLength)
          val maximumLength = lengthFacet(facets, XmlSchema.maxLength)
          val pattern = facetValue(facets, XmlSchema.pattern)
          SlotRange(
            range = Some(typeNameFor(r.datatype)),
            minimumValue = if (minimum ne null) Some(minimum) else None,
            maximumValue = if (maximum ne null) Some(maximum) else None,
            minimumLength = if (minimumLength != noLength) Some(minimumLength) else None,
            maximumLength = if (maximumLength != noLength) Some(maximumLength) else None,
            pattern = if (pattern ne null) Some(LinkmlPattern.fromXsd(pattern).linkml) else None,
            data = true,
          )
        }
      case u: ClassExpr.UnionOf => unionRange(u.operands, data = false)
      case u: DataRange.UnionOf => unionRange(u.operands, data = true)
      case _ => null
    }

    /** An `any_of` of the alternatives, or `null` if one is not a plain class, enum or type. */
    private def unionRange(alternatives: Seq[Filler], data: Boolean): SlotRange = {
      val anyOf = Vector.newBuilder[String]
      val it = alternatives.iterator
      while (it.hasNext) {
        val r = slotRange(it.next())
        if ((r eq null) || !r.isOneElement) return null
        anyOf.addOne(r.range.get)
      }
      SlotRange(anyOf = anyOf.result(), data = data)
    }

    /** The range of each property, before any class restrictions. */
    private lazy val slotRanges: mutable.HashMap[String, SlotRange] = {
      val out = mutable.HashMap.empty[String, SlotRange]
      properties.foreach { p =>
        val stated = entriesOf(ranges, p)
        var range: SlotRange = null
        if (stated.length == 1) {
          val e = stated.head
          if (isVacuous(e.range) && hasKnownParent(p)) {
            // Drop it: the subproperty inherits its parent's range, which this repeats or widens.
            take(e.axiom)
          } else {
            range = slotRange(e.range)
            if (range ne null) take(e.axiom)
            else warn("Range that LinkML cannot express", s"${names(p)}: ${render(e.range)}")
          }
        } else if (stated.length > 1) {
          val all = new java.lang.StringBuilder
          stated.foreach { e =>
            if (all.length > 0) all.append(", ")
            all.append(render(e.range))
          }
          warn(
            "Several ranges (all of them apply), of which only the first is kept",
            s"${names(p)}: $all",
          )
          take(stated.head.axiom)
          range = slotRange(stated.head.range)
        }
        val data = propertyKinds(p).kind == EntityKind.DataProperty
        out.update(
          p,
          if (range ne null) range
          // Leave the range empty so a subproperty inherits it from its parent.
          else if (hasKnownParent(p)) SlotRange(data = data)
          else any(data),
        )
      }
      out
    }

    private def hasKnownParent(p: String): Boolean =
      entriesOf(superProperties, p).exists(e => propertyKinds.contains(e.sup))

    private def isVacuous(f: Filler): Boolean = f match {
      case n: ClassExpr.Named => wellKnownClasses(n.iri)
      case d: DataRange.Datatype => d.iri == Rdfs.Literal.value
      case _ => false
    }

    /** The property's own range, or else the first one it inherits from a parent. */
    private def effectiveRange(p: String): SlotRange = {
      val seen = mutable.HashSet.empty[String]
      var property = p
      while (true) {
        val imported = importedRanges.getOrElse(property, null)
        val own = if (imported ne null) imported else slotRanges(property)
        if (own.range.isDefined || own.anyOf.nonEmpty || !seen.add(property)) return own
        val sup = entriesOf(superProperties, property).find(e => propertyKinds.contains(e.sup))
        if (sup.isEmpty) return own
        property = sup.get.sup
      }
      null
    }

    // Slots

    /** The inverse of each property, in both directions. */
    private lazy val inverseOf: mutable.HashMap[String, String] = {
      val out = mutable.HashMap.empty[String, String]
      inverses.foreach { a =>
        take(a)
        out.update(a.first, a.second)
        out.update(a.second, a.first)
      }
      out
    }

    /** The property of a restriction that allows at most one plain string, `p max 1 xsd:string`, if
      * the range of the property is `langstring`, or `null` otherwise. The OWL generator writes
      * this for a single-valued `langstring` slot, as a text in several languages is one literal
      * per language.
      */
    private def plainTextLimit(expr: ClassExpr): String = expr match {
      case c: ClassExpr.Cardinality =>
        if (
          c.bound == Bound.Max && c.n == 1 && !c.property.inverse &&
          c.filler.contains(DataRange.Datatype(XmlSchema.string.value)) && {
            val r = slotRanges.getOrElse(c.property.iri, null)
            (r ne null) && r.range.contains("langstring")
          }
        ) c.property.iri
        else null
      case _ => null
    }

    /** The `langstring` properties that are single-valued, see [[plainTextLimit]]. */
    private lazy val singleTexts: mutable.HashSet[String] = {
      val out = mutable.HashSet.empty[String]
      subClassOf.valuesIterator.foreach(_.foreach { e =>
        val p = plainTextLimit(e.sup)
        if (p ne null) out.addOne(p)
      })
      out
    }

    private lazy val slots: mutable.ArrayBuffer[SlotDefinition] = {
      val out = mutable.ArrayBuffer.empty[SlotDefinition]
      properties.foreach(p => if (!imported.contains(p)) out.addOne(slotOf(p)))
      out
    }

    private def slotOf(p: String): SlotDefinition = {
      val kind = propertyKinds(p).kind
      val name = names(p)
      val doc = documentation(p)
      var characteristics = 0
      entriesOf(characteristicsOf, p)
        .foreach(a => characteristics |= 1 << take(a).characteristic.ordinal)
      def has(c: PropertyCharacteristic): Boolean = (characteristics & (1 << c.ordinal)) != 0
      val range = slotRanges(p)
      val parents = mutable.ArrayBuffer.empty[String]
      entriesOf(superProperties, p).foreach { e =>
        val known = propertyKinds.contains(e.sup)
        take(e.axiom)
        if (known) parents.addOne(e.sup)
        else warn("Subproperty of an annotation or built-in property", s"$name: ${short(e.sup)}")
      }
      val chosen = chooseParents(p, parents)
      // LinkML slots inherit `multivalued`, and `false` cannot override it.
      val inheritsMultivalued = config.multivalued && parents.exists(sup => !isFunctional(sup))
      if (inheritsMultivalued && has(PropertyCharacteristic.Functional)) {
        warn(
          "Functional subproperty of a property that is not, left multivalued as LinkML slots inherit it",
          name,
        )
      }
      // `domain` takes one class. With a union or several domains, the slot is added to each
      // class instead (see `slotsByDomain`).
      val domains = entriesOf(index.domains, p)
      var domain: Option[Reference[ClassDefinition]] = None
      val domainIt = domains.iterator
      while (domain.isEmpty && domainIt.hasNext) {
        domainIt.next().domain match {
          case n: ClassExpr.Named =>
            if (names.contains(n.iri) && classParents.contains(n.iri)) {
              domain = Some(Reference[ClassDefinition](names(n.iri)))
            }
          case _ =>
        }
      }
      var domainOf: Seq[Reference[ClassDefinition]] = Nil
      val unionIt = domains.iterator
      var foundUnion = false
      while (!foundUnion && unionIt.hasNext) {
        unionIt.next().domain match {
          case u: ClassExpr.UnionOf =>
            if (
              u.operands.forall {
                case n: ClassExpr.Named => classParents.contains(n.iri)
                case _ => false
              }
            ) {
              foundUnion = true
              val refs = Vector.newBuilder[Reference[ClassDefinition]]
              u.operands.foreach {
                case n: ClassExpr.Named => refs.addOne(Reference[ClassDefinition](names(n.iri)))
                case _ =>
              }
              domainOf = refs.result()
            }
          case _ =>
        }
      }
      if (has(PropertyCharacteristic.InverseFunctional)) {
        warn("Inverse functional property, which LinkML cannot say", name)
      }
      entriesOf(chains, p).foreach { a =>
        take(a)
        val chain = new java.lang.StringBuilder
        a.chain.foreach { r =>
          if (chain.length > 0) chain.append(" o ")
          chain.append(render(ClassExpr.Named(r.iri)))
        }
        warn("Property chain, which LinkML cannot say", s"$name: $chain")
      }
      val equivalent = mutable.ArrayBuffer.empty[String]
      entriesOf(equivalentProperties, p).foreach { e =>
        take(e.axiom)
        equivalent.addOne(e.other)
      }
      // The property kind that the range implies.
      val inferred = if (range.data) EntityKind.DataProperty else EntityKind.ObjectProperty
      val implements: String =
        if (kind == EntityKind.AnnotationProperty) owlNs.concat("AnnotationProperty")
        // Mark a data property explicitly unless its range is a type. `Any` or an inherited
        // range doesn't show that it is a data property.
        else if (
          kind == EntityKind.DataProperty && (!range.data || range.range.forall(_ == anyClass))
        ) owlNs.concat("DatatypeProperty")
        else if (kind == EntityKind.ObjectProperty && inferred != kind) {
          owlNs.concat("ObjectProperty")
        } else null
      val slotUri = explicitIri(p, NameGroup.Slot)
      val declaredRange =
        if (kind == EntityKind.AnnotationProperty) {
          // A vacuous range such as `rdfs:Literal` maps to `Any`. Don't set it explicitly
          // because it only adds noise.
          val stated = ranges.getOrElse(p, null)
          val r = if ((stated ne null) && stated.nonEmpty) slotRange(stated.head.range) else null
          if ((r ne null) && r.range.isDefined && r.range.get != anyClass) {
            Some(Reference(r.range.get))
          } else None
        } else if (range.range.isDefined) Some(Reference(range.range.get))
        else None
      val anyOf = Vector.newBuilder[AnonymousSlotExpression]
      range.anyOf.foreach(r => anyOf.addOne(AnonymousSlotExpression(range = Some(Reference(r)))))
      val inverse = inverseOf.getOrElse(p, null)
      val definition = doc.metadata.applyTo(
        SlotDefinition(
          name = name,
          slotUri = slotUri,
          range = declaredRange,
          anyOf = anyOf.result(),
          multivalued = kind != EntityKind.AnnotationProperty &&
            (inheritsMultivalued || config.multivalued &&
              !has(PropertyCharacteristic.Functional) && !singleTexts(p)),
          domain = domain,
          domainOf = domainOf,
          inverse =
            if ((inverse ne null) && names.contains(inverse)) Some(Reference(names(inverse)))
            else None,
          isA = if (chosen.isA ne null) Some(Reference(chosen.isA)) else None,
          mixins = references(chosen.mixins),
          transitive = has(PropertyCharacteristic.Transitive),
          symmetric = has(PropertyCharacteristic.Symmetric),
          asymmetric = has(PropertyCharacteristic.Asymmetric),
          reflexive = has(PropertyCharacteristic.Reflexive),
          irreflexive = has(PropertyCharacteristic.Irreflexive),
          implements = if (implements ne null) Vector(uri(implements)) else Nil,
          annotations = doc.annotations,
        ).copy(
          minimumValue = range.minimumValue.map(LinkmlAny(_)),
          maximumValue = range.maximumValue.map(LinkmlAny(_)),
          minimumLength = range.minimumLength,
          maximumLength = range.maximumLength,
          pattern = range.pattern,
        ),
      )
      val equivalentUris = mutable.ArrayBuffer.empty[UriOrCurie]
      equivalent.foreach { e =>
        ensurePrefix(e)
        equivalentUris.addOne(uri(e))
      }
      definition.copy(exactMappings = sorted(doc.metadata.exactMappings, equivalentUris))
    }

    private def references[A](names: collection.Seq[String]): Seq[Reference[A]] = {
      val out = Vector.newBuilder[Reference[A]]
      names.foreach(n => out.addOne(Reference[A](n)))
      out.result()
    }

    /** Splits named parents into `is_a` and `mixins`. `is_a` prefers a parent in the child's
      * namespace. The parents are sorted, those in the child's namespace first.
      */
    private def chooseParents(child: String, parents: collection.Seq[String]): Parents = {
      val childNamespace = namespaceOf(child)
      val seen = mutable.HashSet.empty[String]
      val same = mutable.ArrayBuffer.empty[String]
      val other = mutable.ArrayBuffer.empty[String]
      parents.foreach { p =>
        if (seen.add(p) && names.contains(p)) {
          (if (namespaceOf(p) == childNamespace) same else other).addOne(p)
        }
      }
      var isA: String = null
      val mixins = mutable.ArrayBuffer.empty[String]

      def add(p: String): Unit = if (isA eq null) isA = names(p) else mixins.addOne(names(p))

      same.sortInPlace().foreach(add)
      other.sortInPlace().foreach(add)
      (isA = isA, mixins = mixins)
    }

    /** The `is_a` parent of an element, by name, or `null` if it has none. */
    private def isAOf(child: String, parents: collection.Seq[String]): String =
      chooseParents(child, parents).isA

    // Classes

    /** Parents of a class that can be inferred from an axiom other than `rdfs:subClassOf`.
      *
      * A class defined by an intersection is a subclass of each named conjunct, and the members of
      * a union or a disjoint union are subclasses of the class they define.
      */
    private lazy val impliedParents: mutable.HashMap[String, mutable.ArrayBuffer[String]] = {
      val out = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
      def add(child: String, parent: String): Unit =
        if (
          child != parent && names.contains(parent) && !enumMembers.contains(parent) &&
          !wellKnownClasses(parent)
        ) {
          val parents = out.getOrElseUpdate(child, mutable.ArrayBuffer.empty)
          if (!parents.contains(parent)) parents.addOne(parent)
        }
      classes.foreach { c =>
        if (!enumMembers.contains(c)) {
          entriesOf(equivalents, c).foreach { e =>
            e.other match {
              // Manager = Staff and (manages some Team): every manager is a staff member.
              case x: ClassExpr.IntersectionOf =>
                x.operands.foreach {
                  case n: ClassExpr.Named => add(c, n.iri)
                  case _ =>
                }
              // Vehicle = Car or Truck: every car is a vehicle.
              case x: ClassExpr.UnionOf =>
                x.operands.foreach {
                  case n: ClassExpr.Named => add(n.iri, c)
                  case _ =>
                }
              case _ =>
            }
          }
          // Color disjointUnionOf (Red Green): every red thing is a color.
          val union = disjointUnions.getOrElse(c, null)
          if (union ne null) {
            union.operands.foreach {
              case n: ClassExpr.Named => add(n.iri, c)
              case _ =>
            }
          }
        }
      }
      out
    }

    /** The named parents of each class, minus any that would create a cycle. */
    private lazy val classParents: mutable.HashMap[String, mutable.ArrayBuffer[String]] = {
      val raw = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
      classes.foreach { c =>
        val parents = mutable.ArrayBuffer.empty[String]
        def add(p: String): Unit = if (!parents.contains(p)) parents.addOne(p)
        entriesOf(subClassOf, c).foreach { e =>
          e.sup match {
            case n: ClassExpr.Named =>
              val p = n.iri
              if (names.contains(p) && p != c && !enumMembers.contains(p)) {
                take(e.axiom)
                add(p)
              } else if (wellKnownClasses(p)) {
                // Every class is a subclass of owl:Thing anyway.
                take(e.axiom)
              }
            case _ =>
          }
        }
        entriesOf(impliedParents, c).foreach(add)
        raw.update(c, parents)
      }
      // Equivalent classes are subclasses of each other. LinkML doesn't allow cycles, so drop any
      // parent that leads back to the child.
      val out = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
      def reaches(from: String, target: String): Boolean = {
        val seen = mutable.HashSet.empty[String]
        val stack = mutable.ArrayBuffer(from)
        while (stack.nonEmpty) {
          val x = stack.remove(stack.length - 1)
          if (x == target) return true
          if (seen.add(x)) {
            val parents = out.getOrElse(x, null)
            if (parents ne null) stack.addAll(parents)
          }
        }
        false
      }
      classes.foreach { c =>
        val parents = mutable.ArrayBuffer.empty[String]
        raw(c).foreach { p =>
          if (reaches(p, c)) {
            warn("Subclass cycle, broken by leaving out a parent", s"${names(c)} -> ${names(p)}")
          } else parents.addOne(p)
        }
        out.update(c, parents)
      }
      out
    }

    /** The children of each class by IRI, by `is_a` only (not mixins). */
    private lazy val childrenOf: mutable.HashMap[String, mutable.ArrayBuffer[String]] = {
      val out = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
      classes.foreach { k =>
        val parentName = isAOf(k, classParents(k))
        if (parentName ne null) {
          out.getOrElseUpdate(naming.iriOf(parentName), mutable.ArrayBuffer.empty).addOne(k)
        }
      }
      out
    }

    /** The `is_a` parent shared by all the classes, or `null` if there is none. */
    private def commonParent(members: collection.Seq[String]): String =
      if (members.isEmpty) null
      else {
        val first = isAOf(members.head, classParents(members.head))
        if ((first ne null) && members.forall(m => isAOf(m, classParents(m)) == first)) {
          naming.iriOf(first)
        } else null
      }

    /** Compares properties by their place in [[properties]]. Others come last. */
    private val byPropertyOrder: java.util.Comparator[String] = (a, b) =>
      Integer.compare(orderOf(a), orderOf(b))

    private def orderOf(p: String): Int = {
      val info = propertyKinds.getOrElse(p, null)
      if (info ne null) info.order else Int.MaxValue
    }

    /** Slots attached to a class through `rdfs:domain`. */
    private lazy val slotsByDomain: mutable.HashMap[String, mutable.ArrayBuffer[String]] = {
      val out = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
      def attach(c: String, p: String): Unit =
        if (classParents.contains(c)) out.getOrElseUpdate(c, mutable.ArrayBuffer.empty).addOne(p)
      properties.foreach { p =>
        val stated = entriesOf(domains, p)
        stated.foreach { e =>
          e.domain match {
            case n: ClassExpr.Named =>
              take(e.axiom)
              if (!wellKnownClasses(n.iri)) {
                if (stated.length > 1) {
                  warn(
                    "Several domains (all of them apply), attached to each",
                    s"${names(p)}: ${names.getOrElse(n.iri, n.iri)}",
                  )
                }
                attach(n.iri, p)
              }
            case u: ClassExpr.UnionOf if u.operands.forall(_.isInstanceOf[ClassExpr.Named]) =>
              take(e.axiom)
              u.operands.foreach(o => attach(o.asInstanceOf[ClassExpr.Named].iri, p))
            case other =>
              warn("Domain that LinkML cannot express", s"${names(p)}: ${render(other)}")
          }
        }
      }
      out
    }

    private lazy val classDefinitions: mutable.ArrayBuffer[ClassDefinition] = {
      val out = mutable.ArrayBuffer.empty[ClassDefinition]
      classes.foreach(c => if (!imported.contains(c)) out.addOne(buildClass(c)))
      out
    }

    private def buildClass(c: String): ClassDefinition = {
      val name = names(c)
      val doc = termDocumentation(c)
      val parents = classParents(c)
      val chosen = chooseParents(c, parents)
      val notes = mutable.ArrayBuffer.empty[String]
      val usage = mutable.HashMap.empty[String, SlotDefinition]
      val usageOrder = mutable.ArrayBuffer.empty[String]
      val attached = mutable.ArrayBuffer.empty[String]
      val attachedSet = mutable.HashSet.empty[String]
      def attach(p: String): Unit = if (attachedSet.add(p)) attached.addOne(p)
      entriesOf(slotsByDomain, c).foreach(attach)
      var isAbstract = false
      val exact = mutable.ArrayBuffer.empty[UriOrCurie]
      val disjoint = mutable.ArrayBuffer.empty[String]

      def unsupported(kind: String, expr: ClassExpr): Unit = {
        val text = render(expr)
        warn(kind, s"$name: $text")
        notes.addOne(s"OWL: $text")
      }

      def restrict(p: String, f: SlotDefinition => SlotDefinition): Unit = {
        attach(p)
        val n = names(p)
        val before = usage.getOrElse(n, null)
        val after = f(if (before ne null) before else SlotDefinition(name = n))
        if (after != SlotDefinition(name = n)) {
          if (before eq null) usageOrder.addOne(n)
          usage.update(n, after)
        }
      }

      /** True if the filler adds nothing to the property's own range. */
      def coversRange(p: String, filler: Filler): Boolean = filler match {
        case n: ClassExpr.Named if wellKnownClasses(n.iri) => true
        case d: DataRange.Datatype if d.iri == Rdfs.Literal.value => true
        case other =>
          val own = effectiveRange(p).copy(data = false)
          val widened = own.copy(
            minimumValue = None,
            maximumValue = None,
            minimumLength = None,
            maximumLength = None,
            pattern = None,
          )
          val r = slotRange(other)
          (r ne null) && {
            val narrowed = r.copy(data = false)
            narrowed == own || narrowed == widened
          }
      }

      /** True if the expressions are exactly the class's children, so the children cover it. */
      def isUnionOfChildren(ops: Seq[ClassExpr]): Boolean =
        ops.forall(_.isInstanceOf[ClassExpr.Named]) && {
          val members = mutable.HashSet.empty[String]
          ops.foreach(o => members.addOne(o.asInstanceOf[ClassExpr.Named].iri))
          val children = mutable.HashSet.empty[String]
          children.addAll(entriesOf(childrenOf, c))
          members == children
        }

      /** True if the slot holds at most one value, so a max cardinality of 1 adds nothing. */
      def singleValuedSlot(p: String) = isFunctional(p) || !config.multivalued

      def superclass(expr: ClassExpr): Unit = expr match {
        case _: ClassExpr.Named => // handled with the parents
        case e: ClassExpr.IntersectionOf => e.operands.foreach(superclass)
        case e: ClassExpr.AllValuesFrom
            if !e.property.inverse && propertyKinds.contains(e.property.iri) =>
          val p = e.property.iri
          if (coversRange(p, e.filler)) attach(p)
          else {
            val r = slotRange(e.filler)
            if (r ne null) {
              val anyOf = Vector.newBuilder[AnonymousSlotExpression]
              r.anyOf.foreach(x =>
                anyOf.addOne(AnonymousSlotExpression(range = Some(Reference(x)))),
              )
              val narrowedAnyOf = anyOf.result()
              restrict(
                p,
                s =>
                  s.copy(
                    range = r.range.map(Reference(_)),
                    anyOf = narrowedAnyOf,
                    minimumValue = r.minimumValue.map(LinkmlAny(_)),
                    maximumValue = r.maximumValue.map(LinkmlAny(_)),
                    minimumLength = r.minimumLength,
                    maximumLength = r.maximumLength,
                    pattern = r.pattern,
                  ),
              )
            } else unsupported("Restriction that LinkML cannot express", expr)
          }
        case e: ClassExpr.SomeValuesFrom
            if !e.property.inverse && propertyKinds.contains(e.property.iri) =>
          val p = e.property.iri
          if (coversRange(p, e.filler)) restrict(p, _.copy(required = true))
          else {
            val r = slotRange(e.filler)
            val style = config.someValuesFromStyle
            if ((r ne null) && style == SomeValuesFrom.Range) {
              restrict(p, _.copy(required = true, range = r.range.map(Reference(_))))
            } else if ((r ne null) && style == SomeValuesFrom.Required && r.range.isDefined) {
              restrict(p, withMember(_, r.range))
            } else unsupported("Restriction that LinkML cannot express", expr)
          }
        // The slot is single-valued already, see `singleTexts`
        case _ if plainTextLimit(expr) ne null =>
        case e: ClassExpr.Cardinality
            if !e.property.inverse && propertyKinds.contains(e.property.iri) =>
          val p = e.property.iri
          val filler = e.filler
          if (filler.isDefined && !coversRange(p, filler.get)) {
            val r = slotRange(filler.get)
            if ((r ne null) && config.someValuesFromStyle == SomeValuesFrom.Range) {
              cardinality(p, e.bound, e.n, singleValuedSlot(p), restrict)
              restrict(p, _.copy(range = r.range.map(Reference(_))))
            } else if ((r ne null) && e.bound == Bound.Min && e.n == 1 && r.range.isDefined) {
              restrict(p, withMember(_, r.range))
            } else unsupported("Qualified cardinality that LinkML cannot express", expr)
          } else cardinality(p, e.bound, e.n, singleValuedSlot(p), restrict)
        case e: ClassExpr.HasValue
            if !e.property.inverse && propertyKinds.contains(e.property.iri) =>
          val p = e.property.iri
          val text = e.value match {
            case i: Iri =>
              val value = enumValueText(p, i.value)
              if (value ne null) value else i.value
            case l: Literal => l.value
            case l: LanguageLiteral => l.value
          }
          restrict(p, _.copy(equalsString = Some(text)))
        case e: ClassExpr.UnionOf if isUnionOfChildren(e.operands) => isAbstract = true
        case other => unsupported("Superclass that LinkML cannot express", other)
      }

      entriesOf(subClassOf, c).foreach { e =>
        if (!e.sup.isInstanceOf[ClassExpr.Named]) {
          take(e.axiom)
          superclass(e.sup)
        }
      }
      entriesOf(equivalents, c).foreach { e =>
        take(e.axiom)
        e.other match {
          case n: ClassExpr.Named =>
            ensurePrefix(n.iri)
            exact.addOne(uri(n.iri))
          case u: ClassExpr.UnionOf if isUnionOfChildren(u.operands) => isAbstract = true
          case expr =>
            // Keep it as a superclass. LinkML can't say that it is also a sufficient condition.
            warn(
              "Class defined by an equivalence, kept as superclasses only",
              s"$name: ${render(expr)}",
            )
            superclass(expr)
        }
      }
      val childList = entriesOf(childrenOf, c)
      val children = mutable.HashSet.empty[String]
      children.addAll(childList)
      // A disjoint pair goes on the first class. A larger group goes on the parent if it is
      // exactly the parent's children, otherwise on each member.
      entriesOf(disjointByClass, c).foreach { a =>
        val ops = a.operands
        if (ops.length == 2) {
          ops(0) match {
            case first: ClassExpr.Named =>
              ops(1) match {
                case second: ClassExpr.Named =>
                  if (first.iri == c && classParents.contains(second.iri)) {
                    take(a)
                    disjoint.addOne(names(second.iri))
                  }
                case _ =>
              }
            case _ =>
          }
        } else if (ops.length > 2 && isOnEachMember(a)) {
          take(a)
          ops.foreach { o =>
            val m = o.asInstanceOf[ClassExpr.Named].iri
            if (m != c) disjoint.addOne(names(m))
          }
        }
      }
      // A group of exactly the children includes the first child, so only its groups are checked.
      val disjointChildren = (children.nonEmpty && entriesOf(disjointByClass, childList.head)
        .exists { a =>
          val ops = a.operands
          ops.length > 2 && {
            val members = mutable.HashSet.empty[String]
            ops.foreach {
              case n: ClassExpr.Named => members.addOne(n.iri)
              case _ =>
            }
            val matches = members == children && children.size == ops.length
            if (matches) take(a)
            matches
          }
        }) || {
        val union = disjointUnions.getOrElse(c, null)
        (union ne null) && {
          take(union)
          isAbstract = true
          true
        }
      }
      val keys = mutable.ArrayBuffer.empty[collection.Seq[String]]
      entriesOf(keysByClass, c).foreach { a =>
        if (a.properties.forall(names.contains)) {
          take(a)
          val slots = mutable.ArrayBuffer.empty[String]
          a.properties.foreach(p => slots.addOne(names(p)))
          keys.addOne(slots)
        }
      }

      // List only slots that no ancestor already has, here or in an imported schema.
      val ancestors = mutable.HashSet.empty[String]
      val importedAncestorSlots = mutable.ArrayBuffer.empty[mutable.HashSet[String]]
      val stack = mutable.ArrayBuffer.from(entriesOf(classParents, c))
      while (stack.nonEmpty) {
        val a = stack.remove(stack.length - 1)
        if (ancestors.add(a)) {
          val slots = importedSlots.getOrElse(a, null)
          if (slots ne null) importedAncestorSlots.addOne(slots)
          stack.addAll(entriesOf(classParents, a))
        }
      }
      def isInherited(p: String): Boolean =
        entriesOf(domainClassesOf, p).exists(ancestors.contains) ||
          importedAncestorSlots.exists(_.contains(p))
      // In declaration order, which the generator keeps: a slot can come from `rdfs:domain` here
      // and from a restriction in the generated OWL.
      val own = mutable.ArrayBuffer.empty[String]
      attached.foreach(p => if (!isInherited(p)) own.addOne(p))
      val ordered = own.toArray
      // A stable sort, so slots that are not properties keep their order.
      java.util.Arrays.sort(ordered, byPropertyOrder)
      val slotRefs = Vector.newBuilder[Reference[SlotDefinition]]
      ordered.foreach(p => slotRefs.addOne(Reference(names(p))))
      val slotUsage = VectorMap.newBuilder[String, SlotDefinition]
      usageOrder.foreach(n => slotUsage.addOne(n -> usage(n)))
      val uniqueKeys = VectorMap.newBuilder[String, UniqueKey]
      var i = 0
      keys.foreach { slots =>
        val keyName = if (i == 0) s"${name}_key" else s"${name}_key_${i + 1}"
        uniqueKeys.addOne(
          keyName -> UniqueKey(uniqueKeyName = keyName, uniqueKeySlots = references(slots)),
        )
        i += 1
      }
      val classNotes = Vector.newBuilder[LocalizedText]
      classNotes.addAll(doc.metadata.notes)
      notes.foreach(n => classNotes.addOne(PlainText(n)))

      doc.metadata.applyTo(
        ClassDefinition(
          name = name,
          classUri = explicitIri(c, NameGroup.Class),
          isA = if (chosen.isA ne null) Some(Reference(chosen.isA)) else None,
          mixins = references(chosen.mixins),
          slots = slotRefs.result(),
          slotUsage = slotUsage.result(),
          `abstract` = isAbstract,
          childrenAreMutuallyDisjoint = disjointChildren,
          disjointWith = references(disjoint),
          uniqueKeys = uniqueKeys.result(),
          annotations = doc.annotations,
        ),
      ).copy(
        exactMappings = sorted(doc.metadata.exactMappings, exact),
        notes = classNotes.result(),
      )
    }

    /** Whether a disjointness group of more than two named classes goes on each member: all members
      * are classes and the group is not exactly the children of their parent. That case is handled
      * with `children_are_mutually_disjoint` on the parent. Each member has the same axiom, so the
      * answer is computed once.
      */
    private def isOnEachMember(a: DisjointClasses): Boolean = {
      val known = onEachMember.get(a)
      if (known ne null) known.booleanValue
      else {
        val ops = a.operands
        val result = ops.forall(_.isInstanceOf[ClassExpr.Named]) && {
          val members = mutable.ArrayBuffer.empty[String]
          ops.foreach(o => members.addOne(o.asInstanceOf[ClassExpr.Named].iri))
          // Members that became enums have no parents, so they are checked first.
          members.forall(classParents.contains) && {
            val p = commonParent(members)
            (p eq null) || {
              val siblings = mutable.HashSet.empty[String]
              siblings.addAll(entriesOf(childrenOf, p))
              val memberSet = mutable.HashSet.empty[String]
              memberSet.addAll(members)
              siblings != memberSet
            }
          }
        }
        onEachMember.put(a, java.lang.Boolean.valueOf(result))
        result
      }
    }

    private val onEachMember = new java.util.IdentityHashMap[DisjointClasses, java.lang.Boolean]

    /** The classes each property is attached to through `rdfs:domain`. */
    private lazy val domainClassesOf: mutable.HashMap[String, mutable.ArrayBuffer[String]] = {
      val out = mutable.HashMap.empty[String, mutable.ArrayBuffer[String]]
      slotsByDomain.foreachEntry { (c, properties) =>
        properties.foreach(p => out.getOrElseUpdate(p, mutable.ArrayBuffer.empty).addOne(c))
      }
      out
    }

    /** Adds "at least one value is a `range`" to a slot. Uses `has_member`, or an `all_of` entry if
      * `has_member` is already taken.
      */
    private def withMember(slot: SlotDefinition, range: Option[String]): SlotDefinition = {
      val member = AnonymousSlotExpression(range = range.map(Reference(_)))
      if (slot.hasMember.isEmpty) slot.copy(required = true, hasMember = Some(member))
      else if (slot.hasMember.contains(member) || slot.allOf.exists(_.hasMember.contains(member))) {
        slot
      } else slot.copy(allOf = slot.allOf :+ AnonymousSlotExpression(hasMember = Some(member)))
    }

    /** Turns a cardinality restriction into `slot_usage`, skipping what the slot already says. */
    private def cardinality(
        p: String,
        bound: Bound,
        n: Int,
        functional: Boolean,
        restrict: (String, SlotDefinition => SlotDefinition) => Unit,
    ): Unit = bound match {
      case Bound.Min =>
        if (n == 0) restrict(p, identity)
        else if (n == 1) restrict(p, _.copy(required = true))
        else restrict(p, _.copy(minimumCardinality = Some(n)))
      case Bound.Max =>
        if (n == 1 && functional) restrict(p, identity)
        else restrict(p, _.copy(maximumCardinality = Some(n)))
      case Bound.Exact =>
        if (n == 1) {
          if (functional) restrict(p, _.copy(required = true))
          else restrict(p, _.copy(required = true, maximumCardinality = Some(1)))
        } else restrict(p, _.copy(exactCardinality = Some(n)))
    }

    /** The individual's permissible value, if the property's range is an enum that has it, or
      * `null` otherwise.
      */
    private def enumValueText(p: String, individual: String): String = {
      val range = slotRanges.getOrElse(p, null)
      if ((range eq null) || range.range.isEmpty) null
      else {
        val e = enumByName.getOrElse(range.range.get, null)
        if ((e ne null) && enumMemberSet(e).contains(individual))
          permissibleValueText(e, individual)
        else null
      }
    }

    /** Enums by name. */
    private lazy val enumByName: mutable.HashMap[String, String] = {
      val out = mutable.HashMap.empty[String, String]
      enumClasses.foreach { e =>
        val name = names.getOrElse(e, null)
        if (name ne null) out.getOrElseUpdate(name, e)
      }
      out
    }

    /** The members of each enum, as a set. */
    private val enumMemberSets = mutable.HashMap.empty[String, mutable.HashSet[String]]

    private def enumMemberSet(e: String): mutable.HashSet[String] =
      enumMemberSets.getOrElseUpdate(e, mutable.HashSet.from(enumMembers(e).members))

    // Enums

    /** Permissible value texts by enum and member. */
    private lazy val pvTexts = mutable.HashMap.empty[String, mutable.HashMap[String, String]]

    /** Permissible value text: the `skos:notation` (where the generator writes it), else a label
      * that the IRI was built from (as `gen-owl` does), else the IRI's local name.
      */
    private def permissibleValueText(e: String, member: String): String = {
      val texts = pvTexts.getOrElseUpdate(e, mutable.HashMap.empty)
      val cached = texts.getOrElse(member, null)
      if (cached ne null) cached
      else {
        val annotations = entriesOf(annotationsOf, member)
        var text: String = null
        annotations.foreach { a =>
          if ((text eq null) && a.property == notationProperty) {
            a.value match {
              case l: Literal => text = l.value
              case _ =>
            }
          }
        }
        if (text eq null) {
          annotations.foreach { a =>
            if ((text eq null) && a.property == Rdfs.label.value) {
              a.value match {
                case l: Literal => if (defaultMeaning(e, l.value) == member) text = l.value
                case _ =>
              }
            }
          }
        }
        if (text eq null) text = styled(localName(member), config.permissibleValueStyle)
        texts.update(member, text)
        text
      }
    }

    private def defaultMeaning(e: String, text: String): String =
      Uri.synthetic(
        defaultNamespace,
        Case.baseToPascal(Case.base(names(e))) + "." + Case.base(text).toUpperCase,
      ).original

    private lazy val enums: mutable.ArrayBuffer[EnumDefinition] = {
      val out = mutable.ArrayBuffer.empty[EnumDefinition]
      enumClasses.foreach(e => if (!imported.contains(e)) out.addOne(buildEnum(e)))
      out
    }

    private def buildEnum(e: String): EnumDefinition = {
      val members = enumMembers(e)
      val name = names(e)
      val doc = termDocumentation(e)
      val values = VectorMap.newBuilder[String, PermissibleValue]
      members.members.foreach { m =>
        val text = permissibleValueText(e, m)
        val notation = Literal(text)
        val annotations = mutable.ArrayBuffer.empty[Annotation]
        entriesOf(annotationsOf, m).foreach { a =>
          if (a.property != notationProperty || a.value != notation) annotations.addOne(a)
        }
        val pvDoc = documentation(annotations)
        if (members.classes) entriesOf(subClassOf, m).foreach(s => take(s.axiom))
        else {
          entriesOf(assertionsByIndividual, m).foreach { a =>
            a.cls match {
              case n: ClassExpr.Named => if (n.iri == e) take(a)
              case _ =>
            }
          }
        }
        entriesOf(declarationsByIri, m).foreach(take)
        ensurePrefix(m)
        values.addOne(
          text -> pvDoc.metadata.applyTo(
            PermissibleValue(
              text = text,
              meaning = if (m != defaultMeaning(e, text)) Some(uri(m)) else None,
              annotations = pvDoc.annotations,
            ),
          ),
        )
      }
      val chosen = chooseParents(e, parentsOfEnum(e))
      // Named equivalents become mappings, as for classes.
      val exact = mutable.ArrayBuffer.empty[UriOrCurie]
      entriesOf(equivalents, e).foreach { x =>
        x.other match {
          case n: ClassExpr.Named =>
            take(x.axiom)
            ensurePrefix(n.iri)
            exact.addOne(uri(n.iri))
          case _ =>
        }
      }
      doc.metadata.applyTo(
        EnumDefinition(
          name = name,
          enumUri = explicitIri(e, NameGroup.Class),
          permissibleValues = values.result(),
          isA = if (chosen.isA ne null) Some(Reference(chosen.isA)) else None,
          mixins = references(chosen.mixins),
          annotations = doc.annotations,
        ),
      ).copy(exactMappings = sorted(doc.metadata.exactMappings, exact))
    }

    /** The enums and classes an enum is a subclass of. */
    private def parentsOfEnum(e: String): mutable.ArrayBuffer[String] = {
      val out = mutable.ArrayBuffer.empty[String]
      entriesOf(subClassOf, e).foreach { x =>
        x.sup match {
          case n: ClassExpr.Named =>
            val p = n.iri
            val wellKnown = wellKnownClasses(p)
            if (names.contains(p) || wellKnown) {
              take(x.axiom)
              if (!wellKnown) out.addOne(p)
            }
          case _ =>
        }
      }
      out
    }

    // The schema

    private def schemaName: String =
      if (config.name.isDefined) config.name.get
      else {
        val it = ontology.annotations.iterator
        while (it.hasNext) {
          val a = it.next()
          if (a.property == Rdfs.label.value) {
            a.value match {
              case l: Literal => if (l.value.matches("[A-Za-z_][A-Za-z0-9_-]*")) return l.value
              case _ =>
            }
          }
        }
        naming.defaultPrefix
      }

    /** The term an axiom is about, or `null` if there is none. */
    private def subjectOf(a: Axiom): String = a match {
      case x: SubClassOf =>
        x.sub match {
          case n: ClassExpr.Named => n.iri
          case _ => null
        }
      case x: EquivalentClasses =>
        val named = x.operands.find(_.isInstanceOf[ClassExpr.Named])
        if (named.isDefined) named.get.asInstanceOf[ClassExpr.Named].iri else null
      case x: DisjointUnion => x.cls
      case x: HasKey =>
        x.cls match {
          case n: ClassExpr.Named => n.iri
          case _ => null
        }
      case x: Domain => x.property
      case x: Range => x.property
      case x: Characteristic => x.property
      case x: SubPropertyOf => x.sub
      case x: EquivalentProperties => if (x.operands.nonEmpty) x.operands.head else null
      case x: PropertyChain => x.sup
      case x: DatatypeDefinition => x.datatype
      case _ => null
    }

    /** True if the ontology says anything about the term besides using it. `rdfs:isDefinedBy`
      * doesn't count.
      */
    private def saysSomethingAbout(iri: String): Boolean =
      entriesOf(annotationsOf, iri).exists(_.property != Rdfs.isDefinedBy.value) ||
        subClassOf.contains(iri) ||
        equivalents.contains(iri) || keysByClass.contains(iri) || domains.contains(iri) ||
        ranges.contains(iri) || characteristicsOf.contains(iri) || superProperties.contains(iri) ||
        equivalentProperties.contains(iri) || enumMembers.contains(iri) ||
        datatypeDefinitions.contains(iri)

    def result: Result = {
      // Build everything first, since building registers the prefixes the schema needs.
      val builtTypes = types
      val builtSlots = slots
      val builtClasses = classDefinitions
      val builtEnums = enums
      val name = schemaName
      // Skip the VANN preferred namespace and prefix: the schema's default prefix covers them.
      val schemaDocAnnotations = mutable.ArrayBuffer.empty[Annotation]
      ontology.annotations.foreach { a =>
        if (
          a.property != vann + "preferredNamespaceUri" &&
          a.property != vann + "preferredNamespacePrefix"
        ) schemaDocAnnotations.addOne(a)
      }
      val doc = documentation(schemaDocAnnotations)
      // Statements about imported terms are dropped. The imported schema defines those terms.
      val importedIris = imported.keysIterator.toArray
      java.util.Arrays.sort(importedIris, Ordering.String)
      importedIris.foreach { iri =>
        if (saysSomethingAbout(iri)) {
          warn("Statement about a term of an imported schema, which keeps its own", short(iri))
        }
      }
      val importedNames = mutable.HashSet.empty[String]
      imported.valuesIterator.foreach(t => importedNames.addOne(t.name))
      val keptClasses = mutable.ArrayBuffer.empty[ClassDefinition]
      builtClasses.foreach(c => if (!importedNames(c.name)) keptClasses.addOne(c))
      val keptSlots = mutable.ArrayBuffer.empty[SlotDefinition]
      builtSlots.foreach(s => if (!importedNames(s.name)) keptSlots.addOne(s))
      // Keep a type only if it is used or declared. Others appear only in dropped axioms.
      val usedTypes = mutable.HashSet.empty[String]
      def useRangesIn(s: SlotDefinition): Unit = {
        if (s.range.isDefined) usedTypes.addOne(s.range.get.value)
        s.anyOf.foreach(x => if (x.range.isDefined) usedTypes.addOne(x.range.get.value))
        if (s.hasMember.isDefined && s.hasMember.get.range.isDefined) {
          usedTypes.addOne(s.hasMember.get.range.get.value)
        }
        s.allOf.foreach { x =>
          if (x.hasMember.isDefined && x.hasMember.get.range.isDefined) {
            usedTypes.addOne(x.hasMember.get.range.get.value)
          }
        }
      }
      keptSlots.foreach(useRangesIn)
      keptClasses.foreach { c =>
        c.slotUsage.valuesIterator.foreach(useRangesIn)
        c.attributes.valuesIterator.foreach(useRangesIn)
      }
      builtTypes.foreach(t => if (t.typeof.isDefined) usedTypes.addOne(t.typeof.get.value))
      val keptTypes = mutable.ArrayBuffer.empty[TypeDefinition]
      builtTypes.foreach { t =>
        if (
          usedTypes(t.name) ||
          naming.iriOf.get(t.name).exists(isDeclared(_, EntityKind.Datatype))
        ) keptTypes.addOne(t)
      }
      def isAny(s: SlotDefinition) =
        s.range.exists(_.value == anyClass) || s.anyOf.exists(_.range.exists(_.value == anyClass))
      val usesAny = keptSlots.exists(isAny) || keptClasses.exists(c =>
        c.slotUsage.valuesIterator.exists(isAny) || c.attributes.valuesIterator.exists(isAny),
      )

      // Warn about every axiom that was not used.
      foreachUnhandled {
        case _: Declaration =>
        // A declaration alone is not worth reporting.
        case a if {
              val subject = subjectOf(a)
              (subject ne null) && imported.contains(subject)
            } =>
        case a: ClassAssertion =>
          if (!enumIndividuals(a.individual)) {
            warn("Individual, which is not part of an enum", s"${a.individual}: ${render(a.cls)}")
          }
        case a: PropertyAssertion =>
          warn("Property value of an individual", s"${a.individual} ${a.property}")
        case a: SubClassOf => warn("Class axiom that LinkML cannot express", render(a))
        case a => warn(s"${a.productPrefix} axiom that LinkML cannot express", render(a))
      }
      val unparsedCounts = mutable.HashMap.empty[String, Int]
      unparsed.foreach { t =>
        val p = t.pred.value
        unparsedCounts.update(p, unparsedCounts.getOrElse(p, 0) + 1)
      }
      val unparsedProperties = unparsedCounts.keysIterator.toArray
      java.util.Arrays.sort(unparsedProperties, Ordering.String)
      unparsedProperties.foreach { p =>
        warn("RDF that is not OWL", s"${unparsedCounts(p)} triples with $p")
      }

      // Without an ontology IRI, use the namespace. That is how others import such vocabularies
      // (e.g. DC terms).
      val schemaId =
        if (config.schemaId.isDefined) config.schemaId.get
        else if (ontology.iri.isDefined) ontology.iri.get
        else defaultNamespace.stripSuffix("#")
      if (!UriCurieValidator.isValidUri(schemaId))
        throw new SchemaConversionException(
          s"The schema id <$schemaId> isn't a valid URI. Set another one with `--schema-id`, or " +
            "with `schema_id` in the config",
        )
      val declaredPrefixes = VectorMap.newBuilder[String, Prefix]
      naming.declared.foreach(e => declaredPrefixes.addOne(e._1 -> Prefix(e._1, Uri(e._2))))
      val imports = Vector.newBuilder[UriOrCurie]
      imports.addOne(Uri(linkmlNs.concat("types")))
      // Import `Any` from LinkML so schemas that import each other don't each define their own.
      if (usesAny) imports.addOne(Uri(linkmlNs.concat("extended_types")))
      importRefs.foreach(r => imports.addOne(importOf(r)))
      val classMap = VectorMap.newBuilder[String, ClassDefinition]
      keptClasses.foreach(c => classMap.addOne(c.name -> c))
      val slotMap = VectorMap.newBuilder[String, SlotDefinition]
      keptSlots.foreach(s => slotMap.addOne(s.name -> s))
      val enumMap = VectorMap.newBuilder[String, EnumDefinition]
      builtEnums.foreach(e => if (!importedNames(e.name)) enumMap.addOne(e.name -> e))
      val typeMap = VectorMap.newBuilder[String, TypeDefinition]
      keptTypes.foreach(t => typeMap.addOne(t.name -> t))
      val schema = doc.metadata.copy(
        version = doc.metadata.version.orElse(ontology.versionIri),
        inLanguage = doc.metadata.inLanguage.orElse(reader.language),
      ).applyTo(
        SchemaDefinition(
          id = Uri(schemaId),
          name = name,
          prefixes = declaredPrefixes.result(),
          defaultPrefix = Some(naming.defaultPrefix),
          defaultRange = Some(Reference("string")),
          imports = imports.result(),
          classes = classMap.result(),
          slotDefinitions = slotMap.result(),
          enums = enumMap.result(),
          types = typeMap.result(),
          annotations = doc.annotations,
        ),
      )
      // Keep only used prefixes. Some were made for terms that ended up not needing one, such as
      // imported terms.
      // The id and the namespaces are of type `uri`, so they are written in full.
      val used = mutable.HashSet.empty[String]
      addPrefixesIn(schema.copy(id = Uri(""), prefixes = VectorMap.empty), used)
      used.addAll(config.prefixMap.keysIterator)
      used.addOne(naming.defaultPrefix)
      val prefixes = schema.prefixes.filter(e => used(e._1))
      prefixes.valuesIterator.foreach { p =>
        val ns = p.prefixReference.original
        if (!UriCurieValidator.isValidUri(ns))
          throw new SchemaConversionException(
            s"The namespace <$ns> of the prefix '${p.prefixPrefix}' isn't a valid URI",
          )
      }
      val report = Vector.newBuilder[String]
      warningKinds.foreach { kind =>
        val details = mutable.ArrayBuffer.empty[String]
        val seen = mutable.HashSet.empty[String]
        warnings(kind).foreach(d => if (seen.add(d)) details.addOne(d))
        val line = new java.lang.StringBuilder(kind).append(" (").append(details.length)
          .append("): ")
        var i = 0
        while (i < details.length && i < 5) {
          if (i > 0) line.append("; ")
          line.append(details(i))
          i += 1
        }
        if (details.length > 5) line.append("; and ").append(details.length - 5).append(" more")
        report.addOne(line.toString)
      }
      Result(schema.copy(prefixes = prefixes), report.result())
    }

    /** Adds the prefixes of the CURIEs anywhere in a value, and of the URIs that are written as
      * CURIEs.
      */
    private def addPrefixesIn(value: Any, out: mutable.HashSet[String]): Unit = value match {
      case u: Uri =>
        val p = naming.prefixOrNull(u.original)
        if (p ne null) out.addOne(p)
      case c: Curie => out.addOne(beforeColon(c.original))
      // Text can be a CURIE too, such as the key of an annotation.
      case t: String => if (t.indexOf(':') > 0) out.addOne(beforeColon(t))
      case m: collection.Map[?, ?] =>
        m.foreach { e =>
          addPrefixesIn(e._1, out)
          addPrefixesIn(e._2, out)
        }
      case i: Iterable[?] => i.foreach(addPrefixesIn(_, out))
      case o: Option[?] => if (o.isDefined) addPrefixesIn(o.get, out)
      case p: Product => p.productIterator.foreach(addPrefixesIn(_, out))
      case _ =>
    }

    // Rendering what could not be imported

    private def short(iri: String): String = curie(iri, record = false)

    private val render = OwlRender(short)
  }
}
