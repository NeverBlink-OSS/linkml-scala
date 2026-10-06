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
  def text(value: String): LinkmlAny =
    LinkmlAny(value.flatMap {
      case '"' => "\\\""
      case '\\' => "\\\\"
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case c if c < ' ' || c == '\u2028' || c == '\u2029' || c == '\u0085' => f"\\u${c.toInt}%04x"
      case c => c.toString
    }.mkString("\"", "", "\"\n"))
}

/** Alias for unknown types - this should be generated when a type does not have a `base` defined.
  */
type Unknown = LinkmlAny
