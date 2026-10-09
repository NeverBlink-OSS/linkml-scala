package eu.neverblink.linkml.validation

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.linkml.yaml.LinkmlYamlCodec.TypeDesignatorEntry

/** Codec for serializing and deserializing a [[SchemaValidationReport]].
  *
  * TODO LNK-187: auto-generate this codec from the schema
  */
object Codec {

  private val cyclicReference: LinkmlYamlCodec[CyclicReference] = LinkmlYamlCodec.derived
  private val invalidDefaultRange: LinkmlYamlCodec[InvalidDefaultRange] =
    LinkmlYamlCodec.derived
  private val invalidKeyOrIdSlotType: LinkmlYamlCodec[InvalidKeyOrIdSlotType] =
    LinkmlYamlCodec.derived
  private val invalidRange: LinkmlYamlCodec[InvalidRange] = LinkmlYamlCodec.derived
  private val invalidSlotUsage: LinkmlYamlCodec[InvalidSlotUsage] = LinkmlYamlCodec.derived
  private val invalidUriOrCurie: LinkmlYamlCodec[InvalidUriOrCurie] =
    LinkmlYamlCodec.derived
  private val multipleKeyOrIdSlots: LinkmlYamlCodec[MultipleKeyOrIdSlots] =
    LinkmlYamlCodec.derived
  private val multipleTreeRoots: LinkmlYamlCodec[MultipleTreeRoots] = LinkmlYamlCodec.derived
  private val noTreeRootClass: LinkmlYamlCodec[NoTreeRootClass] = LinkmlYamlCodec.derived
  private val nonUniqueName: LinkmlYamlCodec[NonUniqueName] = LinkmlYamlCodec.derived
  private val schemaIdClash: LinkmlYamlCodec[SchemaIdClash] = LinkmlYamlCodec.derived
  private val schemaImportError: LinkmlYamlCodec[SchemaImportError] = LinkmlYamlCodec.derived
  private val schemaParseError: LinkmlYamlCodec[SchemaParseError] = LinkmlYamlCodec.derived
  private val undefinedDefaultRange: LinkmlYamlCodec[UndefinedDefaultRange] =
    LinkmlYamlCodec.derived
  private val undefinedPrefix: LinkmlYamlCodec[UndefinedPrefix] = LinkmlYamlCodec.derived
  private val unexpectedError: LinkmlYamlCodec[UnexpectedError] = LinkmlYamlCodec.derived
  private val unknownReference: LinkmlYamlCodec[UnknownReference] = LinkmlYamlCodec.derived
  private val unknownStringReference: LinkmlYamlCodec[UnknownStringReference] =
    LinkmlYamlCodec.derived
  private val emptyName: LinkmlYamlCodec[EmptyName] = LinkmlYamlCodec.derived
  private val flankingSeparator: LinkmlYamlCodec[FlankingSeparator] = LinkmlYamlCodec.derived
  private val nonAsciiName: LinkmlYamlCodec[NonAsciiName] = LinkmlYamlCodec.derived
  private val nonStandardSeparator: LinkmlYamlCodec[NonStandardSeparator] =
    LinkmlYamlCodec.derived
  private val repeatedSeparator: LinkmlYamlCodec[RepeatedSeparator] = LinkmlYamlCodec.derived

  private given issueCodec: LinkmlYamlCodec[SchemaIssue] =
    LinkmlYamlCodec.typeDesignatorCodec(
      "issue_type",
      Seq(
        TypeDesignatorEntry("CyclicReference", classOf[CyclicReference], cyclicReference),
        TypeDesignatorEntry(
          "InvalidDefaultRange",
          classOf[InvalidDefaultRange],
          invalidDefaultRange,
        ),
        TypeDesignatorEntry(
          "InvalidKeyOrIdSlotType",
          classOf[InvalidKeyOrIdSlotType],
          invalidKeyOrIdSlotType,
        ),
        TypeDesignatorEntry("InvalidRange", classOf[InvalidRange], invalidRange),
        TypeDesignatorEntry("InvalidSlotUsage", classOf[InvalidSlotUsage], invalidSlotUsage),
        TypeDesignatorEntry(
          "InvalidUriOrCurie",
          classOf[InvalidUriOrCurie],
          invalidUriOrCurie,
        ),
        TypeDesignatorEntry(
          "MultipleKeyOrIdSlots",
          classOf[MultipleKeyOrIdSlots],
          multipleKeyOrIdSlots,
        ),
        TypeDesignatorEntry("MultipleTreeRoots", classOf[MultipleTreeRoots], multipleTreeRoots),
        TypeDesignatorEntry("NoTreeRootClass", classOf[NoTreeRootClass], noTreeRootClass),
        TypeDesignatorEntry("NonUniqueName", classOf[NonUniqueName], nonUniqueName),
        TypeDesignatorEntry("SchemaIdClash", classOf[SchemaIdClash], schemaIdClash),
        TypeDesignatorEntry("SchemaImportError", classOf[SchemaImportError], schemaImportError),
        TypeDesignatorEntry("SchemaParseError", classOf[SchemaParseError], schemaParseError),
        TypeDesignatorEntry(
          "UndefinedDefaultRange",
          classOf[UndefinedDefaultRange],
          undefinedDefaultRange,
        ),
        TypeDesignatorEntry("UndefinedPrefix", classOf[UndefinedPrefix], undefinedPrefix),
        TypeDesignatorEntry("UnexpectedError", classOf[UnexpectedError], unexpectedError),
        TypeDesignatorEntry("UnknownReference", classOf[UnknownReference], unknownReference),
        TypeDesignatorEntry(
          "UnknownStringReference",
          classOf[UnknownStringReference],
          unknownStringReference,
        ),
        TypeDesignatorEntry("EmptyName", classOf[EmptyName], emptyName),
        TypeDesignatorEntry(
          "FlankingSeparator",
          classOf[FlankingSeparator],
          flankingSeparator,
        ),
        TypeDesignatorEntry("NonAsciiName", classOf[NonAsciiName], nonAsciiName),
        TypeDesignatorEntry(
          "NonStandardSeparator",
          classOf[NonStandardSeparator],
          nonStandardSeparator,
        ),
        TypeDesignatorEntry(
          "RepeatedSeparator",
          classOf[RepeatedSeparator],
          repeatedSeparator,
        ),
      ),
    )

  implicit val codec: LinkmlYamlCodec[SchemaValidationReport] = LinkmlYamlCodec.derived
}
