package eu.neverblink.linkml.runtime

/** Runtime implementation of linkml:Any.
  *
  * @param value
  *   Content of the Any, encoded as a String.
  *
  * @note
  *   Extension methods for working with linkml:Any are available in the schemaview module:
  *   `import eu.neverblink.linkml.schemaview.{yaml, yamlAs}`
  */
final case class LinkMlAny(value: String):
  override def toString: String = value

type Unknown = LinkMlAny
