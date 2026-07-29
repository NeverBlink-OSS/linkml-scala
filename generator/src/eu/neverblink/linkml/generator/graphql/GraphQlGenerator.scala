package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.generator.util.PruningMode.schemaRoot
import eu.neverblink.linkml.generator.util.{Printable, PruningMode, indent}
import eu.neverblink.linkml.schemaview.*

// using sv
class GraphQlGenerator(using sv: SchemaView) {
  def generate(pruningMode: PruningMode = schemaRoot): Iterable[GraphQlDefinition] = {
    val query = pruningMode.derivedQuery(false, true)

    val classDefs: Iterable[GraphQlDefinition] = sv.classes.values
      .filter(query.reachable)
      .map(cls => {
        val fields = cls.attributeViews.values.map(av => {
          val slotView = av.slotView
          val range = av match {
            case AnyView(_) =>
              throw RuntimeException(
                "GraphQL generator does not support 'Any' ranges " +
                  s"(${cls.name}.${slotView.name} in schema ${cls.definingSchema.id})",
              )
            case classAttributeView: ClassAttributeView =>
              classAttributeView.classView.aliasedName
            case TypeAttributeView(_, typeView) =>
              remapToBuiltin(typeView).getOrElse(typeView.aliasedName)
            case EnumAttributeView(_, enumView) => enumView.aliasedName
          }
          GraphQlField(
            slotView.aliasedName,
            range,
            slotView.slot.required,
            slotView.slot.multivalued,
            slotView.slot.description,
          )
        })
        if cls.cls.`abstract` || cls.cls.mixin then
          GraphQlInterfaceDefinition(
            cls.aliasedName,
            fields,
            cls.parents.map(_.aliasedName).toSeq,
            cls.cls.description,
          )
        else
          GraphQlTypeDefinition(
            cls.aliasedName,
            fields,
            cls.parents.map(_.aliasedName).toSeq,
            cls.cls.description,
          )
      })

    val enumDefs: Iterable[GraphQlDefinition] = sv.enums.values
      .filter(query.reachable)
      .map(ev => {
        val pvs = ev.derivedValues.map(value => (value.pv.text, value.pv.description))
        GraphQlEnumDefinition(
          ev.aliasedName,
          pvs,
          ev._enum.description,
        )
      })

    val typeDefs: Iterable[GraphQlDefinition] = sv.types.values
      .filter(query.reachable)
      .flatMap(tv => {
        if remapToBuiltin(tv).isDefined
        then None // if already using the builtin, no need to define scalars
        else Some(GraphQlScalarDefinition(tv.aliasedName, tv.inner.description))
      })

    classDefs ++ enumDefs ++ typeDefs
  }

  def serialize(pruningMode: PruningMode = schemaRoot): String = {
    indent"""${generate(pruningMode).map(_.print.strip()).mkString("\n\n")}
            |""".stripMargin
  }

  private def remapToBuiltin(tv: TypeView): Option[String] =
    tv.runtimeType match {
      case StringType => Some("String")
      case IntegerType => Some("Int")
      case FloatType => Some("Float")
      case DoubleType => Some("Float")
      case BooleanType => Some("Boolean")
      case UriOrCurieType => Some("String")
      case UriType => Some("String")
      case CurieType => Some("String")
      case NcNameType => Some("String")
      case _ => None
    }
}

def wrapDescription(in: Option[String]): String = {
  in.map("\"\"\"\n" + _ + "\n\"\"\"").getOrElse("")
}

trait GraphQlDefinition extends Printable

case class GraphQlInterfaceDefinition(
    name: String,
    fields: Iterable[GraphQlField],
    inherits: Seq[String],
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String =
    val inheritsList =
      if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ")
    indent"""${wrapDescription(description)}
            |interface $name $inheritsList {
            |  ${fields.map(_.print.strip()).mkString("\n")}
            |}
            |""".stripMargin

case class GraphQlTypeDefinition(
    name: String,
    fields: Iterable[GraphQlField],
    inherits: Seq[String],
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String = {
    val inheritsList =
      if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ") + " "
    indent"""${wrapDescription(description)}
            |type $name $inheritsList{
            |  ${fields.map(_.print.strip()).mkString("\n")}
            |}
            |""".stripMargin
  }

case class GraphQlEnumDefinition(
    name: String,
    values: Iterable[(text: String, description: Option[String])],
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String = {
    val serializedValues = values.map(x => (wrapDescription(x.description) + "\n" + x.text).strip())
    indent"""${wrapDescription(description)}
            |enum $name {
            |  ${serializedValues.mkString("\n")}
            |}
            |""".stripMargin
  }

case class GraphQlScalarDefinition(
    name: String,
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String = {
    indent"""${wrapDescription(description)}
            |scalar $name
            |""".stripMargin
  }

case class GraphQlField(
    name: String,
    range: String,
    nonNull: Boolean,
    multivalued: Boolean,
    description: Option[String],
) extends Printable:
  def print: String = {
    // output-strict interface: no nulls in collection
    // nullability: field always present, need to use null to mean empty value
    // collections: no dicts in graphql?
    val typeExpr = if multivalued && nonNull then s"[$range!]!"
    else if multivalued then s"[$range!]"
    else if nonNull then range + "!"
    else range
    indent"""${wrapDescription(description)}
            |$name: $typeExpr
            |""".stripMargin
  }
