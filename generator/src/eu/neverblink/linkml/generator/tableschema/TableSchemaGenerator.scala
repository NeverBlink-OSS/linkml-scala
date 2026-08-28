package eu.neverblink.linkml.generator.tableschema

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, WriterConfig}
import com.github.plokhotnyuk.jsoniter_scala.macros.{CodecMakerConfig, JsonCodecMaker}
import eu.neverblink.linkml.generator.JsonDocumentGenerator
import eu.neverblink.linkml.generator.tableschema.FieldDescriptor.types
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.linkml.runtime.FastUtils.*

class TableSchemaGenerator(using sv: SchemaView)
    extends JsonDocumentGenerator[TableSchemaGenerator.Options, TableDescriptor] {

  override protected def defaultOptions: TableSchemaGenerator.Options =
    TableSchemaGenerator.Options()

  override protected def codec: JsonValueCodec[TableDescriptor] = TableSchemaGenerator.codec

  override protected def writerConfig(options: TableSchemaGenerator.Options): WriterConfig =
    WriterConfig.withIndentionStep(2)

  /** Map the [[RuntimeType]] to the appropriate Table Schema (type, format) tuple
    */
  private def remapType(rt: RuntimeType): (String, String) = rt match {
    case _: StringType.type => (types.string, "default")
    case _: IntegerType.type => (types.integer, "default")
    case _: FloatType.type => (types.number, "default")
    case _: DoubleType.type => (types.number, "default")
    case _: BooleanType.type => (types.boolean, "default")
    case _: DecimalType.type => (types.number, "default")
    case _: AnyType.type => (types.any, "default")
    case _: DateType.type => (types.date, "any")
    case _: DateTimeType.type => (types.datetime, "any")
    case _: TimeType.type => (types.time, "any")
    case _: UriOrCurieType.type => (types.string, "default")
    case _: UriType.type => (types.string, "uri")
    case _: CurieType.type => (types.string, "default")
    case _: NcNameType.type => (types.string, "default")
    case _: LocalizedTextType.type => (types.string, "default") // TODO LNK-195
    case _: UnknownType.type => (types.any, "default")
  }

  /** Get the name of the slot, respecting alias, and LinkML casing rules
    */
  def slotName(slotView: SlotView): String =
    slotView.slot.alias.getOrElseFast(Case.escaped(slotView.slot.name))

  /** Generate the Table Schema
    *
    * @param options
    *   What to generate. See [[TableSchemaGenerator.Options]].
    * @return
    *   Generated Table Schema (Table Descriptor)
    */
  override def generate(
      options: TableSchemaGenerator.Options = TableSchemaGenerator.Options(),
  ): TableDescriptor = {
    val root: ClassView = sv.treeRootWithOverride(options.treeRoot)
      .get.getOrElse(throw RuntimeException("No tree root - can't generate table schema"))
    val fields =
      for slotView <- root.derivedAttributes.values.toSeq.sortBy(s => (s.slot.rank, s.slot.name))
      yield {
        val base = FieldDescriptor(
          name = slotName(slotView),
          title = slotView.slot.title,
          description = slotView.slot.description.flatMapFast(_.inLanguage(options.metadataLanguage)),
          constraints = new Some(new Constraints(required = new Some(slotView.slot.required))),
        )
        slotView.derivedRange.resolve.get match {
          case cls: ClassView =>
            if cls.isAny then
              base.copy(
                `type` = types.any,
                format = "any",
              )
            else if !slotView.derivedInlined then {
              // If we ever write a full data-package generator then this should add foreign keys to the root
              cls.identifier.get.derivedRange.resolve.get match {
                case tv: TypeView =>
                  val (type_, format) = remapType(tv.runtimeType)
                  base.copy(`type` = type_, rdfType = new Some(cls.uriStr), format = format)
                case _ => throw RuntimeException("ID slot is not type")
              }
            } else
              InlineType(slotView) match {
                case InlineType.list =>
                  base.copy(`type` = types.array, rdfType = new Some(cls.uriStr))
                // plain is JSON objects, optional is JSON object or null, dict inlines are JSON objects
                case _ =>
                  base.copy(`type` = types.`object`, rdfType = new Some(cls.uriStr))
              }
          case tv: TypeView =>
            val (type_, format) = remapType(tv.runtimeType)
            if !slotView.slot.multivalued then
              base.copy(
                `type` = type_,
                rdfType = new Some(tv.uriStr),
                format = format,
                constraints = base.constraints.mapFast(
                  _.copy(
                    pattern = slotView.slot.pattern.orElseFast(tv._type.pattern),
                    maximum = slotView.slot.maximumValue
                      .orElseFast(tv._type.maximumValue).mapFast(_.value.strip()),
                    minimum = slotView.slot.minimumValue
                      .orElseFast(tv._type.minimumValue).mapFast(_.value.strip()),
                  ),
                ),
              )
            else
              base.copy(
                `type` = types.array,
                rdfType = new Some(tv.uriStr),
              )
          case ev: EnumView =>
            val values = new Some(ev.toMeaning.keys.toSeq)
            if !slotView.slot.multivalued then
              base.copy(
                `type` = types.string,
                rdfType = new Some(ev.uriStr),
                constraints = base.constraints.mapFast(_.copy(`enum` = values)),
              )
            else
              base.copy(
                `type` = types.array,
                rdfType = new Some(ev.uriStr),
                // no multivalued enums in table schema...
              )
          case _ => throw RuntimeException(s"Couldn't map range ${slotView.derivedRange}")
        }
      }
    TableDescriptor(
      fields = fields.toSeq,
      primaryKey = root.identifier.map(slotName),
    )
  }

}

object TableSchemaGenerator {

  /** Options for [[TableSchemaGenerator]].
    *
    * @param treeRoot
    *   If defined, override the schema `tree_root` class with the one provided.
    * @param metadataLanguage
    *   Which language to use for metadata fields (description) in the generated Table Schema.
    */
  final case class Options(
      treeRoot: Option[String] = None,
      metadataLanguage: String = "en",
  )

  implicit val codec: JsonValueCodec[TableDescriptor] =
    JsonCodecMaker.make(
      CodecMakerConfig.withEncodingOnly(true)
        .withDiscriminatorFieldName(None)
        .withTransientDefault(false)
        .withTransientEmpty(false),
    )
}
