package eu.neverblink.linkml.generator.graphql

import eu.neverblink.linkml.generator.util.PruningMode.schemaRoot
import eu.neverblink.linkml.generator.util.{Printable, PruningMode, indent}
import eu.neverblink.linkml.schemaview.*

class GraphQlGenerator(using sv: SchemaView) {
  val commonDirectives: String =
    """# Common LinkML directives
      |
      |"Specify the URI of this element"
      |directive @linkml_uri(
      |  uri: String!
      |) on OBJECT | INTERFACE | SCALAR | ENUM | ENUM_VALUE | FIELD_DEFINITION
      |
      |"Specify that this field is an identifier"
      |directive @linkml_identifier on FIELD_DEFINITION
      |
      |""".stripMargin.strip()

  /** Generate the GraphQL definitions which use custom directives for rdf interop.
    *
    * @param pruningMode
    *   How to prune the generated definitions, schemaRoot by default (elements reachable from any
    *   root schema defined elements) to omit unnecessary linkml:types scalar definitions.
    */
  def generate(pruningMode: PruningMode = schemaRoot): Iterable[GraphQlDefinition] = {
    val query = pruningMode.derivedQuery(false, true)

    val reachableClasses = sv.classes.values
      .filter(query.reachable)

    val classDefs: Iterable[GraphQlDefinition] = reachableClasses
      .flatMap(generateClass)

    val anyDef = if reachableClasses.exists(_.isAny) then
      Some(
        GraphQlScalarDefinition(
          "Any",
          "https://w3id.org/linkml/Any",
          None,
        ),
      )
    else None

    val enumDefs: Iterable[GraphQlDefinition] = sv.enums.values
      .filter(query.reachable)
      .map(ev => {
        val pvs = ev.derivedValues.map(value =>
          GraphQlEnumValueDefinition(
            value.pv.text,
            value.meaning.uri(using ev.definingPrefixResolver),
            value.pv.description,
          ),
        )
        GraphQlEnumDefinition(
          ev.aliasedName,
          ev.uriStr,
          pvs,
          ev._enum.description,
        )
      })

    val typeDefs: Iterable[GraphQlDefinition] = sv.types.values
      .filter(query.reachable)
      .flatMap(tv => {
        if remapToBuiltin(tv).isDefined
        then None // if already using the builtin, no need to define scalars
        else Some(GraphQlScalarDefinition(tv.aliasedName, tv.uriStr, tv.inner.description))
      })

    classDefs ++ anyDef ++ enumDefs ++ typeDefs
  }

