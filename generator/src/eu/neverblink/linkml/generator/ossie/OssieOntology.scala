package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.runtime.{named, serializeDefault}
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.Node

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
  * @param roles
  *   The roles after the first. Empty for a unary relationship.
  * @param verbalizes
  *   How to say a link out loud, e.g. `{Person} earns {Salary}`. Ossie requires at least one.
  */
final case class Relationship(
    name: String,
    description: Option[String] = None,
    roles: Seq[Role] = Nil,
    multiplicity: Option[Multiplicity] = None,
    requires: Seq[String] = Nil,
    verbalizes: Seq[String],
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
    @named("type")
    conceptType: ConceptType,
    description: Option[String] = None,
    @named("extends")
    extendsConcepts: Seq[String] = Nil,
    @named("identify_by")
    identifyBy: Seq[String] = Nil,
    requires: Seq[String] = Nil,
    relationships: Seq[Relationship] = Nil,
)

/** A whole Ossie ontology document.
  *
  * @param version
  *   The spec pins this to one value, so it defaults to it and nothing sets it. `@serializeDefault`
  *   because it has to be written even though it never differs from the default.
  * @param aiContext
  *   Passed through as a node rather than a string, because Ossie accepts either a string or an
  *   object.
  */
final case class OssieOntology(
    @serializeDefault
    version: String = OssieOntology.specVersion,
    name: String,
    description: Option[String] = None,
    @named("ai_context")
    aiContext: Option[Node] = None,
    ontology: Seq[Concept],
)

object OssieOntology {
  val specVersion = "0.2.0.dev0"

  private given nodeCodec: LinkmlYamlCodec[Node] = new LinkmlYamlCodec[Node] {
    override def decode(node: Node, id: Option[Any]): Node = node

    override def encode(x: Node, skipId: Boolean): Node = x
  }

  given codec: LinkmlYamlCodec[OssieOntology] = LinkmlYamlCodec.derived
}
