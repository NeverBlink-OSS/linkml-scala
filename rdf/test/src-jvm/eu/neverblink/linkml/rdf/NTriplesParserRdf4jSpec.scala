package eu.neverblink.linkml.rdf

import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.rio.{RDFFormat, RDFParseException, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.StringReader

/** Checks that [[NTriplesParser]] reads the same graph as RDF4J from every valid W3C test file. */
class NTriplesParserRdf4jSpec extends AnyWordSpec, Matchers {

  private val files =
    W3cTestFiles.nTriplesSyntax.filterNot(_._1.contains("-bad-")).map((n, c) =>
      (s"ntriples-w3c/$n", c),
    ) ++
      W3cTestFiles.turtleResults.map((n, c) => (s"turtle-w3c/$n", c))

  "NTriplesParser" should {
    for (name, content) <- files do
      s"read the same graph as RDF4J from '$name'" in {
        val expected =
          try Rio.parse(StringReader(content), "", RDFFormat.NTRIPLES)
          catch {
            case e: RDFParseException => cancel(s"RDF4J cannot read this file: ${e.getMessage}")
          }
        val actual = Rdf4jModels.toModel(RdfUtils.parseNTriples(content))
        withClue(s"$actual\nis not isomorphic to\n$expected\n") {
          Models.isomorphic(actual, expected) shouldBe true
        }
      }
  }
}
