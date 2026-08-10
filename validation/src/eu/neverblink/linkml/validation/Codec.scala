package eu.neverblink.linkml.validation

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.Node

/** Codec for serializing a [[SchemaValidationReport]].
  *
  * Only encoding is supported: a report has no type designator, so the concrete issue type cannot
  * be recovered when reading one back.
  *
  * TODO LNK-???: auto-generate this codec from the schema
  */
object Codec {

  private given issueCodec: LinkmlYamlCodec[SchemaIssue] = new LinkmlYamlCodec[SchemaIssue] {
    override def decode(node: Node, id: Option[Any]): SchemaIssue =
      throw UnsupportedOperationException("Reading a validation report back is not supported")

    override def encode(issue: SchemaIssue, skipId: Boolean): Node = issue match {
      case i: InvalidDefaultRangeImpl => invalidDefaultRange.encode(i, skipId)
      case i: InvalidKeyOrIdSlotTypeImpl => invalidKeyOrIdSlotType.encode(i, skipId)
      case i: InvalidRangeImpl => invalidRange.encode(i, skipId)
      case i: InvalidSlotUsageImpl => invalidSlotUsage.encode(i, skipId)
      case i: InvalidUriOrCurieImpl => invalidUriOrCurie.encode(i, skipId)
      case i: MultipleKeyOrIdSlotsImpl => multipleKeyOrIdSlots.encode(i, skipId)
      case i: MultipleTreeRootsImpl => multipleTreeRoots.encode(i, skipId)
      case i: NoTreeRootClassImpl => noTreeRootClass.encode(i, skipId)
      case i: NonUniqueNameImpl => nonUniqueName.encode(i, skipId)
      case i: SchemaIdClashImpl => schemaIdClash.encode(i, skipId)
      case i: SchemaImportErrorImpl => schemaImportError.encode(i, skipId)
      case i: SchemaParseErrorImpl => schemaParseError.encode(i, skipId)
      case i: UndefinedDefaultRangeImpl => undefinedDefaultRange.encode(i, skipId)
      case i: UnexpectedErrorImpl => unexpectedError.encode(i, skipId)
      case i: UndefinedPrefixImpl => undefinedPrefix.encode(i, skipId)
      case i: UnknownReferenceImpl => unknownReference.encode(i, skipId)
      case i: UnknownStringReferenceImpl => unknownStringReference.encode(i, skipId)
      case other =>
        throw UnsupportedOperationException(
          s"No codec for issue type '${other.getClass.getName}'. Add it to Codec.issueCodec.",
        )
    }
  }

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

  implicit val codec: LinkmlYamlCodec[SchemaValidationReportImpl] = LinkmlYamlCodec.derived
}
