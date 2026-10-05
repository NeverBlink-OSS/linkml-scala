package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.metamodel.{Annotation as _, *}
import eu.neverblink.linkml.rdf.{Iri, LanguageLiteral, Literal, Node, XmlSchema}
import eu.neverblink.linkml.runtime.*

/** The documentation and provenance metaslots that OWL supports. These are the ones whose
  * `slot_uri` in the LinkML metamodel is outside the LinkML namespace. Each is written as an
  * annotation with that property, like the Python OWL generator does.
  *
  * [[OwlGenerator]] writes these and [[OwlImporter]] reads them back. The importer's config can map
  * more properties to the same metaslots.
  */
final case class Metadata(
    title: Option[LocalizedText] = None,
    description: Option[LocalizedText] = None,
    aliases: Seq[LocalizedText] = Nil,
    comments: Seq[LocalizedText] = Nil,
    notes: Seq[LocalizedText] = Nil,
    examples: Seq[ExampleImpl] = Nil,
    seeAlso: Seq[UriOrCurie] = Nil,
    source: Option[UriOrCurie] = None,
    contributors: Seq[UriOrCurie] = Nil,
    createdBy: Option[UriOrCurie] = None,
    createdOn: Option[LinkmlDateTime] = None,
    lastUpdatedOn: Option[LinkmlDateTime] = None,
    modifiedBy: Option[UriOrCurie] = None,
    status: Option[UriOrCurie] = None,
    inLanguage: Option[String] = None,
    keywords: Seq[LocalizedText] = Nil,
    categories: Seq[UriOrCurie] = Nil,
    mappings: Seq[UriOrCurie] = Nil,
    exactMappings: Seq[UriOrCurie] = Nil,
    closeMappings: Seq[UriOrCurie] = Nil,
    relatedMappings: Seq[UriOrCurie] = Nil,
    narrowMappings: Seq[UriOrCurie] = Nil,
    broadMappings: Seq[UriOrCurie] = Nil,
    deprecated: Option[LocalizedText] = None,
    /** Only on schemas. */
    license: Option[String] = None,
    /** Only on schemas. */
    version: Option[String] = None,
) {

  def isEmpty: Boolean = this == Metadata.empty

  def applyTo(c: ClassDefinitionImpl): ClassDefinitionImpl = c.copy(
    title = title,
    description = description,
    aliases = aliases,
    comments = comments,
    notes = notes,
    examples = examples,
    seeAlso = seeAlso,
    source = source,
    contributors = contributors,
    createdBy = createdBy,
    createdOn = createdOn,
    lastUpdatedOn = lastUpdatedOn,
    modifiedBy = modifiedBy,
    status = status,
    inLanguage = inLanguage,
    keywords = keywords,
    categories = categories,
    mappings = mappings,
    exactMappings = exactMappings,
    closeMappings = closeMappings,
    relatedMappings = relatedMappings,
    narrowMappings = narrowMappings,
    broadMappings = broadMappings,
    deprecated = deprecated,
  )

  def applyTo(s: SlotDefinitionImpl): SlotDefinitionImpl = s.copy(
    title = title,
    description = description,
    aliases = aliases,
    comments = comments,
    notes = notes,
    examples = examples,
    seeAlso = seeAlso,
    source = source,
    contributors = contributors,
    createdBy = createdBy,
    createdOn = createdOn,
    lastUpdatedOn = lastUpdatedOn,
    modifiedBy = modifiedBy,
    status = status,
    inLanguage = inLanguage,
    keywords = keywords,
    categories = categories,
    mappings = mappings,
    exactMappings = exactMappings,
    closeMappings = closeMappings,
    relatedMappings = relatedMappings,
    narrowMappings = narrowMappings,
    broadMappings = broadMappings,
    deprecated = deprecated,
  )

  def applyTo(e: EnumDefinitionImpl): EnumDefinitionImpl = e.copy(
    title = title,
    description = description,
    aliases = aliases,
    comments = comments,
    notes = notes,
    examples = examples,
    seeAlso = seeAlso,
    source = source,
    contributors = contributors,
    createdBy = createdBy,
    createdOn = createdOn,
    lastUpdatedOn = lastUpdatedOn,
    modifiedBy = modifiedBy,
    status = status,
    inLanguage = inLanguage,
    keywords = keywords,
    categories = categories,
    mappings = mappings,
    exactMappings = exactMappings,
    closeMappings = closeMappings,
    relatedMappings = relatedMappings,
    narrowMappings = narrowMappings,
    broadMappings = broadMappings,
    deprecated = deprecated,
  )

  def applyTo(t: TypeDefinitionImpl): TypeDefinitionImpl = t.copy(
    title = title,
    description = description,
    aliases = aliases,
    comments = comments,
    notes = notes,
    examples = examples,
    seeAlso = seeAlso,
    source = source,
    contributors = contributors,
    createdBy = createdBy,
    createdOn = createdOn,
    lastUpdatedOn = lastUpdatedOn,
    modifiedBy = modifiedBy,
    status = status,
    inLanguage = inLanguage,
    keywords = keywords,
    categories = categories,
    mappings = mappings,
    exactMappings = exactMappings,
    closeMappings = closeMappings,
    relatedMappings = relatedMappings,
    narrowMappings = narrowMappings,
    broadMappings = broadMappings,
    deprecated = deprecated,
  )

  def applyTo(pv: PermissibleValueImpl): PermissibleValueImpl = pv.copy(
    title = title,
    description = description,
    aliases = aliases,
    comments = comments,
    notes = notes,
    examples = examples,
    seeAlso = seeAlso,
    source = source,
    contributors = contributors,
    createdBy = createdBy,
    createdOn = createdOn,
    lastUpdatedOn = lastUpdatedOn,
    modifiedBy = modifiedBy,
    status = status,
    inLanguage = inLanguage,
    keywords = keywords,
    categories = categories,
    mappings = mappings,
    exactMappings = exactMappings,
    closeMappings = closeMappings,
    relatedMappings = relatedMappings,
    narrowMappings = narrowMappings,
    broadMappings = broadMappings,
    deprecated = deprecated,
  )

  def applyTo(s: SchemaDefinitionImpl): SchemaDefinitionImpl = s.copy(
    title = title,
    description = description,
    aliases = aliases,
    comments = comments,
    notes = notes,
    examples = examples,
    seeAlso = seeAlso,
    source = source,
    contributors = contributors,
    createdBy = createdBy,
    createdOn = createdOn,
    lastUpdatedOn = lastUpdatedOn,
    modifiedBy = modifiedBy,
    status = status,
    inLanguage = inLanguage,
    keywords = keywords,
    categories = categories,
    mappings = mappings,
    exactMappings = exactMappings,
    closeMappings = closeMappings,
    relatedMappings = relatedMappings,
    narrowMappings = narrowMappings,
    broadMappings = broadMappings,
    deprecated = deprecated,
    license = license,
    version = version,
  )
}

