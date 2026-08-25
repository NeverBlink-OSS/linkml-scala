package eu.neverblink.linkml.generator.graphql

import eu.neverblink
import eu.neverblink.linkml
import eu.neverblink.linkml.generator.CharDocumentGenerator
import eu.neverblink.linkml.generator.util.PruningMode.schemaRoot
import eu.neverblink.linkml.generator.util.{CharSink, Printable, PruningMode, indent}
import eu.neverblink.linkml.metamodel.PermissibleValue
import eu.neverblink.linkml.runtime.{PrefixResolver, UriOrCurie}
import eu.neverblink.linkml.schemaview
import eu.neverblink.linkml.schemaview.*
import GraphQlGenerator.escaped

import scala.util.matching.Regex

class GraphQlGenerator(using sv: SchemaView)
    extends CharDocumentGenerator[GraphQlGenerator.Options] {

  /** Set of classes that are instantiable and have child classes. They need to have a split
    * interface/implementation, with the implementation only inheriting from the interface.
    */
  lazy val concreteInheritance: Map[String, ClassView] = {
    val builder = Map.newBuilder[String, ClassView]
    sv.classes.foreach { (_, child) =>
      child.parents.foreach { cls =>
        if !cls.cls.`abstract` && !cls.cls.mixin then builder.addOne((cls.name, cls))
      }
    }
    builder.result()
  }

  import GraphQlGenerator.*

  override protected def defaultOptions: Options = Options()

  /** Generate the GraphQL definitions which use custom directives for rdf interop.
    *
    * @param options
    *   What to generate. See [[GraphQlGenerator.Options]].
    */
  def generate(
      options: GraphQlGenerator.Options = GraphQlGenerator.Options(),
  ): Iterable[GraphQlDefinition] = {
    val query = options.pruningMode.derivedQuery(false, true)

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
  def generateClass(cls: ClassView): Iterable[GraphQlDefinition] = {
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
    if cls.isAny then Seq.empty
    else if cls.cls.`abstract` || cls.cls.mixin then
      Seq(
        GraphQlInterfaceDefinition(
          cls,
          fields,
          cls.parents.map(getInterfaceName),
        ),
      )
    else if concreteInheritance.contains(cls.name) then
      Seq(
        GraphQlInterfaceDefinition(
          cls,
          fields,
          cls.parents.map(getInterfaceName),
          Some(splitInterfaceName(cls)),
        ),
        GraphQlTypeDefinition(
          cls,
          fields,
          Seq(splitInterfaceName(cls)),
        ),
      )
    else
      Seq(
        GraphQlTypeDefinition(
          cls,
          fields,
          cls.parents.map(getInterfaceName),
        ),
      )
  }

  /** Get the interface name for a class, for inheritance. Handles classes split into
    * interface/implementation.
    */
  def getInterfaceName(cls: ClassView): String = {
    // Class is split, we need to refer to the interface instead
    if concreteInheritance.contains(cls.name) then splitInterfaceName(cls)
    // Class is interface-only, we can refer to it directly
    else escaped(cls.aliasedName)
  }

  /** Get the interface name of a split class. Assumes [[cls]] is a split class:
    * `concreteInheritance.contains(cls.name)` is true.
    */
  def splitInterfaceName(cls: ClassView): String =
    escaped(cls.aliasedName) + "Interface"

  /** Write the GraphQL definitions.
    */
  override protected def writeChars(sink: CharSink, options: GraphQlGenerator.Options): Unit = {
    sink.append("# GENERATED FROM LINKML\n\n")
    var first = true
    generate(options).foreach { definition =>
      if !first then sink.append("\n\n")
      sink.append(definition.print.strip())
      first = false
    }
    sink.append('\n')
  }
}

object GraphQlGenerator {

  /** Options for [[GraphQlGenerator]].
    *
    * @param pruningMode
    *   How to prune the generated definitions, schemaRoot by default (elements reachable from any
    *   root schema defined elements) to omit unnecessary linkml:types scalar definitions.
    */
  final case class Options(
      pruningMode: PruningMode = schemaRoot,
  )

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

  private val leadingUnderscores: Regex = "^_+".r

  /** Process the escapes and reduce the leading underscores to at most one to avoid clashes with
    * the GraphQL reserved names.
    */
  def escaped(text: String): String = {
    val res = Case.escaped(text)
    leadingUnderscores.replaceAllIn(res, "_")
  }
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
  * @param nameOverride
  *   Name of the interface, if it should be different (like when generating split classes)
  */
case class GraphQlInterfaceDefinition(
    classView: ClassView,
    fields: Iterable[GraphQlField],
    inherits: Seq[String],
    nameOverride: Option[String] = None,
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

  val name: String = nameOverride.getOrElse(escaped(classView.aliasedName))

  override def print: String =
    indent"""${wrapDescription(classView.cls.description)}
            |interface $name $inheritsList $body
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
            |type ${escaped(classView.aliasedName)} $inheritsList $body
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
            |enum ${escaped(enumView.aliasedName)} {
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
            |${escaped(pv.text)}
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
            |scalar ${escaped(typeView.aliasedName)}
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
  val name: String = escaped(slotView.aliasedName)

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
