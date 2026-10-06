package eu.neverblink.linkml.schemaview

/** A regex as LinkML's `pattern` metaslot means it.
  *
  * A LinkML pattern matches anywhere in the value unless anchored with `^` and `$`, as in JSON
  * Schema and SHACL. An XSD pattern (also used by OWL 2) always matches the whole value, and `^`
  * and `$` are ordinary characters in it. So LinkML `^abc$` is XSD `abc`, and LinkML `abc` is XSD
  * `.*abc.*`. A top-level `|` gets wrapped in brackets so the anchors cover all of it.
  */
opaque type LinkmlPattern = String

object LinkmlPattern {

  /** The pattern as written in a schema. */
  def apply(pattern: String): LinkmlPattern = pattern

  def option(pattern: Option[String]): Option[LinkmlPattern] = pattern

  def fromXsd(pattern: String): LinkmlPattern = {
    val start = pattern.startsWith(".*")
    val end = pattern.length >= (if start then 4 else 2) && endsWith(pattern, ".*")
    val inner = pattern.substring(if start then 2 else 0, pattern.length - (if end then 2 else 0))
    val body = if hasTopLevelAlternation(inner) then s"($inner)" else inner
    (if start then "" else "^") + body + (if end then "" else "$")
  }

  extension (p: LinkmlPattern) {

    def linkml: String = p

    def xsd: String = {
      val start = p.anchoredAtStart
      val end = p.anchoredAtEnd
      val inner = p.substring(if start then 1 else 0, p.length - (if end then 1 else 0))
      val body = if hasTopLevelAlternation(inner) then s"($inner)" else inner
      (if start then "" else ".*") + body + (if end then "" else ".*")
    }

    def anchoredAtStart: Boolean = p.startsWith("^")

    def anchoredAtEnd: Boolean = endsWith(p, "$")
  }

  /** Like `String.endsWith`, but false when the suffix is escaped with a backslash. */
  private def endsWith(pattern: String, suffix: String): Boolean =
    pattern.endsWith(suffix) && {
      val backslashes =
        pattern.substring(0, pattern.length - suffix.length).reverseIterator.takeWhile(_ == '\\')
      backslashes.size % 2 == 0
    }

  /** Whether there is a `|` outside any group or character class. */
  private def hasTopLevelAlternation(pattern: String): Boolean = {
    var depth = 0
    var inClass = false
    var i = 0
    while i < pattern.length do {
      pattern(i) match {
        case '\\' => i += 1
        case '[' if !inClass => inClass = true
        case ']' if inClass => inClass = false
        case '(' if !inClass => depth += 1
        case ')' if !inClass => depth -= 1
        case '|' if !inClass && depth == 0 => return true
        case _ =>
      }
      i += 1
    }
    false
  }
}
