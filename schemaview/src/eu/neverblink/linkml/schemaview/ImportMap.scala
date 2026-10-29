package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.metamodel.SchemaDefinition
import eu.neverblink.linkml.runtime.{PrefixMap, UriCurieValidator}
import eu.neverblink.linkml.validation.{SchemaImportError, SchemaParseError}
import org.virtuslab.yaml.{Node, Tag, parseYaml}

import scala.annotation.tailrec

/** Rules that tell the loader where to find a schema that is imported by URI or path. The schemas
  * keep their imports unchanged: the map only gives the location to load each import from.
  *
  * Map keys are full URIs or file paths, not CURIEs. Imports are compared with them by their lookup
  * keys (see [[ImportMap.lookupKey]]), which expand a CURIE import with the prefixes of its schema.
  * So `units:core`, `https://example.org/units/core` and `https://example.org/units/core.yaml` find
  * the same entry when `units` stands for `https://example.org/units/`.
  *
  * A key that ends with `/` or `#` is a prefix key: it replaces the matching start of a lookup key.
  * Any other key is an exact key. An exact key has precedence over a prefix key, and a longer
  * prefix key has precedence over a shorter one.
  */
final class ImportMap private (
    exact: Map[String, String],
    prefixes: Seq[(String, String)],
) {

  /** Return the location to load for a lookup key, applying the map again to each result until it
    * no longer changes. A key that no entry matches is returned unchanged.
    *
    * @return
    *   The location, or a [[SchemaImportError]] that lists the chain when it leads back to a key
    *   already seen or has more steps than the map has entries.
    */
  def resolve(lookupKey: String): Either[SchemaImportError, String] = {
    val maxSteps = exact.size + prefixes.size
    @tailrec
    def loop(current: String, chain: List[String]): Either[SchemaImportError, String] = {
      val next = step(current)
      if (next eq null) new Right(current)
      else {
        val nextKey = ImportMap.withoutExtension(next)
        if (nextKey == ImportMap.withoutExtension(current)) new Right(next)
        else if (chain.exists(ImportMap.withoutExtension(_) == nextKey)) {
          new Left(
            ImportMap.chainError(lookupKey, next :: chain, "leads back to a key already seen"),
          )
        } else if (chain.length > maxSteps) {
          new Left(
            ImportMap.chainError(
              lookupKey,
              next :: chain,
              "has more steps than the map has entries",
            ),
          )
        } else loop(next, next :: chain)
      }
    }
    loop(lookupKey, lookupKey :: Nil)
  }

  /** Apply the map once, or return `null` when no entry matches. */
  private def step(key: String): String = {
    val target = exact.getOrElse(ImportMap.withoutExtension(key), null)
    if (target ne null) target
    else {
      // Prefix keys are sorted from the longest to the shortest.
      val it = prefixes.iterator
      while (it.hasNext) {
        val entry = it.next()
        val prefix = entry._1
        if (key.startsWith(prefix)) {
          return ImportMap.normalize(entry._2.concat(key.substring(prefix.length)))
        }
      }
      null
    }
  }
}

object ImportMap {

  /** A map without entries, which returns every lookup key unchanged. */
  val empty: ImportMap = new ImportMap(Map.empty, Nil)

  /** Build a map from its entries. Each key is an import and each value is the location to load it
    * from. Keys and values are full URIs or file paths.
    *
    * @return
    *   The map, or a [[SchemaImportError]] for a key or value that is a CURIE, or for two keys that
    *   have the same lookup key and different targets.
    */
  def apply(entries: Map[String, String]): Either[SchemaImportError, ImportMap] = {
    // Lookup key (without the extension for exact keys) -> (key as written, target)
    val exact = new java.util.HashMap[String, (String, String)]
    val prefixes = new java.util.HashMap[String, (String, String)]
    val it = entries.iterator
    while (it.hasNext) {
      val entry = it.next()
      val key = entry._1
      val value = entry._2
      val isPrefix = isPrefixKey(key)
      val added = for {
        k <- standaloneKey(key, isPrefix, "key")
        t <- standaloneKey(value, isPrefix, "location")
        _ <-
          if (isPrefix) add(prefixes, k, key, t)
          else add(exact, withoutExtension(k), key, t)
      } yield ()
      added match {
        case Left(err) => return new Left(err)
        case _ =>
      }
    }
    val exactTargets = Map.newBuilder[String, String]
    exact.forEach((k, v) => exactTargets.addOne((k, v._2)))
    val prefixTargets = Seq.newBuilder[(String, String)]
    prefixes.forEach((k, v) => prefixTargets.addOne((k, v._2)))
    new Right(new ImportMap(exactTargets.result(), prefixTargets.result().sortBy(-_._1.length)))
  }

