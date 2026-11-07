package eu.neverblink.linkml.runtime

/** Runtime implementation of linkml:Any.
  *
  * @param value
  *   Content of the Any, encoded as a [[String]], in some format.
  *
  * @note
  *   When using LinkmlYamlCodec, the [[value]] is encoded as YAML. Extension methods are available
  *   in the schemaview module: `import eu.neverblink.linkml.schemaview.{yaml, yamlAs}`
  */
@flatten final case class LinkmlAny(value: String):
  override def toString: String = value

object LinkmlAny {

  /** Encodes a string as a double-quoted YAML scalar, escaped so it reads back unchanged. */
  def text(value: String): LinkmlAny = {
    val sb = new java.lang.StringBuilder(value.length + 4)
    sb.append('"')
    var i = 0
    while (i < value.length) {
      val c = value.charAt(i)
      if (c == '"') sb.append("\\\"")
      else if (c == '\\') sb.append("\\\\")
      else if (c == '\n') sb.append("\\n")
      else if (c == '\r') sb.append("\\r")
      else if (c == '\t') sb.append("\\t")
      else if (c < ' ' || c == '\u2028' || c == '\u2029' || c == '\u0085') {
        sb.append("\\u").append(hexDigits.charAt(c >> 12)).append(hexDigits.charAt((c >> 8) & 0xf))
          .append(hexDigits.charAt((c >> 4) & 0xf)).append(hexDigits.charAt(c & 0xf))
      } else sb.append(c)
      i += 1
    }
    LinkmlAny(sb.append("\"\n").toString)
  }

  private val hexDigits = "0123456789abcdef"
}

/** Alias for unknown types - this should be generated when a type does not have a `base` defined.
  */
type Unknown = LinkmlAny
