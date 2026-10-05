package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.rdf.{Rdf4jModels, RdfUtils, Triple}
import eu.neverblink.linkml.tests.ModelCatalogue
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.rio.{RDFFormat, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.StringReader

/** Checks that [[eu.neverblink.linkml.rdf.NTriplesParser]] and
  * [[eu.neverblink.linkml.rdf.TurtleParser]] read the same graphs as RDF4J from everything in
  * [[CatalogueRdf]]: written as N-Triples, written as Turtle, and the instances' own Turtle files.
  * [[CatalogueRdfRoundTripSpec]] checks the round trips.
  */
class CatalogueRdf4jSpec extends AnyWordSpec, Matchers {
  import CatalogueRdf.InstanceBase

  private def check(document: String, format: RDFFormat, ours: String => Seq[Triple]) = {
    val expected = Rio.parse(StringReader(document), InstanceBase, format)
    val actual = Rdf4jModels.toModel(ours(document))
    withClue(s"${format.getName}:\n$document\n\n$actual\nis not isomorphic to\n$expected\n") {
      Models.isomorphic(actual, expected) shouldBe true
    }
  }

  "The RDF parsers" should {
    for entry <- ModelCatalogue.allOptIn do
      s"read the same graphs as RDF4J for model '${entry.name}'" when {
        for source <- CatalogueRdf.sources(entry) do
          source.name in {
            check(RdfUtils.toNTriples(source.write), RDFFormat.NTRIPLES, RdfUtils.parseNTriples)
            check(
              RdfUtils.toTurtle(source.write),
              RDFFormat.TURTLE,
              RdfUtils.parseTurtle(_, Some(InstanceBase)),
            )
            source.turtle.foreach(
              check(_, RDFFormat.TURTLE, RdfUtils.parseTurtle(_, Some(InstanceBase))),
            )
          }
      }
  }
}
