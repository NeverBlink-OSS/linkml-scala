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

object XmlSchema {
  val string: Iri = Iri("http://www.w3.org/2001/XMLSchema#string")
  val integer: Iri = Iri("http://www.w3.org/2001/XMLSchema#integer")
  val boolean: Iri = Iri("http://www.w3.org/2001/XMLSchema#boolean")
}

object Rdf {
  val Property: Iri = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#Property")
  val first: Iri = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#first")
  val nil: Iri = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#nil")
  val rest: Iri = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#rest")
  val `type`: Iri = Iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type")
}
