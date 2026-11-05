package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.config.OwlImportConfigs.{prefixMap, renameMap}
import eu.neverblink.linkml.generator.options.{NameStyle, OwlImportConfig}
import eu.neverblink.linkml.rdf.{Iri, Literal}
import eu.neverblink.linkml.metamodel.{Prefixes, SchemaDefinition}
import eu.neverblink.linkml.runtime.{SchemaConversionException, Uri, UriCurieValidator, UriOrCurie}
import eu.neverblink.linkml.schemaview.{Case, FileSystemImporter, SchemaView}

import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8

import scala.collection.mutable
import scala.runtime.IntRef

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
    ownTerms: collection.Seq[String],
    allTerms: collection.Seq[String],
    warn: (String, String) => Unit,
) {
  import OwlImportNames.*

  private val ontologyPrefixes: mutable.ArrayBuffer[(String, String)] = {
    val prefixes = mutable.ArrayBuffer.from(ontology.prefixes)
    config.prefixMap.foreach(prefixes.addOne)
    prefixes
  }

  /** Expands a CURIE from the config, using the ontology's or well-known prefixes. */
  def expand(curieOrIri: String): String = OwlImportNames.expand(curieOrIri, ontologyPrefixes)

  /** The value of a `vann` property of the ontology, or `null` if it has none. */
  private def preferred(property: String): String = {
    val iri = vann + property
    val it = ontology.annotations.iterator
    while (it.hasNext) {
      val a = it.next()
      if (a.property == iri) {
        a.value match {
          case l: Literal => return l.value
          case i: Iri => return i.value
          case _ =>
        }
      }
    }
    null
  }

  /** The namespace of the ontology's own terms. The default prefix stands for it. */
  val defaultNamespace: String = {
    var ns: String = null
    if (config.defaultPrefix.isDefined) {
      val p = config.defaultPrefix.get
      ns = namespaceOfPrefix(p, ontologyPrefixes)
      if (ns eq null) ns = namespaceOfPrefix(p, wellKnownPrefixes)
    }
    if (ns eq null) ns = preferred("preferredNamespaceUri")
    if (ns eq null) ns = mostUsedNamespace(ownTerms)
    if ((ns eq null) && ontology.iri.isDefined) {
      val i = ontology.iri.get
      ns = if (i.endsWith("/") || i.endsWith("#")) i else i + "/"
    }
    if (ns eq null) "https://example.org/ontology/" else ns
  }

  /** Prefix by namespace. */
  private val prefixes = mutable.HashMap.empty[String, String]

  /** The namespaces in [[prefixes]], in declaration order. */
  private val namespaces = mutable.ArrayBuffer.empty[String]

  /** The prefixes in [[prefixes]]. */
  private val prefixNames = mutable.HashSet.empty[String]

  private def addPrefix(ns: String, prefix: String): Unit = {
    prefixes.update(ns, prefix)
    namespaces.addOne(ns)
    prefixNames.addOne(prefix)
  }

  {
    def add(prefix: String, ns: String): Unit =
      if (!prefixes.contains(ns) && !prefixNames.contains(prefix)) addPrefix(ns, prefix)
    var defaultName: String = config.defaultPrefix.orNull
    if (defaultName eq null) defaultName = preferred("preferredNamespacePrefix")
    if (defaultName eq null) defaultName = prefixOfNamespace(defaultNamespace, ontologyPrefixes)
    if (defaultName eq null) defaultName = prefixOfNamespace(defaultNamespace, wellKnownPrefixes)
    if (defaultName eq null) defaultName = prefixFor(defaultNamespace)
    add(defaultName, defaultNamespace)
    add("linkml", linkmlNs)
    ontologyPrefixes.foreach(e => add(e._1, e._2))
  }

  def defaultPrefix: String = prefixes(defaultNamespace)

  private val usedPrefixes = mutable.HashSet.empty[String]

  def use(prefix: String): Unit = usedPrefixes.addOne(prefix)

  def declared: Seq[(String, String)] = {
    val out = Vector.newBuilder[(String, String)]
    namespaces.foreach { ns =>
      val p = prefixes(ns)
      if (ns == defaultNamespace || p == "linkml" || usedPrefixes.contains(p)) out.addOne(p -> ns)
    }
    out.result()
  }

  def ensurePrefix(iri: String): Unit = {
    val ns = namespaceOf(iri)
    if (ns.nonEmpty && ns != iri && !prefixes.contains(ns)) {
      val wellKnown = prefixOfNamespace(ns, wellKnownPrefixes)
      if ((wellKnown ne null) && !prefixNames.contains(wellKnown)) addPrefix(ns, wellKnown)
      else {
        val base = prefixFor(ns)
        var name = base
        var n = 2
        while (prefixNames.contains(name)) {
          name = base + n
          n += 1
        }
        addPrefix(ns, name)
      }
    }
  }

  {
    val terms = allTerms.toArray
    java.util.Arrays.sort(terms, Ordering.String)
    var i = 0
    while (i < terms.length) {
      if (i == 0 || terms(i) != terms(i - 1)) ensurePrefix(terms(i))
      i += 1
    }
  }

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
    * Use it for text, such as the key of an annotation. For a `uriorcurie` slot, use [[uri]].
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
    if (p ne null) usedPrefixes.addOne(p)
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
      Prefixes.map.contains(p) || prefixNames.contains(p)
    }
  }

  /** All prefixes that the schema can declare, as prefix and namespace. */
  def all: Seq[(String, String)] = {
    val b = Vector.newBuilder[(String, String)]
    namespaces.foreach(ns => b.addOne((prefixes(ns), ns)))
    b.result()
  }

  /** Element names by IRI. Names are unique across classes, enums, slots and types. */
  private val byIri = mutable.HashMap.empty[String, String]
  private val taken = mutable.HashSet.empty[String] ++= reservedNames

  /** Base names taken by slots, and by classes, enums and types. */
  private val takenSlotBases = mutable.HashSet.empty[String]
  private val takenTypeBases = mutable.HashSet.empty[String]
  reservedNames.foreach(n => takenTypeBases.addOne(Case.base(n)))

  private def takenBases(group: NameGroup): mutable.HashSet[String] =
    if (group == NameGroup.Slot) takenSlotBases else takenTypeBases

  /** Names from the config's `renames`, by expanded IRI. */
  private val renames: mutable.HashMap[String, String] = {
    val renames = mutable.HashMap.empty[String, String]
    config.renameMap.foreachEntry((k, v) => renames.getOrElseUpdate(expand(k), v))
    renames
  }

  def names: collection.Map[String, String] = byIri

  lazy val iriOf: Map[String, String] = byIri.iterator.map(_.swap).toMap

  /** Gives a term its name from an imported schema. Call this before names are handed out. */
  def reserve(iri: String, name: String, group: NameGroup): Unit = {
    byIri.update(iri, name)
    taken.addOne(name)
    takenBases(group).addOne(Case.base(name))
  }

  /** Names the IRIs, those in the default namespace first, each part in IRI order. */
  def name(iris: collection.Seq[String], group: NameGroup, style: NameStyle): Unit = {
    val own = mutable.ArrayBuffer.empty[String]
    val others = mutable.ArrayBuffer.empty[String]
    iris.foreach(iri => (if (namespaceOf(iri) == defaultNamespace) own else others).addOne(iri))
    val sorted = own.toArray
    java.util.Arrays.sort(sorted, Ordering.String)
    sorted.foreach(iri => nameFor(iri, group, style))
    val sortedOthers = others.toArray
    java.util.Arrays.sort(sortedOthers, Ordering.String)
    sortedOthers.foreach(iri => nameFor(iri, group, style))
  }

  private def nameFor(iri: String, group: NameGroup, style: NameStyle): String = {
    val existing = byIri.getOrElse(iri, null)
    if (existing ne null) existing
    else {
      val renamed = renames.getOrElse(iri, null)
      val own = if (renamed ne null) renamed else styled(localName(iri), style)
      // Terms from other namespaces get their prefix added. Clashes within one namespace get a
      // number.
      val qualified =
        if (namespaceOf(iri) == defaultNamespace) own
        else {
          ensurePrefix(iri)
          val p = prefixes.getOrElse(namespaceOf(iri), "ns")
          if (group == NameGroup.Slot || style == NameStyle.Snake) p + '_' + own
          else p.capitalize.concat(own)
        }
      // Classes, enums and types share one namespace in generated code, so their base names
      // must differ too.
      val bases = takenBases(group)
      def free(n: String) = !taken.contains(n) && !bases.contains(Case.base(n))
      var name = own
      if (!free(name)) {
        name = qualified
        var n = 2
        while (!free(name)) {
          name = qualified + '_' + n
          n += 1
        }
      }
      if (name != own) warn("Renamed to avoid a clash", s"$iri is named '$name'")
      taken.addOne(name)
      bases.addOne(Case.base(name))
      byIri.update(iri, name)
      name
    }
  }

  /** The IRI LinkML derives for an element with this name when none is set. */
  def defaultIri(name: String, group: NameGroup): String = group match {
    case NameGroup.Slot => Uri.synthetic(defaultNamespace, Case.base(name)).original
    case _ => Uri.synthetic(defaultNamespace, Case.baseToPascal(Case.base(name))).original
  }

  /** The IRI to write as `class_uri`, `slot_uri` and so on, or None if LinkML derives it. */
  def explicitIri(iri: String, group: NameGroup): Option[UriOrCurie] =
    if (iri == defaultIri(byIri(iri), group)) None
    else {
      ensurePrefix(iri)
      Some(uri(iri))
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
    SchemaView.loadImports(root, "", FileSystemImporter).fold(
      error => throw IllegalStateException(s"Cannot read linkml:types: $error"),
      schemas => {
        val names = Set.newBuilder[String]
        schemas.foreach { s =>
          names.addAll(s.types.keys)
          names.addAll(s.classes.keys)
          names.addAll(s.enums.keys)
          names.addAll(s.slotDefinitions.keys)
        }
        names.result()
      },
    )
  }

  /** Expands a CURIE using the given or well-known prefixes. Anything else is returned as is. */
  def expand(curieOrIri: String, prefixes: collection.Seq[(String, String)]): String =
    if (curieOrIri.contains("://") || curieOrIri.startsWith("urn:")) curieOrIri
    else {
      val colon = curieOrIri.indexOf(':')
      if (colon < 0) curieOrIri
      else {
        val p = curieOrIri.substring(0, colon)
        var ns = namespaceOfPrefix(p, prefixes)
        if (ns eq null) ns = namespaceOfPrefix(p, wellKnownPrefixes)
        if (ns eq null) curieOrIri else ns.concat(curieOrIri.substring(colon + 1))
      }
    }

  /** The namespace of the first entry with the prefix, or `null` if there is none. */
  private def namespaceOfPrefix(
      prefix: String,
      prefixes: collection.Seq[(String, String)],
  ): String = {
    val it = prefixes.iterator
    while (it.hasNext) {
      val e = it.next()
      if (e._1 == prefix) return e._2
    }
    null
  }

  /** The prefix of the first entry with the namespace, or `null` if there is none. */
  private def prefixOfNamespace(ns: String, prefixes: collection.Seq[(String, String)]): String = {
    val it = prefixes.iterator
    while (it.hasNext) {
      val e = it.next()
      if (e._2 == ns) return e._1
    }
    null
  }

  /** The namespace with the most terms, the first in order of IRIs if several have as many, or
    * `null` if there are no terms.
    */
  private def mostUsedNamespace(terms: collection.Seq[String]): String = {
    val counts = mutable.HashMap.empty[String, IntRef]
    terms.foreach(t => counts.getOrElseUpdate(namespaceOf(t), IntRef.create(0)).elem += 1)
    var best: String = null
    var bestCount = 0
    counts.foreachEntry { (ns, count) =>
      val n = count.elem
      if (n > bestCount || (n == bestCount && ns.compareTo(best) < 0)) {
        best = ns
        bestCount = n
      }
    }
    best
  }

  /** The part after the last `#`, `/` or `:`, URL-decoded. Trailing `/` and `#` are ignored. */
  def localName(iri: String): String = {
    var end = iri.length
    while (end > 0 && { val c = iri.charAt(end - 1); c == '/' || c == '#' }) end -= 1
    var start = end
    while (start > 0 && { val c = iri.charAt(start - 1); c != '#' && c != '/' && c != ':' }) {
      start -= 1
    }
    val local = iri.substring(start, end)
    try URLDecoder.decode(local, UTF_8.name)
    catch { case _: IllegalArgumentException => local }
  }

  def namespaceOf(iri: String): String = {
    val i = math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'))
    if (i < 0) iri else iri.substring(0, i + 1)
  }

  /** Applies a name style. Characters that some generator can't handle become underscores. */
  def styled(raw: String, style: NameStyle): String = {
    val sb = new java.lang.StringBuilder(raw.length)
    var i = 0
    while (i < raw.length) {
      val c = raw.charAt(i)
      appendUnderscored(sb, if (Case.isStandard(c)) c else '_')
      i += 1
    }
    val cleaned = withoutTrailingUnderscore(sb)
    val base = if (cleaned.isEmpty) "unnamed" else cleaned
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
    val sb = new java.lang.StringBuilder(name.length + 4)
    var i = 0
    while (i < name.length) {
      val c = name.charAt(i)
      val prev = if (i > 0) name.charAt(i - 1) else '_'
      val next = if (i + 1 < name.length) name.charAt(i + 1) else '_'
      val boundary = Case.isAlphaUpper(c) && (
        Case.isAlphaLower(prev) || Case.isNumeric(prev) ||
          (Case.isAlphaUpper(prev) && Case.isAlphaLower(next))
      )
      if (boundary) appendUnderscored(sb, '_')
      appendUnderscored(sb, if (Case.isAlphanumeric(c)) c.toLower else '_')
      i += 1
    }
    withoutTrailingUnderscore(sb)
  }

  /** Appends a character, but no `_` at the start or right after another `_`. */
  private def appendUnderscored(sb: java.lang.StringBuilder, c: Char): Unit =
    if (c != '_' || (sb.length > 0 && sb.charAt(sb.length - 1) != '_')) sb.append(c)

  private def withoutTrailingUnderscore(sb: java.lang.StringBuilder): String = {
    val n = sb.length
    if (n > 0 && sb.charAt(n - 1) == '_') sb.substring(0, n - 1) else sb.toString
  }

  /** A short lowercase prefix from a namespace's last segment, skipping version numbers. */
  private def prefixFor(namespace: String): String = {
    // The last segment between `/` and `#` that is not empty, a version, or has a `:`.
    var end = namespace.length
    var segment: String = null
    while ((segment eq null) && end > 0) {
      var start = end
      while (start > 0 && { val c = namespace.charAt(start - 1); c != '/' && c != '#' }) start -= 1
      if (start < end) {
        val s = namespace.substring(start, end)
        if (!isVersion(s) && s.indexOf(':') < 0) segment = s
      }
      end = start - 1
    }
    if (segment eq null) segment = "ns"
    val sb = new java.lang.StringBuilder(segment.length)
    var i = 0
    while (i < segment.length && segment.charAt(i) != '.') {
      val c = segment.charAt(i)
      if (Case.isAlphanumeric(c)) sb.append(Character.toLowerCase(c))
      i += 1
    }
    if (sb.length == 0 || !Case.isAlphaLower(sb.charAt(0))) "ns" else sb.toString
  }

  /** Whether a segment is like `v1.2` or `2024`: an optional `v`, then digits and dots. */
  private def isVersion(s: String): Boolean = {
    var i = if (s.startsWith("v")) 1 else 0
    if (i >= s.length) false
    else {
      while (i < s.length && { val c = s.charAt(i); (c >= '0' && c <= '9') || c == '.' }) i += 1
      i == s.length
    }
  }
}
