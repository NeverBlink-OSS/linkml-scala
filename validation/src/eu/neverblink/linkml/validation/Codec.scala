package eu.neverblink.linkml.validation

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.linkml.yaml.LinkmlYamlCodec.TypeDesignatorEntry

/** Codec for serializing and deserializing a [[SchemaValidationReport]].
  *
  * TODO LNK-187: auto-generate this codec from the schema
  */
object Codec {

  private val invalidDefaultRange: LinkmlYamlCodec[InvalidDefaultRangeImpl] =
    LinkmlYamlCodec.derived
  private val invalidKeyOrIdSlotType: LinkmlYamlCodec[InvalidKeyOrIdSlotTypeImpl] =
    LinkmlYamlCodec.derived
  private val invalidRange: LinkmlYamlCodec[InvalidRangeImpl] = LinkmlYamlCodec.derived
  private val invalidSlotUsage: LinkmlYamlCodec[InvalidSlotUsageImpl] = LinkmlYamlCodec.derived
  private val invalidUriOrCurie: LinkmlYamlCodec[InvalidUriOrCurieImpl] = LinkmlYamlCodec.derived
  private val multipleKeyOrIdSlots: LinkmlYamlCodec[MultipleKeyOrIdSlotsImpl] =
    LinkmlYamlCodec.derived
  private val multipleTreeRoots: LinkmlYamlCodec[MultipleTreeRootsImpl] = LinkmlYamlCodec.derived
  private val noTreeRootClass: LinkmlYamlCodec[NoTreeRootClassImpl] = LinkmlYamlCodec.derived
  private val nonUniqueName: LinkmlYamlCodec[NonUniqueNameImpl] = LinkmlYamlCodec.derived
  private val schemaIdClash: LinkmlYamlCodec[SchemaIdClashImpl] = LinkmlYamlCodec.derived
  private val schemaImportError: LinkmlYamlCodec[SchemaImportErrorImpl] = LinkmlYamlCodec.derived
  private val schemaParseError: LinkmlYamlCodec[SchemaParseErrorImpl] = LinkmlYamlCodec.derived
  private val undefinedDefaultRange: LinkmlYamlCodec[UndefinedDefaultRangeImpl] =
    LinkmlYamlCodec.derived
  private val undefinedPrefix: LinkmlYamlCodec[UndefinedPrefixImpl] = LinkmlYamlCodec.derived
  private val unexpectedError: LinkmlYamlCodec[UnexpectedErrorImpl] = LinkmlYamlCodec.derived
  private val unknownReference: LinkmlYamlCodec[UnknownReferenceImpl] = LinkmlYamlCodec.derived
  private val unknownStringReference: LinkmlYamlCodec[UnknownStringReferenceImpl] =
    LinkmlYamlCodec.derived

  private given issueCodec: LinkmlYamlCodec[SchemaIssue] =
    LinkmlYamlCodec.typeDesignatorCodec(
      "issue_type",
      Seq(
        TypeDesignatorEntry(
          "InvalidDefaultRange",
          classOf[InvalidDefaultRangeImpl],
          invalidDefaultRange,
        ),
        TypeDesignatorEntry(
          "InvalidKeyOrIdSlotType",
          classOf[InvalidKeyOrIdSlotTypeImpl],
          invalidKeyOrIdSlotType,
        ),
        TypeDesignatorEntry("InvalidRange", classOf[InvalidRangeImpl], invalidRange),
        TypeDesignatorEntry("InvalidSlotUsage", classOf[InvalidSlotUsageImpl], invalidSlotUsage),
        TypeDesignatorEntry("InvalidUriOrCurie", classOf[InvalidUriOrCurieImpl], invalidUriOrCurie),
        TypeDesignatorEntry(
          "MultipleKeyOrIdSlots",
          classOf[MultipleKeyOrIdSlotsImpl],
          multipleKeyOrIdSlots,
        ),
        TypeDesignatorEntry("MultipleTreeRoots", classOf[MultipleTreeRootsImpl], multipleTreeRoots),
        TypeDesignatorEntry("NoTreeRootClass", classOf[NoTreeRootClassImpl], noTreeRootClass),
        TypeDesignatorEntry("NonUniqueName", classOf[NonUniqueNameImpl], nonUniqueName),
        TypeDesignatorEntry("SchemaIdClash", classOf[SchemaIdClashImpl], schemaIdClash),
        TypeDesignatorEntry("SchemaImportError", classOf[SchemaImportErrorImpl], schemaImportError),
        TypeDesignatorEntry("SchemaParseError", classOf[SchemaParseErrorImpl], schemaParseError),
        TypeDesignatorEntry(
          "UndefinedDefaultRange",
          classOf[UndefinedDefaultRangeImpl],
          undefinedDefaultRange,
        ),
        TypeDesignatorEntry("UndefinedPrefix", classOf[UndefinedPrefixImpl], undefinedPrefix),
        TypeDesignatorEntry("UnexpectedError", classOf[UnexpectedErrorImpl], unexpectedError),
        TypeDesignatorEntry("UnknownReference", classOf[UnknownReferenceImpl], unknownReference),
        TypeDesignatorEntry(
          "UnknownStringReference",
          classOf[UnknownStringReferenceImpl],
          unknownStringReference,
        ),
      ),
    )

  implicit val codec: LinkmlYamlCodec[SchemaValidationReportImpl] = LinkmlYamlCodec.derived
}