object Metadata {
  val empty: Metadata = Metadata()

  def of(cm: CommonMetadata): Metadata = Metadata(
    title = cm.title,
    description = cm.description,
    aliases = cm.aliases,
    comments = cm.comments,
    notes = cm.notes,
    examples = cm.examples,
    seeAlso = cm.seeAlso,
    source = cm.source,
    contributors = cm.contributors,
    createdBy = cm.createdBy,
    createdOn = cm.createdOn,
    lastUpdatedOn = cm.lastUpdatedOn,
    modifiedBy = cm.modifiedBy,
    status = cm.status,
    inLanguage = cm.inLanguage,
    keywords = cm.keywords,
    categories = cm.categories,
    mappings = cm.mappings,
    exactMappings = cm.exactMappings,
    closeMappings = cm.closeMappings,
    relatedMappings = cm.relatedMappings,
    narrowMappings = cm.narrowMappings,
    broadMappings = cm.broadMappings,
    deprecated = cm.deprecated,
  )

  def of(schema: SchemaDefinition): Metadata =
    of(schema: CommonMetadata).copy(license = schema.license, version = schema.version)

  /** How the values of a metaslot are written. */
  enum Kind:
    /** Text that may have a language: `rdf:langString` or `xsd:string`. */
    case Text

    case Plain

    /** A URI or CURIE, written as an IRI. */
    case Reference

    case DateTime

    /** Always written as `true`, an `xsd:boolean`. */
    case Flag

  /** A metaslot, its property in the metamodel, and how to get and set its values. */
  final case class Field(
      name: String,
      property: String,
      kind: Kind,
      get: Metadata => Seq[String | LocalizedText | UriOrCurie],
      set: (Metadata, Seq[String | LocalizedText | UriOrCurie]) => Metadata,
  )

  private def strings(values: Seq[String | LocalizedText | UriOrCurie]): Seq[String] = values.map {
    case s: String => s
    case t: LocalizedText => t.plain
    case u: UriOrCurie => u.original
  }

