package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.SchemaImporter
import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.owl.ClassExpr.Bound
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
import eu.neverblink.linkml.generator.owl.config.{NameStyle, OwlImportConfigImpl, SomeValuesFrom}
import eu.neverblink.linkml.generator.owl.OwlImportNames.*
import eu.neverblink.linkml.generator.rdf.RdfFormat
import eu.neverblink.linkml.generator.util.JsonOutputFormat
import eu.neverblink.linkml.metamodel.{Annotation as _, *}
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.runtime.*
import eu.neverblink.linkml.schemaview.{
  Case,
  FileSystemImporter,
  Importer,
  LinkmlPattern,
  SchemaView,
}
import eu.neverblink.linkml.validation.{SchemaImportError, SchemaParseError}

import java.io.InputStream

import scala.collection.immutable.VectorMap
import scala.collection.mutable
import scala.util.control.NonFatal

/** Reads an OWL ontology (Turtle or N-Triples) and turns it into a LinkML schema.
  *
  * It reverses [[OwlGenerator]]. Use [[config.OwlImportConfig]] to adjust it for ontologies written
  * in other styles. In short:
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
  */
class OwlImporter extends SchemaImporter[OwlImporter.Options] {
  import OwlImporter.*

  override protected def defaultOptions: Options = Options()

  override def importSchema(in: InputStream, options: Options = Options()): SchemaDefinitionImpl =
    importWithWarnings(in, options).schema

  def importWithWarnings(in: InputStream, options: Options = Options()): Result = {
    val sink = CollectingRdfSink()
    options.inputFormat match {
      case RdfFormat.ttl => TurtleParser.parse(in, sink)
      case RdfFormat.nt => NTriplesParser.parse(in, sink)
    }
    val read = OwlRdfReader.read(sink.triples)
    // Document prefixes override those from `sh:declare`. LinkML has no empty prefix, so skip it.
    val prefixes = sink.namespaces.collect { case Namespace(p, ns) if p.nonEmpty => p -> ns }
    val ontology = read.ontology.copy(prefixes = read.ontology.prefixes ++ prefixes)
    importOntology(ontology, options, read.unparsed)
  }

  def importTriples(
      triples: Seq[Triple],
      config: OwlImportConfigImpl = OwlImportConfigImpl(),
  ): Result =
    importTriples(triples, Options(config))

  def importTriples(triples: Seq[Triple], options: Options): Result = {
    val read = OwlRdfReader.read(triples)
    importOntology(read.ontology, options, read.unparsed)
  }

  def importOntology(
      ontology: Ontology,
      config: OwlImportConfigImpl = OwlImportConfigImpl(),
      unparsed: Seq[Triple] = Nil,
  ): Result = importOntology(ontology, Options(config), unparsed)

  def importOntology(ontology: Ontology, options: Options, unparsed: Seq[Triple]): Result =
    Conversion(ontology, options, unparsed).result
}

object OwlImporter {

  /** Options for [[OwlImporter]].
    *
    * @param config
    *   How to map the ontology.
    * @param outputFormat
    *   Output serialization format.
    * @param schemas
    *   Loads the LinkML schemas that the config maps `owl:imports` to. Their terms are then
    *   referenced by name instead of being copied into the schema.
    * @param base
    *   Location of the output schema, used to resolve relative `imports`.
    * @param inputFormat
    *   RDF syntax of the ontology. The Turtle parser also reads N-Triples; the N-Triples parser is
    *   only slightly faster.
    */
  final case class Options(
      config: OwlImportConfigImpl = OwlImportConfigImpl(),
      outputFormat: JsonOutputFormat = JsonOutputFormat.yaml,
      schemas: Importer = FileSystemImporter,
      base: String = "",
      inputFormat: RdfFormat = RdfFormat.ttl,
  ) extends SchemaImporter.Options

