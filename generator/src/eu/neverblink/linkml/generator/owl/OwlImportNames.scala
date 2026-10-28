package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.config.OwlImportConfigs.{prefixMap, renameMap}
import eu.neverblink.linkml.generator.owl.config.{NameStyle, OwlImportConfig}
import eu.neverblink.linkml.rdf.{Iri, Literal}
import eu.neverblink.linkml.metamodel.{Prefixes, SchemaDefinition}
import eu.neverblink.linkml.runtime.{SchemaConversionException, Uri, UriCurieValidator, UriOrCurie}
import eu.neverblink.linkml.schemaview.{Case, FileSystemImporter, SchemaView}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable

/** Prefixes and element names for one import.
  *
  * N-Triples has no prefixes, so they come from the ontology's `sh:declare`, the config, the
  * well-known list, or are made up from a namespace's last path segment. Made-up prefixes are
  * assigned up front in sorted order, so the same ontology always gets the same ones. The schema
  * declares only the prefixes it uses.
  *
  * Names are handed out the ontology's own terms first, so they get the short names. Other terms
  * get their prefix added.
  *
  * @param ownTerms
  *   The terms the ontology declares, used to find its own namespace.
  * @param allTerms
  *   Every IRI that may need a prefix.
  */
private[owl] final class OwlImportNames(
    ontology: Ontology,
    config: OwlImportConfig,
    ownTerms: Seq[String],
    allTerms: Seq[String],
    warn: (String, String) => Unit,
) {
  import OwlImportNames.*

  private val ontologyPrefixes: Seq[(String, String)] = ontology.prefixes ++ config.prefixMap

  /** Expands a CURIE from the config, using the ontology's or well-known prefixes. */
  def expand(curieOrIri: String): String = OwlImportNames.expand(curieOrIri, ontologyPrefixes)

  private def preferred(property: String): Option[String] =
    ontology.annotations.collectFirst {
      case Annotation(p, Literal(v, _)) if p == vann + property => v
      case Annotation(p, Iri(v)) if p == vann + property => v
    }

  /** The namespace of the ontology's own terms. The default prefix stands for it. */
  val defaultNamespace: String =
    config.defaultPrefix
      .flatMap(p => (ontologyPrefixes ++ wellKnownPrefixes).collectFirst { case (`p`, ns) => ns })
      .orElse(preferred("preferredNamespaceUri"))
      .orElse(
        ownTerms.groupBy(namespaceOf).toSeq.sortBy((ns, iris) => (-iris.size, ns)).headOption
          .map(_._1),
      )
      .orElse(ontology.iri.map(i => if i.endsWith("/") || i.endsWith("#") then i else i + "/"))
      .getOrElse("https://example.org/ontology/")

  /** Prefix by namespace, in declaration order. */
  private val prefixes: mutable.LinkedHashMap[String, String] = {
    val out = mutable.LinkedHashMap.empty[String, String]
    def add(prefix: String, ns: String): Unit =
      if !out.contains(ns) && !out.values.exists(_ == prefix) then out(ns) = prefix
    val defaultName = config.defaultPrefix
      .orElse(preferred("preferredNamespacePrefix"))
      .orElse(ontologyPrefixes.collectFirst { case (p, `defaultNamespace`) => p })
      .orElse(wellKnownPrefixes.collectFirst { case (p, `defaultNamespace`) => p })
      .getOrElse(prefixFor(defaultNamespace))
    add(defaultName, defaultNamespace)
    add("linkml", linkmlNs)
    ontologyPrefixes.foreach((p, ns) => add(p, ns))
    out
  }

  def defaultPrefix: String = prefixes(defaultNamespace)

  private val usedPrefixes = mutable.HashSet.empty[String]

  def use(prefix: String): Unit = usedPrefixes += prefix

  def declared: Seq[(String, String)] =
    prefixes.toSeq.filter((ns, p) => ns == defaultNamespace || p == "linkml" || usedPrefixes(p))
      .map((ns, p) => p -> ns)

  def ensurePrefix(iri: String): Unit = {
    val ns = namespaceOf(iri)
    if ns.nonEmpty && ns != iri && !prefixes.contains(ns) then
      wellKnownPrefixes.collectFirst { case (p, `ns`) => p } match {
        case Some(p) if !prefixes.values.exists(_ == p) => prefixes(ns) = p
        case _ =>
          val base = prefixFor(ns)
          val name = (Iterator.single(base) ++ Iterator.from(2).map(base + _))
            .find(p => !prefixes.values.exists(_ == p)).get
          prefixes(ns) = name
      }
  }

  allTerms.distinct.sorted.foreach(ensurePrefix)

  /** Get the longest namespace that the IRI starts with, if the rest of the IRI is a plain local
    * name.
    *
    * @return
    *   The namespace, or null if there is none
    */
  private def namespaceOrNull(iri: String): String = {
    var namespace: String = null
    val it = prefixes.keysIterator
    while (it.hasNext) {
      val ns = it.next()
      if (
        ((namespace eq null) || ns.length > namespace.length) && iri.length > ns.length &&
        iri.startsWith(ns)
      ) namespace = ns
    }
    if ((namespace ne null) && isPlainLocalName(iri, namespace.length)) namespace
    else null
  }

  /** Whether `iri` from index `from` has only letters, digits, `_`, `-` and `.` */
  private def isPlainLocalName(iri: String, from: Int): Boolean = {
    var i = from
    while (i < iri.length) {
      val c = iri.charAt(i)
      if (!(Case.isStandard(c) || c == '-' || c == '.')) return false
      i += 1
    }
    true
  }

  /** Get the prefix of the CURIE for the IRI (see [[curie]]).
    *
    * @return
    *   The prefix, or null if the IRI has no CURIE
    */
  def prefixOrNull(iri: String): String = {
    val ns = namespaceOrNull(iri)
    if (ns eq null) null else prefixes(ns)
  }

  /** The IRI as a CURIE if a prefix covers it and the rest is a plain local name, else the IRI.
    *
    * Use it for text, such as the value of `equals_string`. For a `uriorcurie` slot, use [[uri]].
    *
    * @param record
    *   Whether to mark the prefix as used, so the schema declares it. False for warning text.
    */
  def curie(iri: String, record: Boolean = true): String = {
    val ns = namespaceOrNull(iri)
    if (ns eq null) iri
    else {
      val p = prefixes(ns)
      if (record) usedPrefixes += p
      p.concat(":").concat(iri.substring(ns.length))
    }
  }

  /** The IRI as a URI. Its prefix is marked as used, if it has one (see [[curie]]), so the schema
    * declares the prefix, and the URI is written as a CURIE.
    *
    * @throws SchemaConversionException
    *   if the IRI can't be a URI in the schema (see [[isWritable]])
    */
  def uri(iri: String): Uri = {
    if (!UriCurieValidator.isValidUri(iri))
      throw new SchemaConversionException(
        s"The IRI <$iri> can't be imported, as it isn't a valid URI",
      )
    if (isReadBackAsCurie(iri))
      throw new SchemaConversionException(
        s"The IRI <$iri> can't be imported, as '${iri.substring(0, iri.indexOf(':'))}' is a " +
          "prefix in the schema, so the IRI would be read back as a CURIE with another meaning",
      )
    val p = prefixOrNull(iri)
    if (p ne null) usedPrefixes += p
    Uri(iri)
  }

  /** Whether the IRI can be a URI in the schema: it is a valid URI, and its text wouldn't be read
    * back as a CURIE. Use it to leave out a value that doesn't have to be a URI, such as a
    * reference in an annotation. [[uri]] fails for an IRI that isn't writable.
    */
  def isWritable(iri: String): Boolean =
    UriCurieValidator.isValidUri(iri) && !isReadBackAsCurie(iri)

  /** Whether the IRI would be read back as a CURIE, such as `<xsd:string>` or `<ex:Person>`: no
    * prefix covers it, but the text before its colon is the name of a prefix that the schema can
    * declare, or of a prefix of the metamodel.
    */
  private def isReadBackAsCurie(iri: String): Boolean = {
    val colon = iri.indexOf(':')
    colon > 0 && (namespaceOrNull(iri) eq null) && {
      val p = iri.substring(0, colon)
      Prefixes.map.contains(p) || prefixes.valuesIterator.contains(p)
    }
  }

  /** All prefixes that the schema can declare, as prefix and namespace. */
  def all: Seq[(String, String)] = {
    val b = Seq.newBuilder[(String, String)]
    prefixes.foreach(e => b.addOne((e._2, e._1)))
    b.result()
  }

  /** Element names by IRI. Names are unique across classes, enums, slots and types. */
  private val byIri = mutable.HashMap.empty[String, String]
  private val taken = mutable.HashSet.empty[String] ++= reservedNames
  private val takenBase = mutable.HashSet.empty[(NameGroup, String)] ++=
    reservedNames.map(n => (NameGroup.Type, Case.base(n)))

  def names: collection.Map[String, String] = byIri

  lazy val iriOf: Map[String, String] = byIri.toSeq.map(_.swap).toMap

  /** Gives a term its name from an imported schema. Call this before names are handed out. */
  def reserve(iri: String, name: String, group: NameGroup): Unit = {
    byIri(iri) = name
    taken += name
    takenBase += ((
      if group == NameGroup.Slot then NameGroup.Slot else NameGroup.Type,
      Case.base(name),
    ))
  }

  def name(iris: Seq[String], group: NameGroup, style: NameStyle): Unit =
    iris.sortBy(iri => (namespaceOf(iri) != defaultNamespace, iri))
      .foreach(iri => nameFor(iri, group, style))

  private def nameFor(iri: String, group: NameGroup, style: NameStyle): String =
    byIri.getOrElseUpdate(
      iri, {
        val own = config.renameMap.collectFirst { case (k, v) if expand(k) == iri => v }
          .getOrElse(styled(localName(iri), style))
        // Terms from other namespaces get their prefix added. Clashes within one namespace get a
        // number.
        val qualified =
          if namespaceOf(iri) == defaultNamespace then own
          else {
            ensurePrefix(iri)
            val p = prefixes.getOrElse(namespaceOf(iri), "ns")
            if group == NameGroup.Slot || style == NameStyle.Snake then s"${p}_$own"
            else p.capitalize + own
          }
        // Classes, enums and types share one namespace in generated code, so their base names
        // must differ too.
        val groupKey = if group == NameGroup.Slot then NameGroup.Slot else NameGroup.Type
        def free(n: String) = !taken(n) && !takenBase((groupKey, Case.base(n)))
        val name = (Iterator(own, qualified) ++ Iterator.from(2).map(qualified + "_" + _))
          .find(free).get
        if name != own then warn("Renamed to avoid a clash", s"$iri is named '$name'")
        taken += name
        takenBase += ((groupKey, Case.base(name)))
        name
      },
    )

  /** The IRI LinkML derives for an element with this name when none is set. */
  def defaultIri(name: String, group: NameGroup): String = group match {
    case NameGroup.Slot => Uri.synthetic(defaultNamespace, Case.base(name)).original
    case _ => Uri.synthetic(defaultNamespace, Case.baseToPascal(Case.base(name))).original
  }

  /** The IRI to write as `class_uri`, `slot_uri` and so on, or None if LinkML derives it. */
  def explicitIri(iri: String, group: NameGroup): Option[UriOrCurie] =
    Option.when(iri != defaultIri(byIri(iri), group)) {
      ensurePrefix(iri)
      uri(iri)
    }
}

