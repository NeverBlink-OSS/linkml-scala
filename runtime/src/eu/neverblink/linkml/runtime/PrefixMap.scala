package eu.neverblink.linkml.runtime

import eu.neverblink.linkml.runtime.FastUtils.*

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
  * A map can also have a default namespace, for values without a colon, such as the namespace of a
  * schema's `default_prefix`.
  *
  * Namespaces are used as they are written: nothing is added to or removed from them.
  *
  * @see
  *   https://github.com/linkml/linkml/issues/3906
  */
final class PrefixMap private (
    overrides: Seq[(String, String)],
    defaults: Seq[(String, String)],
    // Namespace for values without a colon, or null if there is none
    defaultNamespace: String,
    // False for a map made with `withoutCompaction`
    compacts: Boolean,
) {
  // Namespace of each prefix, after precedence is applied. Filled together with `compactEntries`
  private val namespaces: java.util.HashMap[String, String] = new java.util.HashMap[String, String](
    (overrides.size + defaults.size) << 1,
    0.5f,
  )

  // Pairs of namespace and prefix used for compaction, longest namespace first. Each namespace
  // appears once, with the prefix from the place with the highest precedence, and the first one
  // declared there. Empty for a map made with `withoutCompaction`.
  private val compactEntries: Array[java.util.Map.Entry[String, String]] = {
    val prefixOfNamespace = new java.util.LinkedHashMap[String, String]
    // The places are read in the order of precedence, so the first namespace of a prefix is used
    def addAll(ps: Seq[(String, String)]): Unit = ps.foreach { (p, ns) =>
      if ((namespaces.putIfAbsent(p, ns) eq null) && compacts) prefixOfNamespace.putIfAbsent(ns, p)
    }
    addAll(overrides)
    addAll(defaults)
    val sorted = new Array[java.util.Map.Entry[String, String]](prefixOfNamespace.size)
    prefixOfNamespace.entrySet.toArray(sorted)
    // Stable, so namespaces of the same length keep the order of precedence
    java.util.Arrays.sort(sorted, (a, b) => b.getKey.length - a.getKey.length)
    sorted
  }

  /** Get the namespace of a prefix.
    *
    * @return
    *   The namespace, or null if the prefix isn't known
    */
  def namespaceOrNull(prefix: String): String = namespaces.get(prefix)

  /** Expand a CURIE into a URI.
    *
    * The text before the first colon is the prefix. If the prefix is known, it is replaced with its
    * namespace. This is also done for a value like `http://example.org` when `http` is declared as
    * a prefix.
    *
    * A non-empty value without a colon, such as `Person`, is put after the default namespace, if
    * the map has one and the value is a valid CURIE.
    *
    * @return
    *   The URI, or null if the value has an unknown prefix, or has no colon and can't be put after
    *   the default namespace
    */
  def expandOrNull(curie: String): String = {
    val index = curie.indexOf(':')
    if (index < 0) {
      if ((defaultNamespace ne null) && !curie.isEmpty && UriCurieValidator.isValidCurie(curie))
        defaultNamespace.concat(curie)
      else null
    } else {
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
    *   The CURIE, or null if no namespace gives one, or if this map was made with
    *   [[withoutCompaction]]
    */
  def compactOrNull(uri: String): String = {
    val uriLen = uri.length
    val len = compactEntries.length
    var i = 0
    while (i < len) {
      val entry = compactEntries(i)
      val ns = entry.getKey
      val nsLen = ns.length
      if (
        nsLen < uriLen && uri.startsWith(ns) &&
        (uri.charAt(nsLen) != '/' || nsLen + 1 == uriLen || uri.charAt(nsLen + 1) != '/')
      ) {
        val curie = entry.getValue + ':' + uri.substring(nsLen)
        if (UriCurieValidator.isValidCurie(curie)) return curie
      }
      i += 1
    }
    null
  }

  /** A copy of this map that expands CURIEs as this one does, but compacts no URIs, so they are
    * written in full.
    */
  def withoutCompaction: PrefixMap = new PrefixMap(overrides, defaults, defaultNamespace, false)

  override def toString: String =
    s"PrefixMap(overrides = $overrides, defaults = $defaults, defaultNamespace = $defaultNamespace, compacts = $compacts)"
}

object PrefixMap {

  /** A map with no prefixes. Decoding and encoding with it keep values as they are written. */
  val empty: PrefixMap = new PrefixMap(Nil, Nil, null, true)

  /** Create a map with overrides and defaults.
    *
    * @param overrides
    *   Prefixes that have precedence over the defaults
    * @param defaults
    *   Prefixes used when the overrides don't have them
    * @param defaultNamespace
    *   Namespace for values without a colon
    */
  def apply(
      overrides: Iterable[(String, String)] = Nil,
      defaults: Iterable[(String, String)] = Nil,
      defaultNamespace: Option[String] = None,
  ): PrefixMap = new PrefixMap(overrides.toSeq, defaults.toSeq, defaultNamespace.orNullFast, true)
}