  /** Build a map from a JSON object in which each key is an import and each value is the location
    * to load it from, such as `{"https://example.org/units/": "deps/units/"}`. The entries are
    * checked as in [[apply]]. A relative location is used as written, so it is relative to the
    * current directory, not to the JSON file.
    *
    * @param source
    *   Where the JSON comes from, such as a file name, to name it in errors.
    * @return
    *   The map, or a [[SchemaImportError]] for text that is not a JSON object of strings, or for an
    *   entry that [[apply]] rejects.
    */
  def fromJson(json: String, source: String = ""): Either[SchemaImportError, ImportMap] = {
    def invalid(reason: String) = new Left(Importer.importError(source, reason))
    // JSON is a subset of YAML 1.2, so the YAML parser that reads schemas reads it too.
    parseYaml(json) match {
      case Right(mapping: Node.MappingNode) =>
        val entries = Map.newBuilder[String, String]
        val it = mapping.mappings.iterator
        while (it.hasNext) {
          val entry = it.next()
          entry._1 match {
            case k: Node.ScalarNode if Tag.str eq k.tag =>
              entry._2 match {
                case v: Node.ScalarNode if Tag.str eq v.tag => entries.addOne((k.value, v.value))
                case _ =>
                  return invalid(s"The location of '${k.value}' in the import map is not a string")
              }
            case _ => return invalid("A key of the import map is not a string")
          }
        }
        apply(entries.result())
      case Right(_) => invalid("The import map is not a JSON object")
      case Left(err) => invalid(s"The import map is not valid JSON: ${err.msg}")
    }
  }

  /** Add an entry, or report a key with the same lookup key and a different target. */
  private def add(
      to: java.util.HashMap[String, (String, String)],
      lookupKey: String,
      key: String,
      target: String,
  ): Either[SchemaImportError, Unit] = {
    val other = to.putIfAbsent(lookupKey, (key, target))
    if ((other eq null) || other._2 == target) new Right(())
    else {
      // Name the keys in a fixed order, whatever the order of the entries.
      val otherFirst = other._1 < key
      val keys = if (otherFirst) s"'${other._1}' and '$key'" else s"'$key' and '${other._1}'"
      val targets =
        if (otherFirst) s"'${other._2}' and '$target'" else s"'$target' and '${other._2}'"
      new Left(
        Importer.importError(
          key,
          s"Keys $keys are the same import, but map it to different targets $targets",
        ),
      )
    }
  }

  /** Turn an import, as written in `schema`, into its lookup key:
    *
    *   - A full URI is used as it is.
    *   - A CURIE is expanded with the prefixes of `schema`: the prefixes of the LinkML metamodel
    *     (`linkml`, `xsd`, `rdf`, ...), the `semweb_context` ones if the schema lists them in
    *     `default_curi_maps`, and the schema's own `prefixes`.
    *   - A relative path is joined to the directory of `location`. An absolute path is used as it
    *     is.
    *
    * The result is normalized with [[Importer.normalizeUri]] and [[Importer.normalizePath]].
    *
    * @param location
    *   The place `schema` was loaded from, or an empty string if it is not known.
    * @return
    *   The lookup key, or a [[SchemaImportError]] for a CURIE whose prefix is not known.
    */
  def lookupKey(
      written: String,
      schema: SchemaDefinition,
      location: String,
  ): Either[SchemaImportError, String] =
    lookupKeyIn(written, schema, directoryOf(location))