  private def texts(values: Seq[String | LocalizedText | UriOrCurie]): Seq[LocalizedText] =
    values.map {
      case t: LocalizedText => t
      case other => PlainText(strings(Seq(other)).head)
    }

  private def text(values: Seq[String | LocalizedText | UriOrCurie]): Option[LocalizedText] =
    texts(values).headOption

  private def refs(values: Seq[String | LocalizedText | UriOrCurie]): Seq[UriOrCurie] =
    values.map {
      case u: UriOrCurie => u
      case other => UriOrCurie(strings(Seq(other)).head)
    }

  private def textField(
      name: String,
      property: String,
      get: Metadata => Option[LocalizedText],
      set: (Metadata, Option[LocalizedText]) => Metadata,
  ) = Field(name, property, Kind.Text, m => get(m).toSeq, (m, v) => set(m, text(v)))

  private def textsField(
      name: String,
      property: String,
      get: Metadata => Seq[LocalizedText],
      set: (Metadata, Seq[LocalizedText]) => Metadata,
  ) = Field(name, property, Kind.Text, get, (m, v) => set(m, texts(v)))

  private def refsField(
      name: String,
      property: String,
      get: Metadata => Seq[UriOrCurie],
      set: (Metadata, Seq[UriOrCurie]) => Metadata,
  ) = Field(name, property, Kind.Reference, m => get(m).map(_.original), (m, v) => set(m, refs(v)))

  private def refField(
      name: String,
      property: String,
      get: Metadata => Option[UriOrCurie],
      set: (Metadata, Option[UriOrCurie]) => Metadata,
  ) = Field(
    name,
    property,
    Kind.Reference,
    m => get(m).map(_.original).toSeq,
    (m, v) => set(m, refs(v).headOption),
  )

  private def dateField(
      name: String,
      property: String,
      get: Metadata => Option[LinkmlDateTime],
      set: (Metadata, Option[LinkmlDateTime]) => Metadata,
  ) = Field(
    name,
    property,
    Kind.DateTime,
    m => get(m).map(_.value).toSeq,
    (m, v) => set(m, strings(v).headOption.map(LinkmlDateTime(_))),
  )

  private def stringField(
      name: String,
      property: String,
      get: Metadata => Option[String],
      set: (Metadata, Option[String]) => Metadata,
  ) = Field(name, property, Kind.Plain, m => get(m).toSeq, (m, v) => set(m, strings(v).headOption))

  private val dcterms = "http://purl.org/dc/terms/"
  private val skos = "http://www.w3.org/2004/02/skos/core#"
  private val rdfs = "http://www.w3.org/2000/01/rdf-schema#"
  private val pav = "http://purl.org/pav/"
  private val schemaOrg = "http://schema.org/"

