package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.runtime.LangTag

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
final case class Literal private (
    value: String,
    datatype: Iri,
    languageTag: Option[LangTag],
) extends Node

object Literal {
  val one: Literal = Literal("1", XmlSchema.integer)
  def apply(value: String): Literal =
    new Literal(value, XmlSchema.string, None)
  def apply(value: String, datatype: Iri): Literal =
    new Literal(value, datatype, None)
  def apply(value: String, languageTag: LangTag): Literal =
    new Literal(value, Rdf.langString, Some(languageTag))
}

final case class Triple(subj: Resource, pred: Iri, obj: Node)

final case class Namespace(prefix: String, name: String)