  /** The same as [[lookupKey]], but with the directory to join relative paths to. */
  private[schemaview] def lookupKeyIn(
      written: String,
      schema: SchemaDefinition,
      directory: String,
  ): Either[SchemaImportError, String] = {
    val badNamespace = invalidNamespaceError(written, schema)
    if (badNamespace ne null) new Left(badNamespace)
    else
      expand(written, SchemaCodec.prefixMap(schema), schema.id.original) match {
        case Right(expanded) =>
          val joined =
            if (directory.isEmpty || isUri(expanded) || isAbsolutePath(expanded)) expanded
            else directory.concat(expanded)
          new Right(Importer.normalizePath(Importer.normalizeUri(joined)))
        case err => err
      }
  }

  /** The error for an import that starts with a namespace of `schema` that isn't a valid URI, or
    * null if there is none. An import is expanded when the schema is read, so `linkml:types` with
    * `linkml: "https://w3id.org/linkml/ has a space"` is
    * `https://w3id.org/linkml/ has a spacetypes` here. The validator reports such a namespace only
    * after the imports are loaded, so without this the user would only learn that the import can't
    * be found.
    */
  private def invalidNamespaceError(
      written: String,
      schema: SchemaDefinition,
  ): SchemaImportError = {
    val prefixes = schema.prefixes.valuesIterator
    while (prefixes.hasNext) {
      val p = prefixes.next()
      val ns = p.prefixReference.original
      if (written.startsWith(ns) && !UriCurieValidator.isValidUri(ns))
        return Importer.importError(
          written,
          s"The namespace '$ns' of the prefix '${p.prefixPrefix}' in schema " +
            s"'${schema.id.original}' isn't a valid URI",
        )
    }
    null
  }

  /** Turn the URI passed to [[SchemaView.loadSchemaViewFromUri]] into its lookup key. It does not
    * belong to any schema, so it is not expanded as a CURIE, and a relative path is not joined to
    * anything.
    */
  def rootLookupKey(uri: String): String = normalize(withoutDotSlash(uri))

  /** The lookup key of a map key or map target. A prefix key and its target are only normalized
    * with [[Importer.normalizePath]], keeping their last character, as the rest of a lookup key is
    * joined to them.
    *
    * @param role
    *   What `s` is in the map, `key` or `location`, to name it in the error for a CURIE.
    */
  private def standaloneKey(
      s: String,
      isPrefix: Boolean,
      role: String,
  ): Either[SchemaImportError, String] =
    if (curieColon(s) >= 0) {
      new Left(
        Importer.importError(
          s,
          s"The import map $role '$s' is a CURIE. Write it as a full URI, such as " +
            "'https://w3id.org/linkml/types' instead of 'linkml:types'",
        ),
      )
    } else {
      val path = withoutDotSlash(s)
      new Right(if (isPrefix) normalizePrefix(path) else normalize(path))
    }

  /** Expand a CURIE, or return any other import as it is, without a leading `./`. */
  private def expand(
      s: String,
      prefixes: PrefixMap,
      schemaId: String,
  ): Either[SchemaImportError, String] = {
    val colon = curieColon(s)
    if (colon < 0) new Right(withoutDotSlash(s))
    else {
      val prefix = s.substring(0, colon)
      val base = prefixes.namespaceOrNull(prefix)
      if (base ne null) new Right(base.concat(s.substring(colon + 1)))
      else {
        val where = if (schemaId.isEmpty) "" else s" in schema '$schemaId'"
        new Left(Importer.importError(s, s"Unknown prefix '$prefix' for CURIE '$s'$where"))
      }
    }
  }

  /** The index of the colon that ends the prefix of a CURIE, or -1 if `s` is not a CURIE. Full URIs
    * (`urn:` ones and those with `://`), paths and Windows paths that start with a drive letter are
    * not CURIEs.
    */
  private def curieColon(s: String): Int =
    if (isUri(s)) -1
    else {
      val colon = s.indexOf(':')
      if (colon <= 0 || isDriveLetter(s)) -1
      else {
        var i = 0
        while (i < colon && s.charAt(i) != '/' && s.charAt(i) != '\\') i += 1
        if (i < colon) -1 else colon
      }
    }

