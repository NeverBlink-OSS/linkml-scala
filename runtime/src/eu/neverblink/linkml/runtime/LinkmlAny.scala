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
final case class LinkmlAny(value: String):
  override def toString: String = value

/** Alias for unknown types - this should be generated when a type does not have a `base` defined.
  */
type Unknown = LinkmlAny
