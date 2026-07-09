package eu.neverblink.linkml.generator.tableschema

import eu.neverblink.linkml
import eu.neverblink.linkml.schemaview
import eu.neverblink.linkml.schemaview.*
import eu.neverblink.tableschema.*

import scala.util.Try

class TableSchemaGenerator(using sv: SchemaView) {
  def remapType(rt: RuntimeType): String = {
    rt match {
      case StringType => FieldDescriptorTypes.string
      case IntegerType => FieldDescriptorTypes.integer
      case FloatType => FieldDescriptorTypes.number
      case DoubleType => FieldDescriptorTypes.number
      case BooleanType => FieldDescriptorTypes.boolean
      case DecimalType => FieldDescriptorTypes.number
      case AnyType => FieldDescriptorTypes.any

      case DateType => FieldDescriptorTypes.date
      case DateTimeType => FieldDescriptorTypes.datetime
      case TimeType => FieldDescriptorTypes.time
      case UriOrCurieType => FieldDescriptorTypes.string
      case UriType => FieldDescriptorTypes.string
      case CurieType => FieldDescriptorTypes.string
      case NcNameType => FieldDescriptorTypes.string
      case UnknownType => FieldDescriptorTypes.any
    }
  }

  def slotName(slotView: SlotView): String =
    slotView.slot.alias.getOrElse(Case.deSpaceCase(slotView.slot.name))

  def generate(treeRootOverride: Option[String]): TableDescriptor = {
    val root: ClassView = sv.treeRootWithOverride(treeRootOverride)
      .get.getOrElse(sys.error("no tree root"))
    val fields =
      for slotView <- root.derivedAttributes.values
      yield {
        val base = FieldDescriptor(
          name = slotName(slotView),
          title = slotView.slot.title,
          description = slotView.slot.description,
          constraints =
            if slotView.slot.required
            then Some(Constraints(required = Some(true)))
            else None,
        )
        slotView.derivedRangeView.resolve.get match {
          case cls: ClassView =>
            if cls.uriStr == "https://w3id.org/linkml/Any" then
              base.copy(
                `type` = FieldDescriptorTypes.any,
              )
            else if !slotView.derivedInlined then {
              ???
            } else
              InlineType(slotView) match {
                case InlineType.plain =>
                  base.copy(`type` = FieldDescriptorTypes.`object`, rdfType = Some(cls.uriStr))
                case InlineType.optional =>
                  base.copy(`type` = FieldDescriptorTypes.`object`, rdfType = Some(cls.uriStr))
                case InlineType.list =>
                  base.copy(`type` = FieldDescriptorTypes.array, rdfType = Some(cls.uriStr))
                case InlineType.dict(form) =>
                  base.copy(`type` = FieldDescriptorTypes.`object`, rdfType = Some(cls.uriStr))
              }
          case tv: TypeView =>
            base.copy(`type` = remapType(tv.runtimeType), rdfType = Some(tv.uriStr))
          case ev: EnumView =>
            val values = Some(Seq())
            base.copy(
            `type` = FieldDescriptorTypes.string,
            rdfType = Some(ev.uriStr),
            constraints = base.constraints.map(_.copy(`enum` = values))
              .orElse(Some(Constraints(
              `enum` = values
            )))
          )
          case _ => throw RuntimeException("bad")
        }
      }
    TableDescriptor(
      fields = fields.toSeq,
      primaryKey = root.identifier.map(slotName),
    )
  }
}