  /** `warnings` has one line per kind of thing that could not be imported. */
  final case class Result(schema: SchemaDefinitionImpl, warnings: Seq[String])

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
    val integer = Seq(
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
    ).map(_ -> "integer")
    val string = Seq(
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
    ).map(_ -> "string")
    (integer ++ string :+ ("dateTimeStamp" -> "datetime"))
      .map((local, base) => XmlSchema.get(local).value -> base).toMap ++ Map(
      Rdf.get("XMLLiteral").value -> "string",
      Rdf.get("HTML").value -> "string",
      Rdf.get("JSON").value -> "string",
      Owl.get("real").value -> "decimal",
      Owl.get("rational").value -> "decimal",
    )
  }

  private def isStandardVocabulary(iri: String): Boolean =
    iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#") ||
      iri.startsWith("http://www.w3.org/2000/01/rdf-schema#") || iri.startsWith(owlNs)

  /** The slot fields that an OWL range maps to. */
  private final case class SlotRange(
      range: Option[String] = None,
      anyOf: Seq[String] = Nil,
      minimumValue: Option[String] = None,
      maximumValue: Option[String] = None,
      pattern: Option[String] = None,
      data: Boolean = false,
  ) {

    /** True if this is a plain class, enum or type, so it can go in an `any_of`. */
    def isOneElement: Boolean =
      range.isDefined && anyOf.isEmpty && minimumValue.isEmpty && maximumValue.isEmpty &&
        pattern.isEmpty
  }

  private final class Conversion(
      ontology: Ontology,
      options: Options,
      unparsed: Seq[Triple],
  ) {
    private val config = options.config

    private val warnings = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[String]]

    /** Warnings are grouped by kind to keep the report short. */
    private def warn(kind: String, detail: String): Unit =
      warnings.getOrElseUpdate(kind, mutable.ArrayBuffer.empty) += detail

    private val index = OwlAxiomIndex(ontology.axioms)
    import index.*

    private val wellKnownClasses = Set(owlNs + "Thing", owlNs + "Nothing", Rdfs.Resource.value)

    /** Classes that are declared or used. Declarations of RDF, RDFS and OWL terms are ignored
      * (FOAF, for example, declares `rdfs:Class`).
      */
    private val allClasses: Seq[String] =
      (axioms.collect {
        case Declaration(EntityKind.Class, iri, _) if !isStandardVocabulary(iri) => iri
      } ++ expressions.flatMap(classesIn)).distinct.filterNot(wellKnownClasses)

    /** True if the namespace is the ontology IRI, ignoring a trailing `#` or `/`. */
    private def isOntologyNamespace(namespace: String): Boolean =
      ontology.iri.exists(o =>
        namespace.stripSuffix("#").stripSuffix("/") == o.stripSuffix("#").stripSuffix("/"),
      )

    /** Each property and its kind, taken from its declaration or else from how it is used. */
    private val propertyKinds: VectorMap[String, EntityKind] = {
      val out = mutable.LinkedHashMap.empty[String, EntityKind]
      axioms.foreach {
        case Declaration(k @ (EntityKind.ObjectProperty | EntityKind.DataProperty), iri, _) =>
          out(iri) = k
        case _ =>
      }
      val used = expressions.flatMap(propertiesIn) ++ axioms.collect {
        case Range(p, r, _) => p -> r.isInstanceOf[DataRange]
        case Domain(p, _, _) => p -> false
        case Characteristic(p, _, _) => p -> false
        case InverseProperties(a, _, _) => a -> false
        case SubPropertyOf(a, _, _) => a -> false
      }
      used.groupMap(_._1)(_._2).foreach { (p, data) =>
        if !out.contains(p) && !isDeclared(p, EntityKind.AnnotationProperty) then
          out(p) =
            if data.contains(true) then EntityKind.DataProperty else EntityKind.ObjectProperty
      }
      // An undeclared parent property gets its child's kind, so the child can keep it as a parent.
      // Skip parents used as annotations, or they would end up with two kinds.
      val annotationProperties = annotationsOf.values.flatten.map(_.property).toSet ++
        ontology.annotations.map(_.property)
      def addParents(): Unit = {
        val added = axioms.collect {
          case SubPropertyOf(sub, sup, _)
              if !out.contains(sup) && out.get(sub).exists(_ != EntityKind.AnnotationProperty) &&
                !isDeclared(sup, EntityKind.AnnotationProperty) && !annotationProperties(sup) =>
            sup -> out(sub)
        }
        added.foreach((sup, kind) => out.getOrElseUpdate(sup, kind))
        if added.nonEmpty then addParents()
      }
      addParents()
      // Keep the ontology's own annotation properties only if it says something about them. A bare
      // declaration is usually just there for OWL 2 DL.
      val described = annotationsOf.keySet ++ axioms.collect {
        case SubPropertyOf(sub, _, _) => sub
        case Domain(p, _, _) => p
        case Range(p, _, _) => p
      }
      // Skip metadata vocabulary properties, unless the ontology is that vocabulary (e.g. SKOS).
      def ownAnnotationProperty(iri: String) =
        !out.contains(iri) && described(iri) &&
          (!metadataVocabularies.contains(namespaceOf(iri)) || isOntologyNamespace(
            namespaceOf(iri),
          ))
      axioms.foreach {
        case Declaration(EntityKind.AnnotationProperty, iri, _) if ownAnnotationProperty(iri) =>
          out(iri) = EntityKind.AnnotationProperty
        case _ =>
      }
      // RDF, RDFS and OWL properties are defined by their specs, so they never become slots.
      VectorMap.from(out.filterNot((p, _) => isStandardVocabulary(p)))
    }

    // Enums

    /** Enums by class IRI, with their members and whether the members are classes. */
    private val enumMembers: VectorMap[String, (Seq[String], Boolean)] = {
      val out = mutable.LinkedHashMap.empty[String, (Seq[String], Boolean)]
      allClasses.foreach { c =>
        equivalents.getOrElse(c, Nil).collectFirst { case (a, ClassExpr.OneOf(individuals)) =>
          (a, individuals)
        }.foreach { (a, individuals) =>
          take(a)
          out(c) = (individuals, false)
        }
      }
      // A union of plain subclasses becomes an enum whose values are those classes.
      allClasses.filterNot(out.contains).foreach { c =>
        equivalents.getOrElse(c, Nil).collectFirst {
          case (a, ClassExpr.UnionOf(members)) if members.forall {
                case ClassExpr.Named(m) => isPlainSubclass(m, c)
                case _ => false
              } =>
            (a, members.collect { case ClassExpr.Named(m) => m })
        }.foreach { (a, members) =>
          take(a)
          members.foreach(m => subClassOf(m).foreach((s, _) => take(s)))
          out(c) = (members, true)
        }
      }
      if config.enumsFromIndividuals then {
        val usedAsRange = (ranges.values.flatten.map(_._2) ++ expressions.flatMap {
          case ClassExpr.AllValuesFrom(_, f) => Seq(f)
          case ClassExpr.SomeValuesFrom(_, f) => Seq(f)
          case _ => Nil
        }).collect { case ClassExpr.Named(c) => c }.toSet ++
          // Schema.org gives ranges with `schema:rangeIncludes`.
          annotationsOf.values.flatten.collect {
            case Annotation(p, Iri(c)) if rangeIncludes(p) => c
          }
        val members = classAssertions.collect { case ClassAssertion(ClassExpr.Named(c), i, _) =>
          c -> i
        }
          .groupMap(_._1)(_._2)
        // Subclasses of a range count too. E.g. Schema.org's `USNonprofitType` is a subclass of
        // `NonprofitType`, which is used as a range.
        def namedAncestors(c: String): Set[String] = {
          val out = mutable.LinkedHashSet.empty[String]
          def visit(x: String): Unit = subClassOf.getOrElse(x, Nil).foreach {
            case (_, ClassExpr.Named(p)) => if out.add(p) then visit(p)
            case _ =>
          }
          visit(c)
          out.toSet
        }
        members.toSeq.sortBy(_._1).foreach { (c, individuals) =>
          if !out.contains(c) && (usedAsRange(c) || namedAncestors(c).exists(usedAsRange)) &&
            isOnlyASet(c)
          then out(c) = (individuals.distinct, false)
        }
      }
      VectorMap.from(out)
    }

    /** True if the only thing said about `m` is that it is a subclass of `c`. */
    private def isPlainSubclass(m: String, c: String): Boolean =
      subClassOf.getOrElse(m, Nil).map(_._2) == Seq(ClassExpr.Named(c)) &&
        !namedSubclasses.contains(m) && !domainClasses(m) && !inEquivalence(m)

    /** True if nothing is said about the class's members, so it can become an enum. Named parents
      * are allowed but dropped with a warning, since Schema.org's enumerations all have one.
      */
    private def isOnlyASet(c: String): Boolean =
      subClassOf.getOrElse(c, Nil).forall(_._2.isInstanceOf[ClassExpr.Named]) &&
        !namedSubclasses.contains(c) && !domainClasses(c) && onlyNamedEquivalents(c)

    /** True if the class is only equivalent to named classes, which just become mappings. */
    private def onlyNamedEquivalents(c: String): Boolean =
      !inEquivalence(c) || axioms.forall {
        case EquivalentClasses(ops, _) if ops.contains(ClassExpr.Named(c)) =>
          ops.forall(_.isInstanceOf[ClassExpr.Named])
        case _ => true
      }

    private val enumIndividuals: Set[String] =
      enumMembers.values.filterNot(_._2).flatMap(_._1).toSet

    /** If the IRI is also a permissible value (as in Schema.org and D3FEND), its `skos:notation`
      * belongs to the value, so it is left out here.
      */
    private def termDocumentation(iri: String, name: String) =
      if !enumIndividuals(iri) then reader.documentation(iri, name)
      else
        reader.documentation(
          annotationsOf.getOrElse(iri, Nil).filterNot(_.property == notationProperty),
          name,
          nameIsLabel = iri.startsWith(naming.defaultNamespace),
        )

    private val classes: Seq[String] =
      allClasses.filterNot(c =>
        enumMembers.contains(c) || enumMembers.values.exists(m => m._2 && m._1.contains(c)),
      )

    // Datatypes

    private val configuredTypes: Map[String, String] =
      config.datatypeMap.map((k, v) => expand(k, ontology.prefixes ++ config.prefixMap) -> v)

    /** Datatypes that are declared, defined or used in a range, minus those LinkML already has. */
    private val datatypes: Seq[String] = {
      (axioms.collect { case Declaration(EntityKind.Datatype, iri, _) => iri } ++
        axioms.flatMap {
          case Range(_, r, _) => datatypesIn(r)
          case DatatypeDefinition(d, r, _) => d +: datatypesIn(r)
          case _ => Nil
        } ++ expressions.flatMap(datatypesIn)).distinct
        // ODRL puts classes in data ranges. Those stay classes.
        .filterNot(d =>
          d == Rdfs.Literal.value || builtInTypes.contains(d) || configuredTypes.contains(d) ||
            isDeclared(d, EntityKind.Class),
        )
    }

    // Prefixes and names

    private val naming = OwlImportNames(
      ontology,
      config,
      ownTerms = (classes ++ enumMembers.keys ++ propertyKinds.keys).filter(declared.contains),
      allTerms =
        allClasses ++ propertyKinds.keys ++ datatypes ++ enumMembers.values.flatMap(_._1) ++
          annotationsOf.values.flatten.map(_.property) ++ ontology.annotations.map(_.property),
      warn,
    )
    import naming.{curie, defaultNamespace, ensurePrefix, explicitIri, names}

    /** The LinkML schemas that `owl:imports` map to, for the schema's `imports`. */
    private val importRefs: Seq[String] = ontology.imports.flatMap { i =>
      config.importMap.get(i).orElse {
        warn("Import that is not mapped to a LinkML schema (see `imports` in the config)", i)
        None
      }
    }

    /** Slot ranges from the imported schemas, by slot IRI. */
    private val importedRanges = mutable.Map.empty[String, SlotRange]

    /** Slots of each class in the imported schemas, by class IRI. */
    private val importedSlots = mutable.Map.empty[String, Set[String]]

    /** Terms defined in the imported schemas, by IRI, with their kind and name. The schema refers
      * to them by that name instead of defining its own.
      */
    private val imported: Map[String, (NameGroup, String)] =
      importRefs.flatMap { ref =>
        termsOf(ref) match {
          case Right(terms) => terms
          case Left(reason) =>
            warn(
              "Imported LinkML schema that could not be read, so its terms are copied in",
              s"$ref: $reason",
            )
            Nil
        }
      }.toMap

    private def termsOf(ref: String): Either[String, Seq[(String, (NameGroup, String))]] = {
      val root = SchemaDefinitionImpl(
        id = Uri("urn:linkml-scala:owl-import"),
        name = "owl_import",
        prefixes = VectorMap.from(config.prefixMap.map((p, ns) => p -> PrefixImpl(p, Uri(ns)))),
        imports = Seq(UriOrCurie(ref)),
      )
      try
        SchemaView.loadImports(root, options.base, options.schemas).left.map {
          case e: SchemaImportError => e.reason
          case e: SchemaParseError => e.parserMessage
        }.map { schemas =>
          val sv = SchemaView(root +: schemas)
          def own(el: eu.neverblink.linkml.schemaview.ElementView[?, ?]) =
            !el.definingSchema.id.original.startsWith("https://w3id.org/linkml/")
          sv.slotDefinitions.values.filter(own).foreach { s =>
            s.ancestorsWithSelf.map(_.slot).find(e => e.range.isDefined || e.anyOf.nonEmpty)
              .foreach { e =>
                importedRanges(s.uriStr) = SlotRange(
                  range = e.range.map(_.value),
                  anyOf = e.anyOf.flatMap(_.range).map(_.value),
                )
              }
          }
          sv.classes.values.filter(own).foreach { c =>
            importedSlots(c.uriStr) = c.derivedAttributes.values.map(_.uriStr).toSet
          }
          sv.classes.values.filter(own).map(c => c.uriStr -> (NameGroup.Class, c.name)).toSeq ++
            sv.enums.values.filter(own).map(e => e.uriStr -> (NameGroup.Class, e.name)) ++
            sv.types.values.filter(own).map(t => t.uriStr -> (NameGroup.Type, t.name)) ++
            sv.slotDefinitions.values.filter(own).map(s => s.uriStr -> (NameGroup.Slot, s.name))
        }
      catch { case NonFatal(e) => Left(String.valueOf(e.getMessage)) }
    }

    imported.foreach((iri, term) => naming.reserve(iri, term._2, term._1))
    naming.name(classes ++ enumMembers.keys, NameGroup.Class, config.classStyle)
    naming.name(propertyKinds.keys.toSeq, NameGroup.Slot, config.slotStyle)
    naming.name(datatypes, NameGroup.Type, NameStyle.Keep)

    private val reader = OwlAnnotationReader(annotationsOf, ontology.annotations, config, naming)
    import reader.documentation

    // Types

    private lazy val types: Seq[TypeDefinitionImpl] = datatypes.filterNot(imported.contains).map {
      iri =>
        val name = names(iri)
        val (metadata, annotations) = documentation(iri, name)
        ensurePrefix(iri)
        val (typeof, facets) = datatypeDefinitions.get(iri).map(take) match {
          case Some(DatatypeDefinition(_, DataRange.Restriction(base, facets), _)) =>
            (typeNameFor(base), facets)
          case Some(DatatypeDefinition(_, DataRange.Datatype(base), _)) => (typeNameFor(base), Nil)
          case Some(other) =>
            warn("Datatype definition that LinkML cannot express", s"$iri: ${render(other.range)}")
            ("string", Nil)
          case None => (derivedTypes.getOrElse(iri, "string"), Nil)
        }
        metadata.applyTo(
          TypeDefinitionImpl(
            name = name,
            typeUri = Some(curie(iri)),
            typeof = Some(Reference(typeof)),
            annotations = annotations,
          ).copy(
            minimumValue = facet(facets, XmlSchema.minInclusive),
            maximumValue = facet(facets, XmlSchema.maxInclusive),
            pattern = facets.collectFirst {
              case (f, Literal(v, _)) if f == XmlSchema.pattern.value =>
                LinkmlPattern.fromXsd(v).linkml
            },
          ),
        )
    }

    private def facet(facets: Seq[(String, Literal)], which: Iri): Option[LinkmlAny] =
      facets.collectFirst { case (f, Literal(v, _)) if f == which.value => LinkmlAny(v) }

    private def typeNameFor(iri: String): String =
      configuredTypes.get(iri)
        .orElse(builtInTypes.get(iri))
        .orElse(names.get(iri))
        .getOrElse("string")

    // Ranges

    private def any(data: Boolean): SlotRange = SlotRange(range = Some(anyClass), data = data)

    /** The slot range for a range or restriction filler, or None if LinkML can't express it. */
    private def slotRange(f: Filler): Option[SlotRange] = f match {
      case ClassExpr.Named(iri) if wellKnownClasses(iri) => Some(any(false))
      case ClassExpr.Named(iri) => names.get(iri).map(n => SlotRange(range = Some(n)))
      case DataRange.Datatype(iri) if iri == Rdfs.Literal.value => Some(any(true))
      // ODRL puts classes in data ranges. LinkML can't express that so it maps back to OWL.
      case DataRange.Datatype(iri) if isDeclared(iri, EntityKind.Class) => None
      case DataRange.Datatype(iri) => Some(SlotRange(range = Some(typeNameFor(iri)), data = true))
      case DataRange.Restriction(base, facets) =>
        val known =
          Set(XmlSchema.minInclusive.value, XmlSchema.maxInclusive.value, XmlSchema.pattern.value)
        Option.when(facets.forall(f => known(f._1)))(
          SlotRange(
            range = Some(typeNameFor(base)),
            minimumValue = facets.collectFirst {
              case (f, Literal(v, _)) if f == XmlSchema.minInclusive.value => v
            },
            maximumValue = facets.collectFirst {
              case (f, Literal(v, _)) if f == XmlSchema.maxInclusive.value => v
            },
            pattern = facets.collectFirst {
              case (f, Literal(v, _)) if f == XmlSchema.pattern.value =>
                LinkmlPattern.fromXsd(v).linkml
            },
            data = true,
          ),
        )
      case ClassExpr.UnionOf(ops) =>
        val alternatives = ops.map(slotRange)
        Option.when(alternatives.forall(_.exists(_.isOneElement)))(
          SlotRange(anyOf = alternatives.flatMap(_.flatMap(_.range))),
        )
      case DataRange.UnionOf(ops) =>
        val alternatives = ops.map(slotRange)
        Option.when(alternatives.forall(_.exists(_.isOneElement)))(
          SlotRange(anyOf = alternatives.flatMap(_.flatMap(_.range)), data = true),
        )
      case _ => None
    }

    /** The range of each property, before any class restrictions. */
    private lazy val slotRanges: Map[String, SlotRange] = propertyKinds.map { (p, kind) =>
      val stated = ranges.getOrElse(p, Nil)
      val range = stated match {
        case Seq() => None
        case Seq((a, r)) if isVacuous(r) && hasKnownParent(p) =>
          // Drop it: the subproperty inherits its parent's range, which this repeats or widens.
          take(a)
          None
        case Seq((a, r)) =>
          val sr = slotRange(r)
          if sr.isDefined then take(a)
          else warn("Range that LinkML cannot express", s"${names(p)}: ${render(r)}")
          sr
        case many =>
          warn(
            "Several ranges (all of them apply), of which only the first is kept",
            s"${names(p)}: ${many.map(r => render(r._2)).mkString(", ")}",
          )
          take(many.head._1)
          slotRange(many.head._2)
      }
      p -> range.getOrElse {
        // Leave the range empty so a subproperty inherits it from its parent.
        if hasKnownParent(p) then SlotRange(data = kind == EntityKind.DataProperty)
        else any(kind == EntityKind.DataProperty)
      }
    }.toMap

    private def hasKnownParent(p: String): Boolean =
      superProperties.getOrElse(p, Nil).exists((_, sup) => propertyKinds.contains(sup))

    private def isVacuous(f: Filler): Boolean = f match {
      case ClassExpr.Named(iri) => wellKnownClasses(iri)
      case DataRange.Datatype(iri) => iri == Rdfs.Literal.value
      case _ => false
    }

    /** The property's own range, or else the first one it inherits from a parent. */
    private def effectiveRange(p: String, seen: Set[String] = Set.empty): SlotRange = {
      val own = importedRanges.getOrElse(p, slotRanges(p))
      if own.range.isDefined || own.anyOf.nonEmpty || seen(p) then own
      else
        superProperties.getOrElse(p, Nil).map(_._2).find(propertyKinds.contains)
          .fold(own)(sup => effectiveRange(sup, seen + p))
    }

    // Slots

    /** The inverse of each property, in both directions. */
    private lazy val inverseOf: Map[String, String] =
      index.inverses.map(take).flatMap(a => Seq(a.first -> a.second, a.second -> a.first)).toMap

    private lazy val slots: Seq[SlotDefinitionImpl] =
      propertyKinds.toSeq.filterNot((p, _) => imported.contains(p)).map { (p, kind) =>
        val name = names(p)
        val (metadata, annotations) = documentation(p, name)
        val characteristics =
          characteristicsOf.getOrElse(p, Nil).map(take).map(_.characteristic).toSet
        val range = slotRanges(p)
        val parents = superProperties.getOrElse(p, Nil)
          .filter((a, sup) => {
            val known = propertyKinds.contains(sup)
            take(a)
            if !known then
              warn("Subproperty of an annotation or built-in property", s"$name: ${short(sup)}")
            known
          }).map(_._2)
        val (isA, mixins) = chooseParents(p, parents)
        // LinkML slots inherit `multivalued`, and `false` cannot override it.
        val inheritsMultivalued = config.multivalued && parents.exists(sup => !isFunctional(sup))
        if inheritsMultivalued && characteristics.contains(PropertyCharacteristic.Functional) then
          warn(
            "Functional subproperty of a property that is not, left multivalued as LinkML slots inherit it",
            name,
          )
        // `domain` takes one class. With a union or several domains, the slot is added to each
        // class instead (see `slotsByDomain`).
        val domain = domains.getOrElse(p, Nil).collectFirst {
          case (_, ClassExpr.Named(d)) if names.contains(d) && classParents.contains(d) =>
            Reference[ClassDefinition](names(d))
        }
        val domainOf = domains.getOrElse(p, Nil).collectFirst {
          case (_, ClassExpr.UnionOf(ops)) if ops.forall {
                case ClassExpr.Named(d) => classParents.contains(d)
                case _ => false
              } =>
            ops.collect { case ClassExpr.Named(d) => Reference[ClassDefinition](names(d)) }
        }.getOrElse(Nil)
        if characteristics.contains(PropertyCharacteristic.InverseFunctional) then
          warn("Inverse functional property, which LinkML cannot say", name)
        chains.getOrElse(p, Nil).map(take).foreach { a =>
          warn(
            "Property chain, which LinkML cannot say",
            s"$name: ${a.chain.map(r => render(ClassExpr.Named(r.iri))).mkString(" o ")}",
          )
        }
        val equivalent = equivalentProperties.getOrElse(p, Nil).map { (a, o) =>
          take(a)
          o
        }
        // The property kind that the range implies.
        val inferred = if range.data then EntityKind.DataProperty else EntityKind.ObjectProperty
        val implements =
          if kind == EntityKind.AnnotationProperty then Seq("owl:AnnotationProperty")
          // Mark a data property explicitly unless its range is a type. `Any` or an inherited
          // range doesn't show that it is a data property.
          else if kind == EntityKind.DataProperty &&
            (!range.data || range.range.forall(_ == anyClass))
          then Seq("owl:DatatypeProperty")
          else if kind == EntityKind.ObjectProperty && inferred != kind then
            Seq("owl:ObjectProperty")
          else Nil
        // Registers the `owl` prefix used in `implements`.
        if implements.nonEmpty then curie(owlNs + "DatatypeProperty")

        metadata.applyTo(
          SlotDefinitionImpl(
            name = name,
            slotUri = explicitIri(p, NameGroup.Slot),
            range =
              if kind == EntityKind.AnnotationProperty then
                ranges.get(p).flatMap(_.headOption).flatMap(r => slotRange(r._2)).flatMap(
                  _.range,
                ).map(Reference(_))
              else range.range.map(Reference(_)),
            anyOf = range.anyOf.map(r => AnonymousSlotExpressionImpl(range = Some(Reference(r)))),
            multivalued = kind != EntityKind.AnnotationProperty &&
              (inheritsMultivalued || config.multivalued && !characteristics.contains(
                PropertyCharacteristic.Functional,
              )),
            domain = domain,
            domainOf = domainOf,
            inverse = inverseOf.get(p).filter(names.contains).map(i => Reference(names(i))),
            isA = isA.map(Reference(_)),
            mixins = mixins.map(Reference(_)),
            transitive = characteristics.contains(PropertyCharacteristic.Transitive),
            symmetric = characteristics.contains(PropertyCharacteristic.Symmetric),
            asymmetric = characteristics.contains(PropertyCharacteristic.Asymmetric),
            reflexive = characteristics.contains(PropertyCharacteristic.Reflexive),
            irreflexive = characteristics.contains(PropertyCharacteristic.Irreflexive),
            implements = implements.map(UriOrCurie(_)),
            annotations = annotations,
          ).copy(
            minimumValue = range.minimumValue.map(LinkmlAny(_)),
            maximumValue = range.maximumValue.map(LinkmlAny(_)),
            pattern = range.pattern,
          ),
        ).copy(exactMappings = (metadata.exactMappings ++ equivalent.map(e => {
          ensurePrefix(e); curie(e)
        })).distinctBy(_.original).sortBy(_.original))
      }

    /** Splits named parents into `is_a` and `mixins`. `is_a` prefers a parent in the child's
      * namespace.
      */
    private def chooseParents(
        child: String,
        parents: Seq[String],
    ): (Option[String], Seq[String]) = {
      val named = parents.distinct.filter(names.contains).sortBy(p =>
        (namespaceOf(p) != namespaceOf(child), p),
      )
      (named.headOption.map(names), named.drop(1).map(names))
    }

    // Classes

    /** The named parents of each class, minus any that would create a cycle. */
    private lazy val classParents: Map[String, Seq[String]] = {
      val raw = classes.map { c =>
        c -> subClassOf.getOrElse(c, Nil).flatMap {
          case (a, ClassExpr.Named(p)) if names.contains(p) && p != c && !enumMembers.contains(p) =>
            take(a)
            Some(p)
          case (a, ClassExpr.Named(p)) if wellKnownClasses(p) =>
            // Every class is a subclass of owl:Thing anyway.
            take(a)
            None
          case _ => None
        }.distinct
      }.toMap
      // Equivalent classes are subclasses of each other. LinkML doesn't allow cycles, so drop any
      // parent that leads back to the child.
      val out = mutable.HashMap.empty[String, Seq[String]]
      def reaches(from: String, target: String, seen: Set[String]): Boolean =
        from == target || (!seen(from) && out.getOrElse(from, Nil).exists(
          reaches(_, target, seen + from),
        ))
      classes.foreach { c =>
        out(c) = raw(c).filterNot { p =>
          val cycle = reaches(p, c, Set.empty)
          if cycle then
            warn("Subclass cycle, broken by leaving out a parent", s"${names(c)} -> ${names(p)}")
          cycle
        }
      }
      out.toMap
    }

    /** The children of each class, by `is_a` only (not mixins). */
    private lazy val childrenOf: Map[String, Seq[String]] =
      classes.flatMap(k => chooseParents(k, classParents(k))._1.map(_ -> k))
        .map((parentName, k) => naming.iriOf(parentName) -> k).groupMap(_._1)(_._2)

    /** The `is_a` parent shared by all the classes, if any. */
    private def commonParent(members: Seq[String]): Option[String] =
      members.map(m => chooseParents(m, classParents(m))._1).distinct match {
        case Seq(Some(p)) => Some(naming.iriOf(p))
        case _ => None
      }

    private def ancestors(c: String): Set[String] = {
      val out = mutable.LinkedHashSet.empty[String]
      def visit(x: String): Unit =
        classParents.getOrElse(x, Nil).foreach(p => if out.add(p) then visit(p))
      visit(c)
      out.toSet
    }

    /** Slots attached to a class through `rdfs:domain`. */
    private lazy val slotsByDomain: Map[String, Seq[String]] = propertyKinds.keys.toSeq.flatMap {
      p =>
        domains.getOrElse(p, Nil).flatMap { (a, d) =>
          val members = d match {
            case ClassExpr.Named(c) if wellKnownClasses(c) =>
              take(a)
              Nil
            case ClassExpr.Named(c) =>
              take(a)
              if domains(p).sizeIs > 1 then
                warn(
                  "Several domains (all of them apply), attached to each",
                  s"${names(p)}: ${names.getOrElse(c, c)}",
                )
              Seq(c)
            case ClassExpr.UnionOf(ops) if ops.forall(_.isInstanceOf[ClassExpr.Named]) =>
              take(a)
              ops.collect { case ClassExpr.Named(c) => c }
            case other =>
              warn("Domain that LinkML cannot express", s"${names(p)}: ${render(other)}")
              Nil
          }
          members.filter(classParents.contains).map(_ -> p)
        }
    }.groupMap(_._1)(_._2)

    private lazy val classDefinitions: Seq[ClassDefinitionImpl] =
      classes.filterNot(imported.contains).map(buildClass)

    private def buildClass(c: String): ClassDefinitionImpl = {
      val name = names(c)
      val (metadata, annotations) = termDocumentation(c, name)
      val parents = classParents(c)
      val (isA, mixins) = chooseParents(c, parents)
      val notes = mutable.ArrayBuffer.empty[String]
      val usage = mutable.LinkedHashMap.empty[String, SlotDefinitionImpl]
      val attached = mutable.LinkedHashSet.from(slotsByDomain.getOrElse(c, Nil))
      var isAbstract = false
      var exact = Seq.empty[UriOrCurie]
      var disjoint = Seq.empty[String]

      def unsupported(kind: String, expr: ClassExpr): Unit = {
        val text = render(expr)
        warn(kind, s"$name: $text")
        notes += s"OWL: $text"
      }

      def restrict(p: String, f: SlotDefinitionImpl => SlotDefinitionImpl): Unit = {
        attached += p
        val n = names(p)
        val before = usage.getOrElse(n, SlotDefinitionImpl(name = n))
        val after = f(before)
        if after != SlotDefinitionImpl(name = n) then usage(n) = after
      }

      /** True if the filler adds nothing to the property's own range. */
      def coversRange(p: String, filler: Filler): Boolean = filler match {
        case ClassExpr.Named(iri) if wellKnownClasses(iri) => true
        case DataRange.Datatype(iri) if iri == Rdfs.Literal.value => true
        case other =>
          slotRange(other).exists(_.copy(data = false) == effectiveRange(p).copy(data = false))
      }

      /** True if the expressions are exactly the class's children, so the children cover it. */
      def isUnionOfChildren(ops: Seq[ClassExpr]): Boolean =
        ops.forall(_.isInstanceOf[ClassExpr.Named]) &&
          ops.collect { case ClassExpr.Named(o) => o }.toSet == childrenOf.getOrElse(c, Nil).toSet

      /** True if the slot holds at most one value, so a max cardinality of 1 adds nothing. */
      def singleValuedSlot(p: String) = isFunctional(p) || !config.multivalued

      def superclass(expr: ClassExpr): Unit = expr match {
        case ClassExpr.Named(_) => // handled with the parents
        case ClassExpr.IntersectionOf(ops) => ops.foreach(superclass)
        case ClassExpr.AllValuesFrom(PropertyRef(p, false), filler) if propertyKinds.contains(p) =>
          if coversRange(p, filler) then attached += p
          else
            slotRange(filler) match {
              case Some(r) =>
                restrict(
                  p,
                  s =>
                    s.copy(
                      range = r.range.map(Reference(_)),
                      anyOf =
                        r.anyOf.map(x => AnonymousSlotExpressionImpl(range = Some(Reference(x)))),
                      minimumValue = r.minimumValue.map(LinkmlAny(_)),
                      maximumValue = r.maximumValue.map(LinkmlAny(_)),
                      pattern = r.pattern,
                    ),
                )
              case None => unsupported("Restriction that LinkML cannot express", expr)
            }
        case ClassExpr.SomeValuesFrom(PropertyRef(p, false), filler) if propertyKinds.contains(p) =>
          if coversRange(p, filler) then restrict(p, _.copy(required = true))
          else
            (config.someValuesFromStyle, slotRange(filler)) match {
              case (SomeValuesFrom.Range, Some(r)) =>
                restrict(p, _.copy(required = true, range = r.range.map(Reference(_))))
              case (SomeValuesFrom.Required, Some(r)) if r.range.isDefined =>
                restrict(p, withMember(_, r.range))
              case _ => unsupported("Restriction that LinkML cannot express", expr)
            }
        case ClassExpr.Cardinality(bound, n, PropertyRef(p, false), filler)
            if propertyKinds.contains(p) =>
          val qualifiedNarrower = filler.exists(f => !coversRange(p, f))
          if qualifiedNarrower then
            (config.someValuesFromStyle, filler.flatMap(slotRange)) match {
              case (SomeValuesFrom.Range, Some(r)) =>
                cardinality(p, bound, n, singleValuedSlot(p), restrict)
                restrict(p, _.copy(range = r.range.map(Reference(_))))
              case (_, Some(r)) if bound == Bound.Min && n == 1 && r.range.isDefined =>
                restrict(p, withMember(_, r.range))
              case _ => unsupported("Qualified cardinality that LinkML cannot express", expr)
            }
          else cardinality(p, bound, n, singleValuedSlot(p), restrict)
        case ClassExpr.HasValue(PropertyRef(p, false), value) if propertyKinds.contains(p) =>
          val text = value match {
            case Iri(v) => enumValueText(p, v).getOrElse { ensurePrefix(v); curie(v).original }
            case Literal(v, _) => v
            case LanguageLiteral(v, _) => v
          }
          restrict(p, _.copy(equalsString = Some(text)))
        case ClassExpr.UnionOf(ops) if isUnionOfChildren(ops) => isAbstract = true
        case other => unsupported("Superclass that LinkML cannot express", other)
      }

      subClassOf.getOrElse(c, Nil).foreach { (a, sup) =>
        if !sup.isInstanceOf[ClassExpr.Named] then {
          take(a)
          superclass(sup)
        }
      }
      equivalents.getOrElse(c, Nil).foreach { (a, other) =>
        take(a)
        other match {
          case ClassExpr.Named(o) =>
            ensurePrefix(o)
            exact = exact :+ curie(o)
          case expr =>
            // Keep it as a superclass. LinkML can't say that it is also a sufficient condition.
            warn(
              "Class defined by an equivalence, kept as superclasses only",
              s"$name: ${render(expr)}",
            )
            superclass(expr)
        }
      }
      val children = childrenOf.getOrElse(c, Nil).toSet
      // A disjoint pair goes on the first class. A larger group goes on the parent if it is
      // exactly the parent's children, otherwise on each member.
      disjointByClass.getOrElse(c, Nil).foreach {
        case a @ DisjointClasses(Seq(ClassExpr.Named(`c`), ClassExpr.Named(o)), _)
            if classParents.contains(o) =>
          take(a)
          disjoint = disjoint :+ names(o)
        case a @ DisjointClasses(ops, _)
            if ops.sizeIs > 2 && ops.forall(_.isInstanceOf[ClassExpr.Named]) =>
          val members = ops.collect { case ClassExpr.Named(o) => o }
          // That case is handled below, with `children_are_mutually_disjoint` on the parent.
          val allChildren =
            commonParent(members).exists(p => childrenOf.getOrElse(p, Nil).toSet == members.toSet)
          if members.forall(classParents.contains) && !allChildren then {
            take(a)
            disjoint = disjoint ++ members.filterNot(_ == c).map(names)
          }
        case _ =>
      }
      val disjointChildren = disjointByClass.values.flatten.exists {
        case a @ DisjointClasses(ops, _) if ops.sizeIs > 2 && children.nonEmpty =>
          val members = ops.collect { case ClassExpr.Named(o) => o }.toSet
          val matches = members == children && children.size == ops.size
          if matches then take(a)
          matches
        case _ => false
      } || disjointUnions.get(c).exists { a =>
        take(a)
        isAbstract = true
        true
      }
      val keys = keysByClass.getOrElse(c, Nil).collect {
        case a @ HasKey(_, properties, _) if properties.forall(names.contains) =>
          take(a)
          properties.map(names)
      }

      // List only slots that no ancestor already has, here or in an imported schema.
      val inherited = ancestors(c).flatMap(a =>
        slotsByDomain.getOrElse(a, Nil) ++ importedSlots.getOrElse(a, Set.empty),
      )
      val own = attached.toSeq.filterNot(inherited.contains).map(names)

      metadata.applyTo(
        ClassDefinitionImpl(
          name = name,
          classUri = explicitIri(c, NameGroup.Class),
          isA = isA.map(Reference(_)),
          mixins = mixins.map(Reference(_)),
          slots = own.map(Reference(_)),
          slotUsage = VectorMap.from(usage),
          `abstract` = isAbstract,
          childrenAreMutuallyDisjoint = disjointChildren,
          disjointWith = disjoint.map(Reference(_)),
          uniqueKeys = VectorMap.from(keys.zipWithIndex.map { (slots, i) =>
            val keyName = if i == 0 then s"${name}_key" else s"${name}_key_${i + 1}"
            keyName -> UniqueKeyImpl(
              uniqueKeyName = keyName,
              uniqueKeySlots = slots.map(Reference(_)),
            )
          }),
          annotations = annotations,
        ),
      ).copy(
        exactMappings = (metadata.exactMappings ++ exact).distinctBy(_.original).sortBy(_.original),
        notes = metadata.notes ++ notes.map(PlainText(_)),
      )
    }

    /** Adds "at least one value is a `range`" to a slot. Uses `has_member`, or an `all_of` entry if
      * `has_member` is already taken.
      */
    private def withMember(slot: SlotDefinitionImpl, range: Option[String]): SlotDefinitionImpl = {
      val member = AnonymousSlotExpressionImpl(range = range.map(Reference(_)))
      if slot.hasMember.isEmpty then slot.copy(required = true, hasMember = Some(member))
      else if slot.hasMember.contains(member) || slot.allOf.exists(_.hasMember.contains(member))
      then slot
      else slot.copy(allOf = slot.allOf :+ AnonymousSlotExpressionImpl(hasMember = Some(member)))
    }

    /** Turns a cardinality restriction into `slot_usage`, skipping what the slot already says. */
    private def cardinality(
        p: String,
        bound: Bound,
        n: Int,
        functional: Boolean,
        restrict: (String, SlotDefinitionImpl => SlotDefinitionImpl) => Unit,
    ): Unit = (bound, n) match {
      case (Bound.Min, 0) => restrict(p, identity)
      case (Bound.Min, 1) => restrict(p, _.copy(required = true))
      case (Bound.Min, k) => restrict(p, _.copy(minimumCardinality = Some(k)))
      case (Bound.Max, 1) if functional => restrict(p, identity)
      case (Bound.Max, k) => restrict(p, _.copy(maximumCardinality = Some(k)))
      case (Bound.Exact, 1) if functional => restrict(p, _.copy(required = true))
      case (Bound.Exact, 1) => restrict(p, _.copy(required = true, maximumCardinality = Some(1)))
      case (Bound.Exact, k) => restrict(p, _.copy(exactCardinality = Some(k)))
    }

    /** The individual's permissible value, if the property's range is an enum that has it. */
    private def enumValueText(p: String, individual: String): Option[String] =
      slotRanges.get(p).flatMap(_.range).flatMap { r =>
        enumMembers.find((e, _) => names.get(e).contains(r)).flatMap { (e, m) =>
          Option.when(m._1.contains(individual))(permissibleValueText(e, individual))
        }
      }

    // Enums

    private lazy val pvTexts = mutable.HashMap.empty[(String, String), String]

    /** Permissible value text: the `skos:notation` (where the generator writes it), else a label
      * that the IRI was built from (as `gen-owl` does), else the IRI's local name.
      */
    private def permissibleValueText(e: String, member: String): String =
      pvTexts.getOrElseUpdate(
        (e, member), {
          val annotations = annotationsOf.getOrElse(member, Nil)
          val notation = annotations.collectFirst {
            case Annotation(p, Literal(v, _)) if p == notationProperty => v
          }
          val labels = annotations.collect {
            case Annotation(p, Literal(v, _)) if p == Rdfs.label.value => v
          }
          notation
            .orElse(labels.find(l => defaultMeaning(e, l) == member))
            .getOrElse(styled(localName(member), config.permissibleValueStyle))
        },
      )

    private def defaultMeaning(e: String, text: String): String =
      Uri.synthetic(
        defaultNamespace,
        Case.baseToPascal(Case.base(names(e))) + "." + Case.base(text).toUpperCase,
      ).original

    private lazy val enums: Seq[EnumDefinitionImpl] =
      enumMembers.toSeq.filterNot((e, _) => imported.contains(e)).map { (e, members) =>
        val name = names(e)
        val (metadata, annotations) = termDocumentation(e, name)
        val values = members._1.map { m =>
          val text = permissibleValueText(e, m)
          val (pvMetadata, pvAnnotations) = documentation(
            annotationsOf.getOrElse(m, Nil).filterNot(a =>
              a.property == notationProperty && a.value == Literal(text),
            ),
            text,
            nameIsLabel = m.startsWith(defaultNamespace),
          )
          if members._2 then subClassOf.getOrElse(m, Nil).foreach((a, _) => take(a))
          else
            assertionsByIndividual.getOrElse(m, Nil).foreach {
              case a @ ClassAssertion(ClassExpr.Named(`e`), _, _) => take(a)
              case _ =>
            }
          declarationsByIri.getOrElse(m, Nil).foreach(take)
          ensurePrefix(m)
          text -> pvMetadata.applyTo(
            PermissibleValueImpl(
              text = text,
              meaning = Option.when(m != defaultMeaning(e, text))(curie(m)),
              annotations = pvAnnotations,
            ),
          )
        }
        val parents = parentsOfEnum(e)
        // Named equivalents become mappings, as for classes.
        val exact = equivalents.getOrElse(e, Nil).collect { case (a, ClassExpr.Named(o)) =>
          take(a)
          ensurePrefix(o)
          curie(o)
        }
        metadata.applyTo(
          EnumDefinitionImpl(
            name = name,
            enumUri = explicitIri(e, NameGroup.Class),
            permissibleValues = VectorMap.from(values),
            isA = parents.headOption.map(Reference(_)),
            mixins = parents.drop(1).map(Reference(_)),
            annotations = annotations,
          ),
        ).copy(exactMappings =
          (metadata.exactMappings ++ exact).distinctBy(_.original).sortBy(_.original),
        )
      }

    private def parentsOfEnum(e: String): Seq[String] =
      subClassOf.getOrElse(e, Nil).collect {
        case (a, ClassExpr.Named(p)) if enumMembers.contains(p) =>
          take(a)
          names(p)
        case (a, ClassExpr.Named(p)) if wellKnownClasses(p) =>
          take(a)
          ""
        case (a, ClassExpr.Named(p)) =>
          take(a)
          warn("Superclass of an enum, which LinkML cannot say", s"${names(e)}: ${short(p)}")
          ""
      }.filter(_.nonEmpty)

    // The schema

    private def schemaName: String =
      config.name.orElse(
        ontology.annotations.collectFirst {
          case Annotation(p, Literal(v, _))
              if p == Rdfs.label.value && v.matches("[A-Za-z_][A-Za-z0-9_-]*") =>
            v
        },
      ).getOrElse(naming.defaultPrefix)

    /** The term an axiom is about, if any. */
    private def subjectOf(a: Axiom): Option[String] = a match {
      case SubClassOf(ClassExpr.Named(c), _, _) => Some(c)
      case EquivalentClasses(ops, _) => ops.collectFirst { case ClassExpr.Named(c) => c }
      case DisjointUnion(c, _, _) => Some(c)
      case HasKey(ClassExpr.Named(c), _, _) => Some(c)
      case Domain(p, _, _) => Some(p)
      case Range(p, _, _) => Some(p)
      case Characteristic(p, _, _) => Some(p)
      case SubPropertyOf(p, _, _) => Some(p)
      case EquivalentProperties(ops, _) => ops.headOption
      case PropertyChain(p, _, _) => Some(p)
      case DatatypeDefinition(d, _, _) => Some(d)
      case _ => None
    }

    /** True if the ontology says anything about the term besides using it. `rdfs:isDefinedBy`
      * doesn't count.
      */
    private def saysSomethingAbout(iri: String): Boolean =
      annotationsOf.getOrElse(iri, Nil).exists(_.property != Rdfs.isDefinedBy.value) ||
        subClassOf.contains(iri) ||
        equivalents.contains(iri) || keysByClass.contains(iri) || domains.contains(iri) ||
        ranges.contains(iri) || characteristicsOf.contains(iri) || superProperties.contains(iri) ||
        equivalentProperties.contains(iri) || enumMembers.contains(iri) ||
        datatypeDefinitions.contains(iri)

    def result: Result = {
      // Build everything first, since building registers the prefixes the schema needs.
      val (builtTypes, builtSlots, builtClasses, builtEnums) =
        (types, slots, classDefinitions, enums)
      val name = schemaName
      // Skip the VANN preferred namespace and prefix: the schema's default prefix covers them.
      val (metadata, schemaAnnotations) = documentation(
        ontology.annotations.filterNot(a =>
          a.property == vann + "preferredNamespaceUri" || a.property == vann + "preferredNamespacePrefix",
        ),
        name,
      )
      // Statements about imported terms are dropped. The imported schema defines those terms.
      imported.keys.toSeq.sorted.filter(saysSomethingAbout).foreach { iri =>
        warn("Statement about a term of an imported schema, which keeps its own", short(iri))
      }
      val importedNames = imported.values.map(_._2).toSet
      val keptClasses = builtClasses.filterNot(c => importedNames(c.name))
      val keptSlots = builtSlots.filterNot(s => importedNames(s.name))
      // Keep a type only if it is used or declared. Others appear only in dropped axioms.
      def rangesIn(s: SlotDefinition): Seq[String] =
        (s.range.toSeq ++ s.anyOf.flatMap(_.range) ++ s.hasMember.flatMap(_.range) ++
          s.allOf.flatMap(_.hasMember).flatMap(_.range)).map(_.value)
      val usedTypes = (keptSlots.flatMap(rangesIn) ++
        keptClasses.flatMap(c => (c.slotUsage.values ++ c.attributes.values).flatMap(rangesIn)) ++
        builtTypes.flatMap(_.typeof.map(_.value))).toSet
      val keptTypes = builtTypes.filter(t =>
        usedTypes(t.name) || naming.iriOf.get(t.name).exists(isDeclared(_, EntityKind.Datatype)),
      )
      def isAny(s: SlotDefinition) =
        s.range.exists(_.value == anyClass) || s.anyOf.exists(_.range.exists(_.value == anyClass))
      val usesAny = keptSlots.exists(isAny) ||
        keptClasses.exists(c => (c.slotUsage.values ++ c.attributes.values).exists(isAny))
      // Import `Any` from LinkML so schemas that import each other don't each define their own.
      val any = Option.when(usesAny)(UriOrCurie("linkml:extended_types"))

      // Warn about every axiom that was not used.
      unhandled.foreach {
        case _: Declaration =>
        // A declaration alone is not worth reporting.
        case a if subjectOf(a).exists(imported.contains) =>
        case a: ClassAssertion if enumIndividuals(a.individual) =>
        case ClassAssertion(cls, i, _) =>
          warn("Individual, which is not part of an enum", s"$i: ${render(cls)}")
        case a: PropertyAssertion =>
          warn("Property value of an individual", s"${a.individual} ${a.property}")
        case a: SubClassOf => warn("Class axiom that LinkML cannot express", render(a))
        case a => warn(s"${a.productPrefix} axiom that LinkML cannot express", render(a))
      }
      unparsed.groupBy(_.pred.value).toSeq.sortBy(_._1).foreach { (p, ts) =>
        warn("RDF that is not OWL", s"${ts.size} triples with $p")
      }

      val schema = metadata.copy(
        version = metadata.version.orElse(ontology.versionIri),
        inLanguage = metadata.inLanguage.orElse(reader.language),
      ).applyTo(
        SchemaDefinitionImpl(
          id = Uri(
            // Without an ontology IRI, use the namespace. That is how others import such
            // vocabularies (e.g. DC terms).
            config.schemaId.orElse(ontology.iri).getOrElse(defaultNamespace.stripSuffix("#")),
          ),
          name = name,
          prefixes = VectorMap.from(naming.declared.map((p, ns) => p -> PrefixImpl(p, Uri(ns)))),
          defaultPrefix = Some(naming.defaultPrefix),
          defaultRange = Some(Reference("string")),
          imports = (UriOrCurie("linkml:types") +: any.toSeq) ++ importRefs.map(UriOrCurie(_)),
          classes = VectorMap.from(keptClasses.map(c => c.name -> c)),
          slotDefinitions = VectorMap.from(keptSlots.map(s => s.name -> s)),
          enums = VectorMap.from(
            builtEnums.filterNot(e => importedNames(e.name)).map(e => e.name -> e),
          ),
          types = VectorMap.from(keptTypes.map(t => t.name -> t)),
          annotations = schemaAnnotations,
        ),
      )
      // Keep only used prefixes. Some were made for terms that ended up not needing one, such as
      // imported terms.
      val used = prefixesIn(schema).toSet ++ config.prefixMap.keySet + naming.defaultPrefix
      Result(
        schema.copy(prefixes = schema.prefixes.filter((p, _) => used(p))),
        warnings.toSeq.map { (kind, details) =>
          val shown = details.distinct.take(5).mkString("; ")
          val more =
            if details.distinct.size > 5 then s"; and ${details.distinct.size - 5} more" else ""
          s"$kind (${details.distinct.size}): $shown$more"
        },
      )
    }

    /** The prefixes of the CURIEs anywhere in a value. */
    private def prefixesIn(value: Any): Iterator[String] = value match {
      case c: Curie => Iterator(c.original.takeWhile(_ != ':'))
      // Text can be a CURIE too, such as the value of `equals_string`.
      case t: String if t.indexOf(':') > 0 => Iterator(t.takeWhile(_ != ':'))
      case m: collection.Map[?, ?] => m.iterator.flatMap((k, v) => prefixesIn(k) ++ prefixesIn(v))
      case i: Iterable[?] => i.iterator.flatMap(prefixesIn)
      case o: Option[?] => o.iterator.flatMap(prefixesIn)
      case p: Product => p.productIterator.flatMap(prefixesIn)
      case _ => Iterator.empty
    }

    // Rendering what could not be imported

    private def short(iri: String): String = curie(iri, record = false).original

    private val render = OwlRender(short)
  }
}