  /** Generate a GraphQL definition corresponding to the provided class, or None if the class is
    * linkml:Any
    */
  private def generateClass(cls: ClassView): Option[GraphQlDefinition] = {
    lazy val fields = cls.attributeViews.values.map(av => {
      val slotView = av.slotView
      val range = av match {
        case AnyView(_) =>
          "Any"
        case classAttributeView: ClassAttributeView =>
          classAttributeView.classView.aliasedName
        case TypeAttributeView(_, typeView) =>
          remappedType(typeView)
        case EnumAttributeView(_, enumView) => enumView.aliasedName
      }
      GraphQlField(
        slotView.aliasedName,
        slotView.uriStr,
        range,
        slotView.slot.required,
        slotView.slot.multivalued,
        slotView.slot.identifier,
        slotView.slot.description,
      )
    })
    if cls.isAny then None
    else if cls.cls.`abstract` || cls.cls.mixin then
      Some(
        GraphQlInterfaceDefinition(
          cls.aliasedName,
          cls.uriStr,
          fields,
          cls.parents
            .filter(cv => cv.cls.`abstract` || cv.cls.mixin)
            .map(_.aliasedName).toSeq,
          cls.cls.description,
        ),
      )
    else
      Some(
        GraphQlTypeDefinition(
          cls.aliasedName,
          cls.uriStr,
          fields,
          cls.parents
            // Break the inheritance chain on concrete -> concrete inheritance
            // We still use the derived slots and interfaces and types
            // have to "duplicate" fields in GraphQL anyway
            .filter(cv => cv.cls.`abstract` || cv.cls.mixin)
            .map(_.aliasedName).toSeq,
          cls.cls.description,
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
            |# Generated definitions
            |${generate(pruningMode).map(_.print.strip()).mkString("\n\n")}
            |
            |$commonDirectives
            |""".stripMargin
  }

  /** Remap a runtime type to a GraphQL built-in scalar, if possible.
    */
  private def remapToBuiltin(tv: TypeView): Option[String] =
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
  private def remappedType(tv: TypeView): String =
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
  * @param name
  *   Aliased name to use
  * @param uri
  *   URI to use in the "rdf_iri" directive
  * @param fields
  *   Field definitions to include
  * @param inherits
  *   Aliased names to use for inheritance
  * @param description
  *   Description of the interface
  */
case class GraphQlInterfaceDefinition(
    name: String,
    uri: String,
    fields: Iterable[GraphQlField],
    inherits: Seq[String],
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String =
    val inheritsList =
      if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ")
    val body = {
      if fields.isEmpty
      then """{
             |  _emptyClass: String
             |}
             |""".stripMargin
      else indent"""{
                   |  ${fields.map(_.print.strip()).mkString("\n")}
                   |}
                   |""".stripMargin
    }
    indent"""${wrapDescription(description)}
            |interface $name $inheritsList @linkml_uri(uri: "$uri") $body
            |""".stripMargin

/** Container for information needed to generate an object ("type") definition
  *
  * @param name
  *   Aliased name to use
  * @param uri
  *   URI to use in the "rdf_iri" directive
  * @param fields
  *   Field definitions to include
  * @param inherits
  *   Aliased names to use for inheritance
  * @param description
  *   Description of the object
  */
case class GraphQlTypeDefinition(
    name: String,
    uri: String,
    fields: Iterable[GraphQlField],
    inherits: Seq[String],
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String = {
    val inheritsList =
      if inherits.isEmpty then "" else "implements " + inherits.mkString(" & ")
    val body = {
      if fields.isEmpty
      then """{
             |  _emptyClass: String
             |}
             |""".stripMargin
      else indent"""{
                   |  ${fields.map(_.print.strip()).mkString("\n")}
                   |}
                   |""".stripMargin
    }
    indent"""${wrapDescription(description)}
            |type $name $inheritsList @linkml_uri(uri: "$uri") $body
            |""".stripMargin
  }

/** Container for information needed to generate an enum definition
  *
  * @param name
  *   Aliased name to use
  * @param uri
  *   URI to use in the "rdf_iri" directive
  * @param values
  *   Enum's permissible values
  * @param description
  *   Description of the enum
  */
case class GraphQlEnumDefinition(
    name: String,
    uri: String,
    values: Iterable[GraphQlEnumValueDefinition],
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String = {
    val serializedValues = values.map(_.print.strip())
    indent"""${wrapDescription(description)}
            |enum $name @linkml_uri(uri: "$uri") {
            |  ${serializedValues.mkString("\n")}
            |}
            |""".stripMargin
  }

/** Container for information needed to generate an enum value definition
  *
  * @param text
  *   Name to use
  * @param uri
  *   URI to use in the "rdf_iri" directive
  * @param description
  *   Description of the enum's value
  */
case class GraphQlEnumValueDefinition(
    text: String,
    uri: String,
    description: Option[String],
) extends GraphQlElement:
  override def print: String =
    indent"""${wrapDescription(description)}
            |$text @linkml_uri(uri: "$uri")
            |""".stripMargin

/** Container for information needed to generate a scalar definition
  *
  * @param name
  *   Aliased name to use
  * @param uri
  *   URI to use in the "rdf_iri" directive
  * @param description
  *   Description of the scalar
  */
case class GraphQlScalarDefinition(
    name: String,
    uri: String,
    description: Option[String],
) extends GraphQlDefinition:
  override def print: String = {
    indent"""${wrapDescription(description)}
            |scalar $name @linkml_uri(uri: "$uri")
            |""".stripMargin
  }

/** Container for information needed to generate a graphql field definition
  *
  * @param name
  *   Aliased name to use
  * @param uri
  *   URI to use in the "rdf_iri" directive
  * @param range
  *   Aliased name to use in the range of the field
  * @param nonNull
  *   Whether the [[range]] should be declared non-null ("Range!")
  * @param multivalued
  *   Whether the [[range]] should be declared an array ("[Range]")
  * @param identifier
  *   Whether the field should include the @linkml_identifier directive
  * @param description
  *   Description of the field
  */
case class GraphQlField(
    name: String,
    uri: String,
    range: String,
    nonNull: Boolean,
    multivalued: Boolean,
    identifier: Boolean,
    description: Option[String],
) extends GraphQlElement:
  def print: String = {
    val typeExpr =
      // output-strict interface: no nulls in collection
      if multivalued && nonNull then s"[$range!]!"
      else if multivalued then s"[$range!]"
      else if nonNull then range + "!"
      // nullability: field always present if requested, need to use null on output
      else range
    val directive = if identifier then "@linkml_identifier @linkml_uri(uri: \"$uri\")"
    else s"@linkml_uri(uri: \"$uri\")"
    indent"""${wrapDescription(description)}
            |$name: $typeExpr $directive
            |""".stripMargin
  }
