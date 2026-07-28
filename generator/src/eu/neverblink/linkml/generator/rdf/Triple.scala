package eu.neverblink.linkml.generator.rdf

/** An RDF term. Serialize with [[NTriplesWriter]]. */
sealed trait Node

sealed trait Resource extends Node

final case class Iri(value: String) extends Resource

final case class BlankNode(id: String) extends Resource

final case class Literal(value: String, datatype: Iri = XmlSchema.string) extends Node

object Literal {
  val one: Literal = Literal("1", XmlSchema.integer)
}

final case class Triple(subj: Resource, pred: Iri, obj: Node)

final case class Namespace(prefix: String, name: String)
