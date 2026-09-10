package eu.neverblink.linkml.generator.ossie

import eu.neverblink.linkml.generator.ossie.OssieCases.Case
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}

/** Utilities for working with Ossie ontologies. */
trait OssieFixtures {

  /** A SchemaView over a minimal schema with [[body]] spliced in. */
  def schemaOf(body: String): SchemaView = {
    val schema =
      s"""id: https://example.org/spec
         |name: spec
         |prefixes:
         |  linkml: https://w3id.org/linkml/
         |  xsd: http://www.w3.org/2001/XMLSchema#
         |  ex: https://example.org/
         |default_prefix: ex
         |default_range: string
         |imports:
         |  - linkml:types
         |${body.stripMargin}
         |""".stripMargin
    SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(schema))
  }

  def schemaOf(c: Case): SchemaView = schemaOf(c.body)

  def ontologyOf(body: String): OssieOntology = OssieGenerator(using schemaOf(body)).generate()

  def ontologyOf(c: Case): OssieOntology = ontologyOf(c.body)

  def yamlOf(body: String): String = OssieGenerator(using schemaOf(body)).serialize()

  def yamlOf(c: Case): String = yamlOf(c.body)

  def concept(o: OssieOntology, name: String): Concept =
    o.ontology.find(_.concept == name).getOrElse(
      throw AssertionError(s"no concept '$name' in ${o.ontology.map(_.concept)}"),
    )

  def relationship(o: OssieOntology, conceptName: String, name: String): Relationship = {
    val c = concept(o, conceptName)
    c.relationships.find(_.name == name).getOrElse(
      throw AssertionError(s"no relationship '$name' on '$conceptName' in ${names(o, conceptName)}"),
    )
  }

  /** The single role of a relationship, which is the only shape the generator emits. */
  def role(o: OssieOntology, conceptName: String, name: String): Role =
    relationship(o, conceptName, name).roles match {
      case Seq(only) => only
      case other => throw AssertionError(s"expected exactly one role, got $other")
    }

  def names(o: OssieOntology, conceptName: String): Seq[String] =
    concept(o, conceptName).relationships.map(_.name)
}
