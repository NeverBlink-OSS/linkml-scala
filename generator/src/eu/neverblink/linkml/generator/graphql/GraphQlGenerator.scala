package eu.neverblink.linkml.generator.graphql

import eu.neverblink
import eu.neverblink.linkml
import eu.neverblink.linkml.generator.util.PruningMode.schemaRoot
import eu.neverblink.linkml.generator.util.{Printable, PruningMode, indent}
import eu.neverblink.linkml.metamodel.PermissibleValue
import eu.neverblink.linkml.runtime.{PrefixResolver, UriOrCurie}
import eu.neverblink.linkml.schemaview
import eu.neverblink.linkml.schemaview.*

class GraphQlGenerator(using sv: SchemaView) {
  import GraphQlGenerator.*

  /** Generate the GraphQL definitions which use custom directives for rdf interop.
    *
    * @param pruningMode
    *   How to prune the generated definitions, schemaRoot by default (elements reachable from any
    *   root schema defined elements) to omit unnecessary linkml:types scalar definitions.
    */
  def generate(
      pruningMode: PruningMode = schemaRoot,
  ): Iterable[GraphQlDefinition] = {
    val query = pruningMode.derivedQuery(false, true)

    val reachableClasses = sv.classes.values
      .filter(query.reachable)

    val classDefs: Iterable[GraphQlDefinition] = reachableClasses
      .flatMap(generateClass)

    val anyDef = if reachableClasses.exists(_.isAny) then
      Some(
        GraphQlAnyScalarDefinition(),
      )
    else None

    val enumDefs: Iterable[GraphQlDefinition] = sv.enums.values
      .filter(query.reachable)
      .map(ev => {
        val pvs = ev.derivedValues.map(value =>
          GraphQlEnumValueDefinition(
            value.pv,
            value.meaning,
            ev.definingPrefixResolver,
          ),
        )
        GraphQlEnumDefinition(
          ev,
          pvs,
        )
      })

    val typeDefs: Iterable[GraphQlDefinition] = sv.types.values
      .filter(query.reachable)
      .flatMap(tv => {
        if remapToBuiltin(tv).isDefined
        then None // if already using the builtin, no need to define scalars
        else
          Some(
            GraphQlScalarDefinition(tv),
          )
      })

    classDefs ++ anyDef ++ enumDefs ++ typeDefs
  }

  /** Generate a GraphQL definition corresponding to the provided class, or None if the class is
    * linkml:Any
    */
  def generateClass(cls: ClassView): Option[GraphQlDefinition] = {
    lazy val fields = cls.attributeViews.values.map(av => {
      val range: String = av match {
        case AnyView(_, _) =>
          "Any"
        case classAttributeView: ClassAttributeView =>
          classAttributeView.classView.aliasedName
        case tav: TypeAttributeView =>
          remappedType(tav.typeView)
        case EnumAttributeView(_, _, enumView) => enumView.aliasedName
      }
      GraphQlField(
        av,
        range,
      )
    })
    if cls.isAny then None
    else if cls.cls.`abstract` || cls.cls.mixin then
      Some(
        GraphQlInterfaceDefinition(
          cls,
          fields,
          cls.parents.collect {
            case cv if cv.cls.`abstract` || cv.cls.mixin => cv.aliasedName
          },
        ),
      )
    else
      Some(
        GraphQlTypeDefinition(
          cls,
          fields,
          // Break the inheritance chain on concrete -> concrete inheritance
          // We still need to use the derived slots and interfaces and types anyway
          cls.parents.collect {
            case cv if cv.cls.`abstract` || cv.cls.mixin => cv.aliasedName
          },
        ),
      )
  }

  /** Generate and serialize the GraphQL definitions, along with the linkml directives for rdf
    * interop.
    *
    * @param pruningMode
    *   How to prune the generated definitions, schemaRoot by default (elements reachable from any
    *   root schema defined elements) to omit unnecessary linkml:types scalar definitions.
    */
  def serialize(
      pruningMode: PruningMode = schemaRoot,
  ): String = {
    indent"""# GENERATED FROM LINKML
            |
            |${generate(pruningMode).map(_.print.strip()).mkString("\n\n")}
            |""".stripMargin
  }
}

object GraphQlGenerator {

  /** Remap a runtime type to a GraphQL built-in scalar, if possible.
    */
  def remapToBuiltin(tv: TypeView): Option[String] =
    tv.runtimeType match {
      case StringType => Some("String")
      case IntegerType => Some("Int")
      case FloatType => Some("Float")
      case DoubleType => Some("Float")
      case BooleanType => Some("Boolean")
      case NcNameType => Some("String")
      case _ => None
    }

  /** Builtin remapped type or the aliased name of the type
    */
  def remappedType(tv: TypeView): String =
    remapToBuiltin(tv).getOrElse(tv.aliasedName)
}

