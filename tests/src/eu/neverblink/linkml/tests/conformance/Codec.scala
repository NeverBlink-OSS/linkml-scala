package eu.neverblink.linkml.tests.conformance

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.linkml.yaml.LinkmlYamlCodec.TypeDesignatorEntry

object Codec {
  given manifestCodec: LinkmlYamlCodec[Manifest] = LinkmlYamlCodec.derived
  given testCodec: LinkmlYamlCodec[Test] = LinkmlYamlCodec.derived

  given LinkmlYamlCodec[Action] = LinkmlYamlCodec.typeDesignatorCodec(
    "type",
    Seq(
      TypeDesignatorEntry(
        "JsonSchemaGenerate",
        classOf[JsonSchemaGenerate],
        LinkmlYamlCodec.derived[JsonSchemaGenerate],
      ),
      TypeDesignatorEntry(
        "LoadAction",
        classOf[LoadAction],
        LinkmlYamlCodec.derived[LoadAction],
      ),
      TypeDesignatorEntry(
        "DeriveAction",
        classOf[DeriveAction],
        LinkmlYamlCodec.derived[DeriveAction],
      ),
      TypeDesignatorEntry(
        "LintAction",
        classOf[LintAction],
        LinkmlYamlCodec.derived[LintAction],
      ),
    ),
  )

  given LinkmlYamlCodec[Assertion] = LinkmlYamlCodec.typeDesignatorCodec(
    "type",
    Seq(
      TypeDesignatorEntry(
        "LoadsAssertion",
        classOf[LoadsAssertion],
        LinkmlYamlCodec.derived[LoadsAssertion],
      ),
      TypeDesignatorEntry(
        "StringAssertion",
        classOf[StringAssertion],
        LinkmlYamlCodec.derived[StringAssertion],
      ),
      TypeDesignatorEntry(
        "JsonPointerAssertion",
        classOf[JsonPointerAssertion],
        LinkmlYamlCodec.derived[JsonPointerAssertion],
      ),
      TypeDesignatorEntry(
        "JsonSchemaAccepts",
        classOf[JsonSchemaAccepts],
        LinkmlYamlCodec.derived[JsonSchemaAccepts],
      ),
      TypeDesignatorEntry(
        "JsonSchemaRejects",
        classOf[JsonSchemaRejects],
        LinkmlYamlCodec.derived[JsonSchemaRejects],
      ),
    ),
  )
}
