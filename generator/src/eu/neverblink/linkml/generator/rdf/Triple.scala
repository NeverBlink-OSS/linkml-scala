package eu.neverblink.linkml.generator.rdf

sealed trait Node:
  /** @return
    *   NTriples format representation
    */
  def nt: String

sealed trait Resource extends Node

case class Iri(value: String) extends Resource:
  def nt: String = s"<$value>"

case class BlankNode(id: String) extends Resource:
  def nt: String = s"_:$id"

case class Literal(value: String, datatype: Iri = XmlSchema.string) extends Node:
  def nt: String = s"\"$value\"^^${datatype.nt}"

object Literal {
  val one: Literal = Literal("1", XmlSchema.integer)
}

case class Triple(subj: Resource, pred: Iri, obj: Node):
  /** @return
    *   NTriples format representation
    */
  def nt: String = s"${subj.nt} ${pred.nt} ${obj.nt} ."

case class Namespace(prefix: String, name: String)

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