  private def isUri(s: String): Boolean = s.startsWith("urn:") || s.contains("://")

  private def withoutDotSlash(s: String): String =
    if (s.startsWith("./") || s.startsWith(".\\")) s.substring(2) else s

  /** Whether `s` starts with a Windows drive letter, such as `C:\` or `C:/`. */
  private def isDriveLetter(s: String): Boolean =
    s.length >= 3 && s.charAt(0).isLetter && s.charAt(1) == ':' &&
      (s.charAt(2) == '/' || s.charAt(2) == '\\')

  private def isAbsolutePath(s: String): Boolean =
    s.startsWith("/") || s.startsWith("\\") || isDriveLetter(s)

  private def isPrefixKey(key: String): Boolean = key.endsWith("/") || key.endsWith("#")

  /** The directory part of a location, with its trailing separator, or an empty string. */
  private[schemaview] def directoryOf(location: String): String = {
    val idx = Importer.lastSeparator(location)
    if (idx >= 0) location.substring(0, idx + 1) else ""
  }

  /** Add the trailing separator to a directory, which [[SchemaView.loadImports]] takes without one.
    */
  private[schemaview] def directory(baseUri: String): String =
    if (baseUri.isEmpty || Importer.lastSeparator(baseUri) == baseUri.length - 1) baseUri
    else baseUri.concat(Importer.separatorFor(baseUri))

  /** Normalize a prefix key, keeping the last character that [[Importer.normalizePath]] drops. */
  private def normalizePrefix(s: String): String =
    if (s.isEmpty) s
    else {
      val last = s.last
      val normalized = Importer.normalizePath(s)
      if (normalized.nonEmpty && normalized.last == last) normalized else normalized + last
    }

  /** Normalize the result of a prefix key. */
  private def normalize(s: String): String = Importer.normalizePath(Importer.normalizeUri(s))

  /** A lookup key without its `.yaml` or `.yml` extension, which matching ignores. */
  private[schemaview] def withoutExtension(key: String): String =
    if (key.endsWith(".yaml")) key.substring(0, key.length - 5)
    else if (key.endsWith(".yml")) key.substring(0, key.length - 4)
    else key

  private def chainError(lookupKey: String, chain: List[String], what: String): SchemaImportError =
    Importer.importError(lookupKey, s"The import map $what: ${chain.reverse.mkString(" -> ")}")
}

/** An importer that applies `map` to every import, then passes the result to
  * `underlying.mapImport`. It passes all other calls to `underlying`, so stacked maps work, and the
  * outer map has precedence.
  */
final class ImportMapImporter(map: ImportMap, underlying: Importer) extends Importer {
  override def mapImport(lookupKey: String): Either[SchemaImportError, String] =
    map.resolve(lookupKey).flatMap(underlying.mapImport)

  override def readSchema(path: String): Either[ImportFailure, SchemaDefinition] =
    underlying.readSchema(path)

  override def schemaKey(path: String): String = underlying.schemaKey(path)

  override def parseSchema(
      yaml: String,
      uri: String,
  ): Either[SchemaParseError, SchemaDefinition] =
    underlying.parseSchema(yaml, uri)
}

object ImportMapImporter {

  /** Wrap `underlying` with a map built from `entries`, or return `underlying` itself when there
    * are no entries. Bindings that take the map as plain entries use it.
    *
    * @return
    *   The importer, or the [[SchemaImportError]] from [[ImportMap.apply]] for an entry that is not
    *   allowed.
    */
  def fromEntries(
      entries: Map[String, String],
      underlying: Importer,
  ): Either[SchemaImportError, Importer] =
    if (entries.isEmpty) new Right(underlying)
    else ImportMap(entries).map(new ImportMapImporter(_, underlying))
}
