package eu.neverblink.linkml.generator.ossie

import org.virtuslab.yaml.{Node, StringNode}

import scala.collection.immutable.ListMap

object BuiltInConcept {
  val any = "Any"
  val boolean = "Boolean"
  val date = "Date"
  val dateTime = "DateTime"
  val decimal = "Decimal"
  val float = "Float"
  val integer = "Integer"
  val string = "String"

  val all: Set[String] = Set(any, boolean, date, dateTime, decimal, float, integer, string)
  val valueTypes: Set[String] = all - any
}

enum ConceptType:
  case EntityType, ValueType

enum Multiplicity:
  case ManyToOne, OneToOne

final case class Role(concept: String, name: Option[String] = None) {

  /** How an expression refers to this role: its name, or the player's name when it has none. */
  def ref: String = name.getOrElse(concept)
}

/** A relationship, declared under the concept that plays its first role.
  *
  * @param name
  *   Unique only within the containing concept - the full identifier is `Concept.name`.
  * @param verbalizes
  *   How to say a link out loud, e.g. `{Person} earns {Salary}`. Ossie requires at least one.
  * @param roles
  *   The roles after the first. Empty for a unary relationship.
  */
final case class Relationship(
    name: String,
    verbalizes: Seq[String],
    description: Option[String] = None,
    roles: Seq[Role] = Nil,
    multiplicity: Option[Multiplicity] = None,
    requires: Seq[String] = Nil,
) {

  /** What this relationship says, ignoring how it is worded.
    *
    * Two relationships with the same shape say the same thing, which is how an inherited
    * relationship is recognized.
    */
  private[ossie] def shape: Relationship = copy(description = None, verbalizes = Nil)
}

/** One component of an ontology: a concept plus the relationships that play their first role in it.
  *
  * @param extendsConcepts
  *   Supertypes.
  * @param identifyBy
  *   Names of the relationships forming this concept's preferred identifier.
  */
final case class Concept(
    concept: String,
    conceptType: ConceptType,
    description: Option[String] = None,
    extendsConcepts: Seq[String] = Nil,
    identifyBy: Seq[String] = Nil,
    requires: Seq[String] = Nil,
    relationships: Seq[Relationship] = Nil,
)

/** A whole Ossie ontology document.
  *
  * `version` is not a field: the spec pins it to a single value, which [[OssieOntology.encode]]
  * writes out.
  *
  * @param aiContext
  *   Passed through as a node rather than a string, because Ossie accepts either a string or an
  *   object.
  */
final case class OssieOntology(
    name: String,
    description: Option[String] = None,
    aiContext: Option[Node] = None,
    ontology: Seq[Concept] = Nil,
)

object OssieOntology {
  val specVersion = "0.2.0.dev0"

  /** Encode the ontology as a scala-yaml [[Node]], which then goes out as either YAML or JSON.
    *
    * Absent and empty fields are left out entirely rather than written as nulls or empty lists,
    * because the Ossie schema sets `additionalProperties: false` and types every optional field.
    * Key order follows the spec's own ordering rather than being alphabetical.
    */
  def encode(ontology: OssieOntology): Node = mapping(
    field("version", specVersion),
    field("name", ontology.name),
    optional("description", ontology.description),
    ontology.aiContext.map(node => StringNode("ai_context") -> node),
    always("ontology", ontology.ontology.map(concept)),
  )

  private def concept(c: Concept): Node = mapping(
    field("concept", c.concept),
    field("type", c.conceptType.toString),
    optional("description", c.description),
    strings("extends", c.extendsConcepts),
    strings("identify_by", c.identifyBy),
    strings("requires", c.requires),
    nodes("relationships", c.relationships.map(relationship)),
  )

  private def relationship(r: Relationship): Node = mapping(
    field("name", r.name),
    optional("description", r.description),
    nodes("roles", r.roles.map(role)),
    optional("multiplicity", r.multiplicity.map(_.toString)),
    strings("requires", r.requires),
    always("verbalizes", r.verbalizes.map(StringNode(_))),
  )

  private def role(r: Role): Node = mapping(
    field("concept", r.concept),
    optional("name", r.name),
  )

  /** One entry of a mapping node. `None` means the field is left out entirely. */
  private type Field = Option[(Node, Node)]

  private def field(key: String, value: String): Field =
    Some(StringNode(key) -> StringNode(value))

  /** A field that is written only when the value is there. */
  private def optional(key: String, value: Option[String]): Field =
    value.map(v => StringNode(key) -> StringNode(v))

  /** A sequence field the spec requires, written even when empty.
    *
    * An empty one should be impossible, and writing it anyway means the schema check reports it as
    * a `minItems` violation instead of the field silently going missing.
    */
  private def always(key: String, values: Seq[Node]): Field =
    Some(StringNode(key) -> Node.SequenceNode(values*))

  /** A sequence field that is written only when it has entries, since the Ossie schema types every
    * optional field and rejects anything it does not declare.
    */
  private def nodes(key: String, values: Seq[Node]): Field =
    if values.isEmpty then None else always(key, values)

  private def strings(key: String, values: Seq[String]): Field =
    nodes(key, values.map(StringNode(_)))

  /** A mapping node that keeps the given key order, dropping the absent fields.
    *
    * Goes through the `Map` overload rather than the varargs one on purpose: varargs calls `toMap`,
    * which reorders anything past four entries.
    */
  private def mapping(fields: Field*): Node =
    Node.MappingNode(ListMap.from(fields.flatten))
}
