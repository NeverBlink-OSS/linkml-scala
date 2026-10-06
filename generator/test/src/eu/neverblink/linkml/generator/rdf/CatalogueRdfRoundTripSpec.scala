package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Everything in [[CatalogueRdf]], written with both writers and read back with both parsers.
  * [[CatalogueRdf4jSpec]] checks the parsers against RDF4J on the same RDF.
  */
class CatalogueRdfRoundTripSpec extends AnyWordSpec, Matchers {
  import CatalogueRdfRoundTripSpec.*

  /** Write what `write` pushes in both formats, and read it back.
    *
    * N-Triples keeps every blank node label, so it must read back exactly what was pushed. Turtle
    * writes `[ ... ]` and `( ... )` without labels, so only the labels may differ there.
    */
  private def roundTrip(write: RdfSink => Unit): Unit = {
    val pushed = new CollectingRdfSink
    write(pushed)
    pushed.triples should not be empty

    val nTriples = RdfUtils.toNTriples(write)
    withClue(s"N-Triples:\n$nTriples\n") {
      RdfUtils.parseNTriples(nTriples) shouldBe pushed.triples.map(labeled)
    }
    val turtle = RdfUtils.toTurtle(write)
    withClue(s"Turtle:\n$turtle\n") {
      RdfUtils.sameUpToBlankNodes(RdfUtils.parseTurtle(turtle), pushed.triples) shouldBe true
    }
  }

  "The RDF writers and parsers" should {
    for entry <- ModelCatalogue.allOptIn do
      s"round-trip model '${entry.name}'" when {
        for source <- CatalogueRdf.sources(entry) do
          source.name in {
            roundTrip(source.write)
          }
      }
  }
}

object CatalogueRdfRoundTripSpec {

  /** What N-Triples reads back for `triple`: it has no inline blank nodes. */
  private def labeled(triple: Triple): Triple =
    Triple(labeled(triple.subj).asInstanceOf[Resource], triple.pred, labeled(triple.obj))

  private def labeled(node: Node): Node = node match {
    case b: InlineBlankNode => BlankNode(b.id)
    case other => other
  }
}
