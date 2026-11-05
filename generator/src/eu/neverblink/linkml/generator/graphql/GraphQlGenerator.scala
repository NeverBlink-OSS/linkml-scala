package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.generator.options.GraphQlGeneratorOptions
import eu.neverblink
import eu.neverblink.linkml
import eu.neverblink.linkml.generator.CharDocumentGenerator
import eu.neverblink.linkml.generator.util.*
import eu.neverblink.linkml.generator.util.Pruning.pruning
import eu.neverblink.linkml.generator.util.metadataLanguage
import eu.neverblink.linkml.rdf.io.CharSink
import eu.neverblink.linkml.metamodel.{CommonMetadata, PermissibleValue}
import eu.neverblink.linkml.schemaview
import eu.neverblink.linkml.schemaview.*

class GraphQlGenerator(using sv: SchemaView)
    extends CharDocumentGenerator[GraphQlGeneratorOptions],
      GraphQlRenamer {
  import GraphQlGenerator.*

  /** Whether the class is instantiable and has child classes. Such a class needs a split
    * interface/implementation, with the implementation only inheriting from the interface.
    */
  private def isSplit(cls: ClassView): Boolean = cls.isConcrete && cls.hasDescendants

  override protected def defaultOptions: GraphQlGeneratorOptions = GraphQlGeneratorOptions()

  /** Generate the GraphQL definitions which use custom directives for rdf interop.
    *
    * @param options
    *   What to generate. See [[GraphQlGeneratorOptions]].
    */
  def generate(
      options: GraphQlGeneratorOptions = GraphQlGeneratorOptions(),
  ): Iterable[GraphQlDefinition] = {
    given GraphQlGeneratorOptions = options

    val query = options.pruning.derivedQuery(false, true)

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
        val pvs = ev.derivedValues.map(value => GraphQlEnumValueDefinition(value.pv))
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

  /** The type of a field, without the list and non-null marks. */
  private def fieldRange(av: AttributeView): String =
    av match {
      case _: AnyView => "Any"
      case cav: ClassAttributeView => getInterfaceName(cav.classView)
      case tav: TypeAttributeView => remappedType(tav.typeView)
      case eav: EnumAttributeView => enumName(eav.enumView)
    }

  /** A class that implements an interface must have each of its fields, with the same name and the
    * same type or a subtype of it. A field is a subtype when it adds `!`, or when its type is an
    * object or interface that implements the interface's type. A list is not a subtype of a single
    * value.
    */
  private lazy val sharedFields = new SharedSlots((own, their) =>
    slotName(own.slotView) == slotName(their.slotView) &&
      own.slotView.slot.multivalued == their.slotView.slot.multivalued && (
        fieldRange(own) == fieldRange(their) || (own match {
          case _: ClassAttributeView =>
            their.isInstanceOf[ClassAttributeView] &&
            their.slotView.hasRangeDerivedFrom(own.slotView)
          case _ => false
        })
      ),
  )

  /** Generate a GraphQL definition corresponding to the provided class, or None if the class is
    * linkml:Any.
    *
    * The interface of a class leaves out the fields that a descendant changes in a way that does
    * not give a subtype, such as a field that a `slot_usage` makes multivalued. Each class still
    * has all its fields.
    */
  def generateClass(cls: ClassView)(using GraphQlGeneratorOptions): Iterable[GraphQlDefinition] = {
    lazy val fields =
      cls.attributeViews.values.map(av => GraphQlField(av, fieldRange(av)))
    lazy val interfaceFields =
      fields.filter(f => sharedFields.isShared(cls, f.attributeView.slotView.name))
    // A class must have the `_` field of each interface it implements that has only that field
    lazy val parentStub = cls.ancestorsWithSelf.iterator.drop(1).exists(hasStubInterface)
    if cls.isAny then Seq.empty
    else if !cls.isConcrete then
      Seq(
        GraphQlInterfaceDefinition(
          cls,
          interfaceFields,
          cls.parents.map(getInterfaceName),
          stub = parentStub,
        ),
      )
    else if isSplit(cls) then
      Seq(
        GraphQlInterfaceDefinition(
          cls,
          interfaceFields,
          cls.parents.map(getInterfaceName),
          Some(splitInterfaceName(cls)),
          parentStub,
        ),
        GraphQlTypeDefinition(
          cls,
          fields,
          Seq(splitInterfaceName(cls)),
          parentStub || hasStubInterface(cls),
        ),
      )
    else
      Seq(
        GraphQlTypeDefinition(
          cls,
          fields,
          cls.parents.map(getInterfaceName),
          parentStub,
        ),
      )
  }

  /** Whether the class has an interface without fields of its own. GraphQL requires an interface to
    * have a field, so it gets the stub field `_`, which its implementations must have too.
    */
  private def hasStubInterface(cls: ClassView): Boolean =
    !cls.isAny && (!cls.isConcrete || isSplit(cls)) &&
      cls.derivedAttributes.keysIterator.forall(name => !sharedFields.isShared(cls, name))

  /** Get the interface name for a class' interface, if it has an interface/implementation split.
    * Otherwise, gets the class' GraphQL name.
    */
  def getInterfaceName(cls: ClassView): String = {
    // Class is split, we need to refer to the interface instead
    if isSplit(cls) then splitInterfaceName(cls)
    // Class is interface-only, we can refer to it directly
    else className(cls)
  }

  /** Get the interface name of a split class. Assumes [[cls]] is a split class: `isSplit(cls)` is
    * true.
    */
  def splitInterfaceName(cls: ClassView): String =
    className(cls) + "Interface"

  /** Write the GraphQL definitions.
    */
  override protected def writeChars(sink: CharSink, options: GraphQlGeneratorOptions): Unit = {
    sink.append(GeneratedHeader("#"))
    sink.append('\n')
    var first = true
    generate(options).foreach { definition =>
      if !first then sink.append("\n\n")
      sink.append(definition.print.strip())
      first = false
    }
    sink.append('\n')
  }
}

object GraphQlGenerator extends GraphQlRenamer {

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
    remapToBuiltin(tv).getOrElse(typeName(tv))

}

/** ADT for different kinds of GraphQL definitions (type/interface/enum/scalar) */
trait GraphQlElement(using GraphQlGeneratorOptions) extends Printable:
  /** Process an optional description into a proper graphql description
    */
  final def wrapDescription(in: Option[String]): String = {
    in.map("\"\"\"\n" + _ + "\n\"\"\"").getOrElse("")
  }

  final def descriptionFor(commonMetadata: CommonMetadata): String =
    wrapDescription(commonMetadata.description.localized)

trait GraphQlDefinition extends GraphQlElement

object GraphQlDefinition:

  /** The body of a type or interface: its fields, with the stub field `_` first if there are none
    * or `stub` is set.
    */
  def body(fields: Iterable[GraphQlField], stub: Boolean)(using GraphQlGeneratorOptions): String =
    if (fields.isEmpty) """{
        |  "Empty class stub"
        |  _: String
        |}
        |""".stripMargin
    else if (stub) indent"""{
              |  "Empty class stub"
              |  _: String
              |  ${fields.map(_.print.strip()).mkString("\n")}
              |}
              |""".stripMargin
    else indent"""{
              |  ${fields.map(_.print.strip()).mkString("\n")}
              |}
              |""".stripMargin

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
    stub: Boolean = false,
)(using GraphQlGeneratorOptions)
    extends GraphQlDefinition:
  val inheritsList: String =
    if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ")

  val body: String = GraphQlDefinition.body(fields, stub)

  val name: String = nameOverride.getOrElse(GraphQlRenamer.className(classView))

  override def print: String =
    indent"""${descriptionFor(classView.cls)}
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
    stub: Boolean = false,
)(using GraphQlGeneratorOptions)
    extends GraphQlDefinition:
  val inheritsList: String =
    if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ")

  val body: String = GraphQlDefinition.body(fields, stub)

  val name: String = GraphQlRenamer.className(classView)

  override def print: String = {
    indent"""${descriptionFor(classView.cls)}
            |type $name $inheritsList $body
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
)(using GraphQlGeneratorOptions)
    extends GraphQlDefinition:
  val name: String = GraphQlRenamer.enumName(enumView)
  override def print: String = {
    val serializedValues =
      if values.isEmpty then Seq(GraphQlEnumDefinition.emptyEnumStub)
      else values.map(_.print.strip())
    indent"""${descriptionFor(enumView._enum)}
            |enum $name {
            |  ${serializedValues.mkString("\n")}
            |}
            |""".stripMargin
  }

