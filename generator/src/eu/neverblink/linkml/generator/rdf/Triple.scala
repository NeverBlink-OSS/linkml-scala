package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.runtime.LanguageTag

/** An RDF term. Serialize with [[NTriplesWriter]] or [[TurtleWriter]]. */
sealed trait Node

sealed trait Resource extends Node

final case class Iri(value: String) extends Resource

/** A blank node, either standalone ([[BlankNode]]) or inlined into its one reference
  * ([[InlineBlankNode]]). The two differ only in how Turtle writes them. N-Triples writes both as
  * `_:id`.
  */
sealed trait AnyBlankNode extends Resource {
  def id: String
}

final case class BlankNode(id: String) extends AnyBlankNode

/** A blank node that is referenced exactly once, as an object, and whose own triples are pushed
  * into the sink immediately after that reference. [[TurtleWriter]] writes it as `[ ... ]`.
  */
final case class InlineBlankNode(id: String) extends AnyBlankNode

/** @param value
  *   The lexical value of the literal
  * @param datatype
  *   The datatype of the literal
  */
final case class Literal(
    value: String,
    datatype: Iri = XmlSchema.string,
) extends Node

final case class LanguageLiteral(
    value: String,
    language: LanguageTag,
) extends Node:
  def datatype: Iri = Rdf.langString

object Literal {
  val one: Literal = Literal("1", XmlSchema.integer)
}

final case class Triple(subj: Resource, pred: Iri, obj: Node)

final case class Namespace(prefix: String, name: String)
