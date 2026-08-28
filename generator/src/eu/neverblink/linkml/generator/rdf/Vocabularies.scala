package eu.neverblink.linkml.generator.rdf

abstract class Vocabulary(val prefix: String) {
  def get(suffix: String): Iri = new Iri(prefix.concat(suffix))
}

object XmlSchema extends Vocabulary("http://www.w3.org/2001/XMLSchema#") {
  val string: Iri = get("string")
  val integer: Iri = get("integer")
  val boolean: Iri = get("boolean")
  val decimal: Iri = get("decimal")
  val double: Iri = get("double")
  val float: Iri = get("float")
  val date: Iri = get("date")
  val dateTime: Iri = get("dateTime")
  val time: Iri = get("time")
}

object Rdf extends Vocabulary("http://www.w3.org/1999/02/22-rdf-syntax-ns#") {
  val Property: Iri = get("Property")
  val first: Iri = get("first")
  val nil: Iri = get("nil")
  val langString: Iri = get("langString")
  val rest: Iri = get("rest")
  val `type`: Iri = get("type")
}

object Shacl extends Vocabulary("http://www.w3.org/ns/shacl#") {
  val BlankNodeOrIRI: Iri = get("BlankNodeOrIRI")
  val IRI: Iri = get("IRI")
  val Literal: Iri = get("Literal")
  val NodeShape: Iri = get("NodeShape")
  val PropertyGroup: Iri = get("PropertyGroup")
  val PropertyShape: Iri = get("PropertyShape")
  val `class`: Iri = get("class")
  val closed: Iri = get("closed")
  val datatype: Iri = get("datatype")
  val description: Iri = get("description")
  val group: Iri = get("group")
  val ignoredProperties: Iri = get("ignoredProperties")
  val in: Iri = get("in")
  val maxCount: Iri = get("maxCount")
  val maxInclusive: Iri = get("maxInclusive")
  val minCount: Iri = get("minCount")
  val minInclusive: Iri = get("minInclusive")
  val name: Iri = get("name")
  val nodeKind: Iri = get("nodeKind")
  val or: Iri = get("or")
  val order: Iri = get("order")
  val path: Iri = get("path")
  val pattern: Iri = get("pattern")
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

object Skos extends Vocabulary("http://www.w3.org/2004/02/skos/core#") {
  val definition: Iri = get("definition")
}
