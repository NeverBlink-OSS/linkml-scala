package eu.neverblink.linkml.rdf

/** A base IRI to resolve references against, per RFC 3986 section 5.2. No normalization is done
  * beyond removing dot segments from the paths that the algorithm merges.
  *
  * This is needed to fully support Turtle parsing.
  *
  * @param authority
  *   null when the IRI has none, which is not the same as an empty one (`file:///x`)
  * @param query
  *   null when the IRI has none
  */
private[rdf] final class BaseIri private (
    val iri: String,
    scheme: String,
    authority: String,
    path: String,
    query: String,
) {
  import BaseIri.*

  /** Resolve `ref`, which may already be absolute. An absolute IRI is returned as it is, not with
    * its dot segments removed, so that every IRI a writer writes in full reads back unchanged.
    */
  def resolve(ref: String): String = {
    if (RdfSyntax.hasScheme(ref)) return ref
    val len = ref.length
    val hash = ref.indexOf('#')
    val beforeFragment = if (hash < 0) len else hash
    val question = ref.indexOf('?')
    val hasQuery = question >= 0 && question < beforeFragment
    val pathEnd = if (hasQuery) question else beforeFragment

    val sb = new java.lang.StringBuilder(iri.length + len)
    sb.append(scheme).append(':')
    if (ref.startsWith("//")) {
      var authorityEnd = 2
      while (authorityEnd < pathEnd && ref.charAt(authorityEnd) != '/') authorityEnd += 1
      sb.append(ref, 0, authorityEnd)
      removeDotSegments(sb, ref.substring(authorityEnd, pathEnd))
      return sb.append(ref, pathEnd, len).toString
    }
    if (authority != null) sb.append("//").append(authority)
    if (pathEnd == 0) {
      sb.append(path)
      if (!hasQuery && query != null) sb.append('?').append(query)
    } else if (ref.charAt(0) == '/') removeDotSegments(sb, ref.substring(0, pathEnd))
    else removeDotSegments(sb, merge(ref.substring(0, pathEnd)))
    sb.append(ref, pathEnd, len).toString
  }

  private def merge(refPath: String): String =
    if (authority != null && path.isEmpty) "/".concat(refPath)
    else path.substring(0, path.lastIndexOf('/') + 1).concat(refPath)
}

private[rdf] object BaseIri {

  /** @throws IllegalArgumentException if `iri` is not absolute */
  def apply(iri: String): BaseIri = {
    if (!RdfSyntax.hasScheme(iri))
      throw new IllegalArgumentException(s"The base IRI must be absolute: '$iri'")
    val colon = iri.indexOf(':')
    val hash = iri.indexOf('#')
    val end = if (hash < 0) iri.length else hash
    val question = iri.indexOf('?')
    val pathEnd = if (question >= 0 && question < end) question else end
    var pathStart = colon + 1
    var authority: String = null
    if (iri.startsWith("//", pathStart)) {
      var authorityEnd = pathStart + 2
      while (authorityEnd < pathEnd && iri.charAt(authorityEnd) != '/') authorityEnd += 1
      authority = iri.substring(pathStart + 2, authorityEnd)
      pathStart = authorityEnd
    }
    new BaseIri(
      iri,
      iri.substring(0, colon),
      authority,
      iri.substring(pathStart, pathEnd),
      if (pathEnd < end) iri.substring(pathEnd + 1, end) else null,
    )
  }

  /** Append `path` without its `.` and `..` segments (RFC 3986 section 5.2.4). */
  private def removeDotSegments(out: java.lang.StringBuilder, path: String): Unit = {
    if (!hasDotSegment(path)) {
      out.append(path)
      return
    }
    // The output so far is the scheme and authority, which `..` must never cut into.
    val floor = out.length
    val len = path.length
    var i = 0
    while (i < len) {
      if (path.startsWith("../", i)) i += 3
      else if (path.startsWith("./", i)) i += 2
      else if (path.startsWith("/./", i)) i += 2
      else if (path.startsWith("/.", i) && i + 2 == len) {
        out.append('/')
        i = len
      } else if (path.startsWith("/../", i)) {
        dropLastSegment(out, floor)
        i += 3
      } else if (path.startsWith("/..", i) && i + 3 == len) {
        dropLastSegment(out, floor)
        out.append('/')
        i = len
      } else if (
        (path.startsWith(".", i) && i + 1 == len) || (path.startsWith("..", i) && i + 2 == len)
      )
        i = len
      else {
        // Move the first segment, with its leading '/', to the output.
        var j = if (path.charAt(i) == '/') i + 1 else i
        while (j < len && path.charAt(j) != '/') j += 1
        out.append(path, i, j)
        i = j
      }
    }
  }

  private def hasDotSegment(path: String): Boolean =
    path.startsWith(".") || path.contains("/.")

  private def dropLastSegment(out: java.lang.StringBuilder, floor: Int): Unit = {
    var i = out.length - 1
    while (i >= floor && out.charAt(i) != '/') i -= 1
    out.setLength(math.max(i, floor))
  }
}
