package eu.neverblink.linkml.tests.conformance

import eu.neverblink.linkml.yaml.LinkmlYamlCodec

object Codec {
  given manifestCodec: LinkmlYamlCodec[Manifest] = LinkmlYamlCodec.derived
  given testCodec: LinkmlYamlCodec[Test] = LinkmlYamlCodec.derived
}
