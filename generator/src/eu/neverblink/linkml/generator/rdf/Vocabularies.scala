package eu.neverblink.linkml.generator.rdf

abstract class Vocabulary(val prefix: String) {
  def get(suffix: String): Iri = Iri(prefix + suffix)
  def apply(suffix: String): Iri = get(suffix)
}

object Shacl extends Vocabulary("http://www.w3.org/ns/shacl#") {
  val BlankNodeOrIRI: Iri = get("BlankNodeOrIRI")
  val IRI: Iri = get("IRI")
  val Literal: Iri = get("Literal")
  val NodeShape: Iri = get("NodeShape")
  val PropertyShape: Iri = get("PropertyShape")
  val `class`: Iri = get("class")
  val closed: Iri = get("closed")
  val datatype: Iri = get("datatype")
  val description: Iri = get("description")
  val ignoredProperties: Iri = get("ignoredProperties")
  val in: Iri = get("in")
  val maxCount: Iri = get("maxCount")
  val minCount: Iri = get("minCount")
  val nodeKind: Iri = get("nodeKind")
  val or: Iri = get("or")
  val order: Iri = get("order")
  val path: Iri = get("path")
  val property: Iri = get("property")
  val targetClass: Iri = get("targetClass")
}

object Rdfs extends Vocabulary("http://www.w3.org/2000/01/rdf-schema#") {
  val Class: Iri = get("Class")
  val Datatype: Iri = get("Datatype")
  val comment: Iri = get("comment")
  val domain: Iri = get("domain")
  val isDefinedBy: Iri = get("isDefinedBy")
  val label: Iri = get("label")
  val range: Iri = get("range")
  val seeAlso: Iri = get("seeAlso")
  val subClassOf: Iri = get("subClassOf")
  val subPropertyOf: Iri = get("subPropertyOf")
}

object Skos extends Vocabulary("\thttp://www.w3.org/2004/02/skos/core#") {
  val definition: Iri = get("definition")
}
