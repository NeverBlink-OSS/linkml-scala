package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.runtime.LanguageTag

/** An RDF term. Serialize with [[NTriplesWriter]]. */
sealed trait Node

sealed trait Resource extends Node

final case class Iri(value: String) extends Resource

final case class BlankNode(id: String) extends Resource

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
