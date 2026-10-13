package eu.neverblink.linkml.validation

import eu.neverblink.linkml.yaml.LinkmlYamlCodec

/** Codec for serializing and deserializing a [[SchemaValidationReport]].
  *
  * The `issue_type` field tells which kind of issue it is. The codec picks the issue class with the
  * `@typeDesignator` annotation on [[SchemaIssue]].
  */
object Codec {
  implicit val codec: LinkmlYamlCodec[SchemaValidationReport] = LinkmlYamlCodec.derived
}
