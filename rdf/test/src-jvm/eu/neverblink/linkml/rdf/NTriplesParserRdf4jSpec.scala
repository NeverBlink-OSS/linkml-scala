package eu.neverblink.linkml.rdf

import org.eclipse.rdf4j.model.impl.{LinkedHashModel, SimpleValueFactory}
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.{Model, Resource as Rdf4jResource, Value}
import org.eclipse.rdf4j.rio.{RDFFormat, RDFParseException, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.StringReader

/** Checks that [[NTriplesParser]] reads the same graph as RDF4J from every valid W3C test file. */
class NTriplesParserRdf4jSpec extends AnyWordSpec, Matchers {
  import NTriplesParserRdf4jSpec.*

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
        val sink = new CollectingRdfSink
        NTriplesParser.parse(content, sink)
        val actual = toModel(sink.triples)
        withClue(s"$actual\nis not isomorphic to\n$expected\n") {
          Models.isomorphic(actual, expected) shouldBe true
        }
      }
  }
}

object NTriplesParserRdf4jSpec {
  private val vf = SimpleValueFactory.getInstance()

  private def toModel(triples: Seq[Triple]): Model = {
    val model = new LinkedHashModel
    triples.foreach { t =>
      model.add(toResource(t.subj), vf.createIRI(t.pred.value), toValue(t.obj))
    }
    model
  }

  private def toResource(res: Resource): Rdf4jResource = res match {
    case Iri(value) => vf.createIRI(value)
    case b: AnyBlankNode => vf.createBNode(b.id)
  }

  private def toValue(node: Node): Value = node match {
    case r: Resource => toResource(r)
    case LanguageLiteral(value, lang) => vf.createLiteral(value, lang)
    case Literal(value, datatype) => vf.createLiteral(value, vf.createIRI(datatype.value))
  }
}