object GraphQlEnumDefinition:

  /** The only value of an enum without permissible values. GraphQL does not allow empty enums. */
  val emptyEnumStub: String =
    """"Empty enum stub"
      |_""".stripMargin

/** Container for information needed to generate an enum value definition
  *
  * @param pv
  *   The metamodel permissible value this element is generated for
  */
case class GraphQlEnumValueDefinition(pv: PermissibleValue)(using opt: GraphQlGeneratorOptions)
    extends GraphQlElement:
  val name: String = GraphQlRenamer.permissibleValueName(pv)
  override def print: String =
    indent"""${descriptionFor(pv)}
            |$name
            |""".stripMargin

/** Container for information needed to generate a scalar definition
  *
  * @param typeView
  *   TypeView of the linkml type this scalar definition is generated for
  */
case class GraphQlScalarDefinition(
    typeView: TypeView,
)(using opt: GraphQlGeneratorOptions)
    extends GraphQlDefinition:
  val name: String = GraphQlRenamer.typeName(typeView)
  override def print: String = {
    indent"""${descriptionFor(typeView._type)}
            |scalar $name
            |""".stripMargin
  }

/** Container for creating an "Any" scalar
  */
case class GraphQlAnyScalarDefinition()(using GraphQlGeneratorOptions) extends GraphQlDefinition:
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
)(using GraphQlGeneratorOptions)
    extends GraphQlElement:
  val slotView: SlotView = attributeView.slotView

  /** Aliased name to use in the range of the field */
  val name: String = GraphQlRenamer.slotName(attributeView.slotView)

  /** Whether the [[range]] should be declared non-null ("Range!") */
  val nonNull: Boolean = slotView.slot.required

  /** Whether the [[range]] should be declared an array ("[Range]") */
  val multivalued: Boolean = slotView.slot.multivalued

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
    indent"""${descriptionFor(attributeView.slotView.slot)}
            |$name: $typeExpr
            |""".stripMargin
  }
