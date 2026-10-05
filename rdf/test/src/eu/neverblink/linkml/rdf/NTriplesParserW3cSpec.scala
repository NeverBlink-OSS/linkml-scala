package eu.neverblink.linkml.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Conformance tests for [[NTriplesParser]] over the W3C test files. Cross-platform. The JVM-only
  * [[NTriplesParserRdf4jSpec]] also checks what the parser reads against RDF4J.
  */
class NTriplesParserW3cSpec extends AnyWordSpec, Matchers {

  private def parse(document: String): Seq[Triple] = {
    val sink = new CollectingRdfSink
    NTriplesParser.parse(document, sink)
    sink.triples
  }

  "NTriplesParser, on the W3C N-Triples syntax tests," should {
    for (name, content) <- W3cTestFiles.nTriplesSyntax do
      if name.contains("-bad-") then
        s"reject '$name'" in {
          intercept[RdfParseException](parse(content))
        }
      else
        s"accept '$name'" in {
          noException should be thrownBy parse(content)
        }
  }

  "NTriplesParser, on the W3C Turtle test results," should {
    for (name, content) <- W3cTestFiles.turtleResults do
      s"read back what NTriplesWriter writes for '$name'" in {
        val triples = parse(content)
        parse(
          RdfUtils.toNTriples(sink => triples.foreach(t => sink.triple(t.subj, t.pred, t.obj))),
        ) shouldBe
          triples
      }
  }
}