  /** All metaslots that are written, with their metamodel property. `description` uses
    * `skos:definition` here, and `rdfs:comment` in the `rdfs` metadata profile.
    */
  val fields: Seq[Field] = Seq(
    textField("title", dcterms + "title", _.title, (m, v) => m.copy(title = v)),
    textField("description", skos + "definition", _.description, (m, v) => m.copy(description = v)),
    textsField("aliases", skos + "altLabel", _.aliases, (m, v) => m.copy(aliases = v)),
    textsField("comments", skos + "note", _.comments, (m, v) => m.copy(comments = v)),
    textsField("notes", skos + "editorialNote", _.notes, (m, v) => m.copy(notes = v)),
    Field(
      "examples",
      skos + "example",
      Kind.Plain,
      _.examples.flatMap(_.value),
      (m, v) => m.copy(examples = strings(v).map(e => ExampleImpl(value = Some(e)))),
    ),
    refsField("see_also", rdfs + "seeAlso", _.seeAlso, (m, v) => m.copy(seeAlso = v)),
    refField("source", dcterms + "source", _.source, (m, v) => m.copy(source = v)),
    refsField(
      "contributors",
      dcterms + "contributor",
      _.contributors,
      (m, v) => m.copy(contributors = v),
    ),
    refField("created_by", pav + "createdBy", _.createdBy, (m, v) => m.copy(createdBy = v)),
    dateField("created_on", pav + "createdOn", _.createdOn, (m, v) => m.copy(createdOn = v)),
    dateField(
      "last_updated_on",
      pav + "lastUpdatedOn",
      _.lastUpdatedOn,
      (m, v) => m.copy(lastUpdatedOn = v),
    ),
    refField(
      "modified_by",
      "http://open-services.net/ns/core#modifiedBy",
      _.modifiedBy,
      (m, v) => m.copy(modifiedBy = v),
    ),
    refField(
      "status",
      "http://purl.org/ontology/bibo/status",
      _.status,
      (m, v) => m.copy(status = v),
    ),
    stringField(
      "in_language",
      schemaOrg + "inLanguage",
      _.inLanguage,
      (m, v) => m.copy(inLanguage = v),
    ),
    textsField("keywords", schemaOrg + "keywords", _.keywords, (m, v) => m.copy(keywords = v)),
    refsField("categories", dcterms + "subject", _.categories, (m, v) => m.copy(categories = v)),
    refsField("mappings", skos + "mappingRelation", _.mappings, (m, v) => m.copy(mappings = v)),
    refsField(
      "exact_mappings",
      skos + "exactMatch",
      _.exactMappings,
      (m, v) => m.copy(exactMappings = v),
    ),
    refsField(
      "close_mappings",
      skos + "closeMatch",
      _.closeMappings,
      (m, v) => m.copy(closeMappings = v),
    ),
    refsField(
      "related_mappings",
      skos + "relatedMatch",
      _.relatedMappings,
      (m, v) => m.copy(relatedMappings = v),
    ),
    refsField(
      "narrow_mappings",
      skos + "narrowMatch",
      _.narrowMappings,
      (m, v) => m.copy(narrowMappings = v),
    ),
    refsField(
      "broad_mappings",
      skos + "broadMatch",
      _.broadMappings,
      (m, v) => m.copy(broadMappings = v),
    ),
    Field(
      "deprecated",
      "http://www.w3.org/2002/07/owl#deprecated",
      Kind.Flag,
      _.deprecated.toSeq,
      (m, v) => m.copy(deprecated = strings(v).headOption.map(PlainText(_))),
    ),
    stringField("license", dcterms + "license", _.license, (m, v) => m.copy(license = v)),
    stringField("version", pav + "version", _.version, (m, v) => m.copy(version = v)),
  )

  val byName: Map[String, Field] = fields.map(f => f.name -> f).toMap

  /** @param expand
    *   Expands a CURIE to a full IRI for this element.
    * @param description
    *   The property used for `description`.
    */
  def annotations(
      metadata: Metadata,
      expand: UriOrCurie => String,
      description: String = skos + "definition",
      language: Option[String] = None,
  ): Seq[Annotation] =
    fields.flatMap { field =>
      val property = if field.name == "description" then description else field.property
      field.get(metadata).flatMap(v => values(field.kind, v, expand))
        .map(v => if humanText(field.name) then inLanguage(v, language) else v)
        .map {
          // Write a license that is an IRI as a link, as most ontologies do.
          case Literal(v, XmlSchema.string) if field.name == "license" && isAbsoluteIri(v) => Iri(v)
          case v => v
        }
        .map(Annotation(property, _))
    }

  private def isAbsoluteIri(v: String): Boolean =
    (v.contains("://") || v.startsWith("urn:")) && !v.exists(c =>
      c.isWhitespace || "<>\"{}|\\^`".contains(c),
    )

  /** Metaslots with text for people to read. These get the language tag. */
  val humanText: Set[String] =
    Set("title", "description", "aliases", "comments", "notes", "examples", "keywords")

  /** Adds the language tag to a plain string literal, if a language is given. */
  def inLanguage(node: Node, language: Option[String]): Node = (node, language) match {
    case (Literal(v, XmlSchema.string), Some(l)) => LanguageLiteral(v, l)
    case _ => node
  }

  private def values(
      kind: Kind,
      value: String | LocalizedText | UriOrCurie,
      expand: UriOrCurie => String,
  ): Seq[Node] =
    (kind, value) match {
      case (_, PlainText(v)) => Seq(Literal(v))
      case (_, MultilingualText(mapping)) =>
        mapping.toSeq.sortBy(_._1).map((lang, v) => LanguageLiteral(v, lang))
      case (Kind.Reference, v: String) => Seq(Iri(expand(UriOrCurie(v))))
      case (Kind.Reference, v: UriOrCurie) => Seq(Iri(expand(v)))
      case (_, v: UriOrCurie) => Seq(Literal(v.original))
      case (Kind.DateTime, v: String) => Seq(Literal(v, XmlSchema.dateTime))
      case (Kind.Flag, _) => Seq(Literal("true", XmlSchema.boolean))
      case (_, v: String) => Seq(Literal(v))
    }
}
