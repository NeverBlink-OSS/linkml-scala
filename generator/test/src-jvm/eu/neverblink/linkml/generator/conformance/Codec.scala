package eu.neverblink.linkml.generator.conformance

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.linkml.yaml.LinkmlYamlCodec.TypeDesignatorEntry

object Codec {
  given manifestCodec: LinkmlYamlCodec[ManifestImpl] = LinkmlYamlCodec.derived
  given testCodec: LinkmlYamlCodec[TestImpl] = LinkmlYamlCodec.derived

  given LinkmlYamlCodec[Action] = LinkmlYamlCodec.typeDesignatorCodec(
    "type",
    Seq(
      TypeDesignatorEntry(
        "JsonSchemaGenerate",
        classOf[JsonSchemaGenerateImpl],
        LinkmlYamlCodec.derived[JsonSchemaGenerateImpl],
      ),
      TypeDesignatorEntry(
        "LoadAction",
        classOf[LoadActionImpl],
        LinkmlYamlCodec.derived[LoadActionImpl],
      ),
      TypeDesignatorEntry(
        "DeriveAction",
        classOf[DeriveActionImpl],
        LinkmlYamlCodec.derived[DeriveActionImpl],
      ),
      TypeDesignatorEntry(
        "LintAction",
        classOf[LintActionImpl],
        LinkmlYamlCodec.derived[LintActionImpl],
      ),
    ),
  )

  given LinkmlYamlCodec[Assertion] = LinkmlYamlCodec.typeDesignatorCodec(
    "type",
    Seq(
      TypeDesignatorEntry(
        "LoadsAssertion",
        classOf[LoadsAssertionImpl],
        LinkmlYamlCodec.derived[LoadsAssertionImpl],
      ),
      TypeDesignatorEntry(
        "StringAssertion",
        classOf[StringAssertionImpl],
        LinkmlYamlCodec.derived[StringAssertionImpl],
      ),
      TypeDesignatorEntry(
        "JsonPathAssertion",
        classOf[JsonPathAssertionImpl],
        LinkmlYamlCodec.derived[JsonPathAssertionImpl],
      ),
      TypeDesignatorEntry(
        "JsonSchemaAccepts",
        classOf[JsonSchemaAcceptsImpl],
        LinkmlYamlCodec.derived[JsonSchemaAcceptsImpl],
      ),
      TypeDesignatorEntry(
        "JsonSchemaRejects",
        classOf[JsonSchemaRejectsImpl],
        LinkmlYamlCodec.derived[JsonSchemaRejectsImpl],
      ),
    ),
  )
}
