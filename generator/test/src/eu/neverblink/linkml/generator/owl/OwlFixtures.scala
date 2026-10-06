package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.rdf.RdfFormat
import eu.neverblink.linkml.rdf.{CollectingRdfSink, NTriplesParser, Triple}
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}

trait OwlFixtures {

  val ns = "https://example.org/test/"

  /** Wraps `body` in a schema whose default prefix is `ex`. */
  def schemaOf(body: String): SchemaView =
    SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        s"""id: https://example.org/test
           |name: test
           |prefixes:
           |  ex: $ns
           |  linkml: https://w3id.org/linkml/
           |default_prefix: ex
           |default_range: string
           |imports:
           |  - linkml:types
           |${body.stripMargin.trim}
           |""".stripMargin,
      ),
    )

  def ontologyOf(body: String, options: OwlGenerator.Options = OwlGenerator.Options()): Ontology =
    OwlGenerator(using schemaOf(body)).ontology(options)

  def turtleOf(body: String, options: OwlGenerator.Options = OwlGenerator.Options()): String =
    OwlGenerator(using schemaOf(body)).serialize(options)

  def triplesOf(
      sv: SchemaView,
      options: OwlGenerator.Options = OwlGenerator.Options(),
  ): Seq[Triple] = {
    val sink = CollectingRdfSink()
    NTriplesParser.parse(
      OwlGenerator(using sv).serialize(options.copy(format = RdfFormat.nt)),
      sink,
    )
    sink.triples
  }
}