/** ADT for different kinds of GraphQL definitions (type/interface/enum/scalar) */
trait GraphQlElement extends Printable:
  /** Process an optional description into a proper graphql description
    */
  final def wrapDescription(in: Option[String]): String = {
    in.map("\"\"\"\n" + _ + "\n\"\"\"").getOrElse("")
  }

trait GraphQlDefinition extends GraphQlElement

/** Container for information needed to generate an interface definition
  *
  * @param classView
  *   ClassView this interface is being generated for
  * @param fields
  *   Field definitions to include
  * @param inherits
  *   Aliased names to use for inheritance
  */
case class GraphQlInterfaceDefinition(
    classView: ClassView,
    fields: Iterable[GraphQlField],
    inherits: Seq[String],
) extends GraphQlDefinition:
  val inheritsList: String =
    if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ")

  val body: String = {
    if fields.isEmpty
    then """{
        |  "Empty class stub"
        |  _: String
        |}
        |""".stripMargin
    else indent"""{
              |  ${fields.map(_.print.strip()).mkString("\n")}
              |}
              |""".stripMargin
  }

  override def print: String =
    indent"""${wrapDescription(classView.cls.description)}
            |interface ${classView.aliasedName} $inheritsList $body
            |""".stripMargin

/** Container for information needed to generate an object ("type") definition
  *
  * @param classView
  *   The ClassView this GraphQL object is generated for
  * @param fields
  *   Field definitions to include
  * @param inherits
  *   Aliased names to use for the `implements` part of the definition
  */
case class GraphQlTypeDefinition(
    classView: ClassView,
    fields: Iterable[GraphQlField],
    inherits: Seq[String],
) extends GraphQlDefinition:
  val inheritsList: String =
    if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ")

  val body: String = {
    if fields.isEmpty
    then """{
        |  "Empty class stub"
        |  _: String
        |}
        |""".stripMargin
    else indent"""{
              |  ${fields.map(_.print.strip()).mkString("\n")}
              |}
              |""".stripMargin
  }

  override def print: String = {
    indent"""${wrapDescription(classView.cls.description)}
            |type ${classView.aliasedName} $inheritsList $body
            |""".stripMargin
  }

/** Container for information needed to generate an enum definition
  *
  * @param enumView
  *   EnumView this definition is being generated for
  * @param values
  *   Enum's permissible values
  */
case class GraphQlEnumDefinition(
    enumView: EnumView,
    values: Iterable[GraphQlEnumValueDefinition],
) extends GraphQlDefinition:
  override def print: String = {
    val serializedValues = values.map(_.print.strip())
    indent"""${wrapDescription(enumView._enum.description)}
            |enum ${enumView.aliasedName} {
            |  ${serializedValues.mkString("\n")}
            |}
            |""".stripMargin
  }

/** Container for information needed to generate an enum value definition
  *
  * @param pv
  *   The metamodel permissible value this element is generated for
  * @param meaning
  *   The provided permissible value meaning, or a synthetic meaning
  * @param prefixResolver
  *   The prefix resolver to use when processing the meaning of the enum
  */
case class GraphQlEnumValueDefinition(
    pv: PermissibleValue,
    meaning: UriOrCurie,
    prefixResolver: PrefixResolver,
) extends GraphQlElement:
  override def print: String =
    indent"""${wrapDescription(pv.description)}
            |${pv.text}
            |""".stripMargin

/** Container for information needed to generate a scalar definition
  *
  * @param typeView
  *   TypeView of the linkml type this scalar definition is generated for
  */
case class GraphQlScalarDefinition(
    typeView: TypeView,
) extends GraphQlDefinition:
  override def print: String = {
    indent"""${wrapDescription(typeView._type.description)}
            |scalar ${typeView.aliasedName}
            |""".stripMargin
  }

/** Container for creating an "Any" scalar
  */
case class GraphQlAnyScalarDefinition() extends GraphQlDefinition:
  override def print: String = {
    indent""""Scalar definition for a linkml:Any class"
            |scalar Any
            |""".stripMargin
  }

/** Container for information needed to generate a graphql field definition
  */
case class GraphQlField(
    attributeView: AttributeView,
    range: String,
) extends GraphQlElement:
  val slotView: SlotView = attributeView.slotView

  /** Aliased name to use in the range of the field */
  val name: String = slotView.aliasedName

  /** Whether the [[range]] should be declared non-null ("Range!") */
  val nonNull: Boolean = slotView.slot.required

  /** Whether the [[range]] should be declared an array ("[Range]") */
  val multivalued: Boolean = slotView.slot.multivalued

  /** Description of the field */
  val description: Option[String] = slotView.slot.description

  /** Stringy expression to put in the type position of the GraphQL field definition */
  val typeExpr: String = {
    // If multivalued, then the array is required but may be empty.
    // Array values are nullable for partial errors only!
    // Nulls in array = Error happened
    if multivalued then s"[$range]!"
    else if nonNull then range + "!"
    // nullability: field always present if requested, need to use null on output
    else range
  }

  def print: String = {
    indent"""${wrapDescription(description)}
            |$name: $typeExpr
            |""".stripMargin
  }
