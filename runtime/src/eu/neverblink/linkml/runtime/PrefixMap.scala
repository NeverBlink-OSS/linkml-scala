package eu.neverblink.linkml.runtime

/** Prefixes that turn CURIEs into URIs when data is decoded, and URIs back into CURIEs when it is
  * encoded.
  *
  * The prefixes come from two places. When a prefix is in both, the overrides have precedence:
  *
  *   1. Overrides, such as the prefixes that a document declares.
  *   2. Defaults, for prefixes that are not in the overrides.
  *
  * When a place declares a prefix more than once, the first declaration is used.
  *
  * Namespaces are used as they are written: nothing is added to or removed from them.
  *
  * @see
  *   https://github.com/linkml/linkml/issues/3906
  */
final class PrefixMap private (
    val overrides: Seq[(String, String)],
    val defaults: Seq[(String, String)],
    // False for a map made with `withoutCompaction`
    val compacts: Boolean,
) {
  // Namespace of each prefix, after precedence is applied. Filled together with `compactEntries`
  private val namespaces: java.util.HashMap[String, String] = new java.util.HashMap[String, String](
    (overrides.size + defaults.size) << 1,
    0.5f,
  )

  // Pairs of namespace and prefix used for compaction, longest namespace first. Each namespace
  // appears once, with the prefix from the place with the highest precedence, and the first one
  // declared there.
  private val compactEntries: Array[java.util.Map.Entry[String, String]] = {
    val prefixOfNamespace = new java.util.LinkedHashMap[String, String]
    // The places are read in the order of precedence, so the first namespace of a prefix is used
    def addAll(ps: Seq[(String, String)]): Unit = ps.foreach { (p, ns) =>
      if (namespaces.putIfAbsent(p, ns) eq null) prefixOfNamespace.putIfAbsent(ns, p)
    }
    addAll(overrides)
    addAll(defaults)
    val sorted = new Array[java.util.Map.Entry[String, String]](prefixOfNamespace.size)
    prefixOfNamespace.entrySet.toArray(sorted)
    // Stable, so namespaces of the same length keep the order of precedence
    java.util.Arrays.sort(sorted, (a, b) => b.getKey.length - a.getKey.length)
    sorted
  }
  private val compactNamespaces: Array[String] = compactEntries.map(_.getKey)
  private val compactPrefixes: Array[String] = compactEntries.map(_.getValue)

  /** True if this map has no prefixes, so it never expands or compacts anything. */
  def isEmpty: Boolean = namespaces.isEmpty

  /** Get the namespace of a prefix.
    *
    * @return
    *   The namespace, or null if the prefix isn't known
    */
  def namespaceOrNull(prefix: String): String = namespaces.get(prefix)

  /** All prefixes with their namespaces, after precedence is applied. */
  def toMap: Map[String, String] = {
    val b = Map.newBuilder[String, String]
    namespaces.forEach((p, ns) => b.addOne((p, ns)))
    b.result()
  }

  /** Expand a CURIE into a URI.
    *
    * The text before the first colon is the prefix. If the prefix is known, it is replaced with its
    * namespace. This is also done for a value like `http://example.org` when `http` is declared as
    * a prefix.
    *
    * @return
    *   The URI, or null if the value has no colon or has an unknown prefix
    */
  def expandOrNull(curie: String): String = {
    val index = curie.indexOf(':')
    if (index < 0) null
    else {
      val ns = namespaces.get(curie.substring(0, index))
      if (ns eq null) null
      else ns.concat(curie.substring(index + 1))
    }
  }

  /** Compact a URI into a CURIE.
    *
    * The longest namespace that the URI starts with is replaced by its prefix. The result is only
    * used if it is a valid CURIE with a non-empty local part, which [[expandOrNull]] turns back
    * into the same URI, and if its local part doesn't start with `//`, so it doesn't look like a
    * URL. Otherwise, the next longest namespace is tried.
    *
    * @return
    *   The CURIE, or null if no namespace gives one, or if [[compacts]] is false
    */
  def compactOrNull(uri: String): String = {
    if (!compacts) return null
    val uriLen = uri.length
    val len = compactNamespaces.length
    var i = 0
    while (i < len) {
      val ns = compactNamespaces(i)
      val nsLen = ns.length
      if (
        nsLen < uriLen && uri.startsWith(ns) &&
        (uri.charAt(nsLen) != '/' || nsLen + 1 == uriLen || uri.charAt(nsLen + 1) != '/')
      ) {
        val curie = compactPrefixes(i) + ':' + uri.substring(nsLen)
        if (UriCurieValidator.isValidCurie(curie)) return curie
      }
      i += 1
    }
    null
  }

  /** A copy of this map with the given overrides. They replace the current overrides. */
  def withOverrides(prefixes: Iterable[(String, String)]): PrefixMap =
    new PrefixMap(prefixes.toSeq, defaults, compacts)

  /** A copy of this map with the given defaults. They replace the current defaults. */
  def withDefaults(prefixes: Iterable[(String, String)]): PrefixMap =
    new PrefixMap(overrides, prefixes.toSeq, compacts)

  /** A copy of this map that expands CURIEs as this one does, but compacts no URIs, so they are
    * written in full.
    */
  def withoutCompaction: PrefixMap = new PrefixMap(overrides, defaults, false)

  override def toString: String =
    s"PrefixMap(overrides = $overrides, defaults = $defaults, compacts = $compacts)"
}

object PrefixMap {

  /** A map with no prefixes. Decoding and encoding with it keep values as they are written. */
  val empty: PrefixMap = new PrefixMap(Nil, Nil, true)

  /** Create a map with overrides and defaults.
    *
    * @param overrides
    *   Prefixes that have precedence over the defaults
    * @param defaults
    *   Prefixes used when the overrides don't have them
    */
  def apply(
      overrides: Iterable[(String, String)] = Nil,
      defaults: Iterable[(String, String)] = Nil,
  ): PrefixMap = new PrefixMap(overrides.toSeq, defaults.toSeq, true)
}