private[owl] object OwlImportNames {

  /** Kinds of element for naming. Classes, enums and types share a namespace. */
  enum NameGroup:
    case Class, Slot, Type

  /** Prefixes to use when the ontology does not declare its own. */
  val wellKnownPrefixes: Seq[(String, String)] = Seq(
    "rdf" -> "http://www.w3.org/1999/02/22-rdf-syntax-ns#",
    "rdfs" -> "http://www.w3.org/2000/01/rdf-schema#",
    "owl" -> "http://www.w3.org/2002/07/owl#",
    "xsd" -> "http://www.w3.org/2001/XMLSchema#",
    "skos" -> "http://www.w3.org/2004/02/skos/core#",
    "dcterms" -> "http://purl.org/dc/terms/",
    "dc" -> "http://purl.org/dc/elements/1.1/",
    "vann" -> "http://purl.org/vocab/vann/",
    "foaf" -> "http://xmlns.com/foaf/0.1/",
    "schema" -> "http://schema.org/",
    "prov" -> "http://www.w3.org/ns/prov#",
    "sosa" -> "http://www.w3.org/ns/sosa/",
    "ssn" -> "http://www.w3.org/ns/ssn/",
    "time" -> "http://www.w3.org/2006/time#",
    "geo" -> "http://www.opengis.net/ont/geosparql#",
    "dcat" -> "http://www.w3.org/ns/dcat#",
    "org" -> "http://www.w3.org/ns/org#",
    "sh" -> "http://www.w3.org/ns/shacl#",
    "pav" -> "http://purl.org/pav/",
    "oslc" -> "http://open-services.net/ns/core#",
    "bibo" -> "http://purl.org/ontology/bibo/",
    "cc" -> "http://creativecommons.org/ns#",
    "obo" -> "http://purl.obolibrary.org/obo/",
    "oboInOwl" -> "http://www.geneontology.org/formats/oboInOwl#",
    "linkml" -> "https://w3id.org/linkml/",
  )

  private val vann = "http://purl.org/vocab/vann/"
  val linkmlNs = "https://w3id.org/linkml/"

  /** The catch-all class, added when some slot needs it. */
  val anyClass = "Any"

  /** Names of the elements in `linkml:types` and `linkml:extended_types` (such as `Any`), which the
    * schema imports, so its own elements may not use them.
    */
  private lazy val reservedNames: Set[String] = {
    val root = SchemaDefinition(
      id = Uri("urn:linkml-scala:owl-import-reserved"),
      name = "reserved",
      imports = Seq(Uri(linkmlNs.concat("types")), Uri(linkmlNs.concat("extended_types"))),
    )
    SchemaView.loadImports(root, "", FileSystemImporter) match {
      case Right(schemas) =>
        schemas.flatMap(s =>
          s.types.keys ++ s.classes.keys ++ s.enums.keys ++ s.slotDefinitions.keys,
        ).toSet
      case Left(error) => throw IllegalStateException(s"Cannot read linkml:types: $error")
    }
  }

  /** Expands a CURIE using the given or well-known prefixes. Anything else is returned as is. */
  def expand(curieOrIri: String, prefixes: Seq[(String, String)]): String =
    if curieOrIri.contains("://") || curieOrIri.startsWith("urn:") then curieOrIri
    else {
      val colon = curieOrIri.indexOf(':')
      if colon < 0 then curieOrIri
      else {
        val p = curieOrIri.substring(0, colon)
        (prefixes ++ wellKnownPrefixes).collectFirst { case (`p`, ns) => ns }
          .fold(curieOrIri)(_ + curieOrIri.substring(colon + 1))
      }
    }

  /** The part after the last `#`, `/` or `:`, URL-decoded. Trailing `/` and `#` are ignored. */
  def localName(iri: String): String = {
    val trimmed = iri.reverse.dropWhile(c => c == '/' || c == '#').reverse
    val i = Seq('#', '/', ':').map(trimmed.lastIndexOf(_)).max
    val local = trimmed.substring(i + 1)
    scala.util.Try(URLDecoder.decode(local, UTF_8.name)).getOrElse(local)
  }

  def namespaceOf(iri: String): String = {
    val i = math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'))
    if i < 0 then iri else iri.substring(0, i + 1)
  }

  /** Applies a name style. Characters that some generator can't handle become underscores. */
  def styled(raw: String, style: NameStyle): String = {
    val cleaned = raw.map(c => if Case.isStandard(c) then c else '_')
      .replaceAll("_+", "_").stripPrefix("_").stripSuffix("_")
    val base = if cleaned.isEmpty then "unnamed" else cleaned
    style match {
      case NameStyle.Keep => base
      case NameStyle.Snake => snake(base)
      case NameStyle.Pascal => Case.baseToPascal(Case.base(base))
      case NameStyle.UpperSnake => snake(base).toUpperCase
    }
  }

  /** `hasValue` to `has_value` and `HTTPServer` to `http_server`. Unlike [[Case.base]], numbers
    * stay with their word, so `sha1sum` and `d3fend` don't change.
    */
  def snake(name: String): String = {
    val out = StringBuilder()
    for i <- name.indices do {
      val c = name(i)
      val prev = if i > 0 then name(i - 1) else '_'
      val next = if i + 1 < name.length then name(i + 1) else '_'
      val boundary = Case.isAlphaUpper(c) && (
        Case.isAlphaLower(prev) || Case.isNumeric(prev) ||
          (Case.isAlphaUpper(prev) && Case.isAlphaLower(next))
      )
      if boundary && out.nonEmpty && out.last != '_' then out += '_'
      out += (if Case.isAlphanumeric(c) then c.toLower else '_')
    }
    out.toString.replaceAll("_+", "_").stripPrefix("_").stripSuffix("_")
  }

  /** A short lowercase prefix from a namespace's last segment, skipping version numbers. */
  private def prefixFor(namespace: String): String = {
    val segments = namespace.split("[/#]").filter(_.nonEmpty)
      .filterNot(s => s.matches("v?[0-9.]+") || s.contains(":"))
    val candidate = segments.lastOption.getOrElse("ns").takeWhile(_ != '.')
      .filter(c => Case.isAlphanumeric(c)).toLowerCase
    if candidate.isEmpty || !Case.isAlphaLower(candidate.head) then "ns" else candidate
  }
}
