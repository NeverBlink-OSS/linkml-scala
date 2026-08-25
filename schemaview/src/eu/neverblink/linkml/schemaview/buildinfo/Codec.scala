package eu.neverblink.linkml.schemaview.buildinfo

import eu.neverblink.linkml.yaml.LinkmlYamlCodec

/** Codec for serializing and deserializing a [[BuildInfo]].
  *
  * TODO LNK-187: auto-generate this codec from the schema
  */
object Codec {
  implicit val codec: LinkmlYamlCodec[BuildInfoImpl] = LinkmlYamlCodec.derived
}
