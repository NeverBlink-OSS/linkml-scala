package eu.neverblink.linkml.generator.tableschema

import eu.neverblink.linkml.generator.tableschema.FieldDescriptor.types
import eu.neverblink.linkml.schemaview.*
import io.circe.Codec

class TableSchemaGenerator(using sv: SchemaView) {

  /** Map the [[RuntimeType]] to the appropriate Table Schema (type, format) tuple
    */
  private def remapType(rt: RuntimeType): (String, String) =
    rt match {
      case StringType => (types.string, "default")
      case IntegerType => (types.integer, "default")
      case FloatType => (types.number, "default")
      case DoubleType => (types.number, "default")
      case BooleanType => (types.boolean, "default")
      case DecimalType => (types.number, "default")
      case AnyType => (types.any, "default")
      case DateType => (types.date, "any")
      case DateTimeType => (types.datetime, "any")
      case TimeType => (types.time, "any")
      case UriOrCurieType => (types.string, "default")
      case UriType => (types.string, "uri")
      case CurieType => (types.string, "default")
      case NcNameType => (types.string, "default")
      case UnknownType => (types.any, "default")
    }

  /** Get the name of the slot, respecting alias, and LinkML casing rules
    */
  def slotName(slotView: SlotView): String =
    slotView.slot.alias.getOrElse(Case.deSpaceCase(slotView.slot.name))

  /** Generate the Table Schema
    *
    * @param treeRootOverride
    *   If defined, override the schema `tree_root` class with the one provided
    *
    * @return
    *   Generated Table Schema (Table Descriptor)
    */
  def generate(treeRootOverride: Option[String] = None): TableDescriptor = {
    val root: ClassView = sv.treeRootWithOverride(treeRootOverride)
      .get.getOrElse(throw RuntimeException("No tree root - can't generate table schema"))
    val fields =
      for slotView <- root.derivedAttributes.values
      yield {
        val base = FieldDescriptor(
          name = slotName(slotView),
          title = slotView.slot.title,
          description = slotView.slot.description,
          constraints = Some(Constraints(required = Some(slotView.slot.required))),
        )
        slotView.derivedRangeView.resolve.get match {
          case cls: ClassView =>
            if cls.uriStr == "https://w3id.org/linkml/Any" then
              base.copy(
                `type` = types.any,
                format = "any",
              )
            else if !slotView.derivedInlined then {
              // If we ever write a full data-package generator then this should add foreign keys to the root
              cls.identifier.get.derivedRangeView.resolve.get match {
                case tv: TypeView =>
                  val (type_, format) = remapType(tv.runtimeType)
                  base.copy(`type` = type_, rdfType = Some(cls.uriStr), format = format)
                case _ => throw RuntimeException("ID slot is not type")
              }
            } else
              InlineType(slotView) match {
                case InlineType.list =>
                  base.copy(`type` = types.array, rdfType = Some(cls.uriStr))
                // plain is JSON objects, optional is JSON object or null, dict inlines are JSON objects
                case _ =>
                  base.copy(`type` = types.`object`, rdfType = Some(cls.uriStr))
              }
          case tv: TypeView =>
            val (type_, format) = remapType(tv.runtimeType)
            if !slotView.slot.multivalued then
              base.copy(
                `type` = type_,
                rdfType = Some(tv.uriStr),
                format = format,
                constraints = base.constraints.map(
                  _.copy(
                    pattern = slotView.slot.pattern.orElse(tv._type.pattern),
                    maximum =
                      slotView.slot.maximumValue.orElse(tv._type.maximumValue).map(_.value.strip()),
                    minimum =
                      slotView.slot.minimumValue.orElse(tv._type.minimumValue).map(_.value.strip()),
                  ),
                ),
              )
            else
              base.copy(
                `type` = types.array,
                rdfType = Some(tv.uriStr),
              )
          case ev: EnumView =>
            val values = Some(ev.toMeaning.keys.toSeq)
            if !slotView.slot.multivalued then
              base.copy(
                `type` = types.string,
                rdfType = Some(ev.uriStr),
                constraints = base.constraints.map(_.copy(`enum` = values)),
              )
            else
              base.copy(
                `type` = types.array,
                rdfType = Some(ev.uriStr),
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

  /** Generate the Table Schema and serialize
    *
    * @param treeRootOverride
    *   If defined, override the schema `tree_root` class with the one provided
    * @return
    *   Generated Table Schema (Table Descriptor)
    */
  def serialize(treeRootOverride: Option[String] = None): String = {
    summon[Codec[TableDescriptor]](generate(treeRootOverride)).deepDropNullValues.spaces2
  }
}
