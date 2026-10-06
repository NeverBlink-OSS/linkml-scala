package eu.neverblink.linkml.rdf

import eu.neverblink.linkml.rdf.W3cTestFiles.TurtleTestType.*
import org.eclipse.rdf4j.model.Model
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.rio.{RDFFormat, RDFParseException, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.StringReader

/** Checks [[TurtleParser]] parsing the W3C Turtle tests, against the expected results of the
  * evaluation tests, and against what RDF4J reads from every valid test file.
  */
class TurtleParserRdf4jSpec extends AnyWordSpec, Matchers {

  private def check(actual: Seq[Triple], expected: Model) =
    withClue(s"${Rdf4jModels.toModel(actual)}\nis not isomorphic to\n$expected\n") {
      Models.isomorphic(Rdf4jModels.toModel(actual), expected) shouldBe true
    }

  "TurtleParser" should {
    for test <- W3cTestFiles.turtleTests if test.testType == Eval do
      s"read the expected graph from '${test.name}'" in {
        val expected =
          try Rio.parse(StringReader(test.result.get), "", RDFFormat.NTRIPLES)
          catch {
            case e: RDFParseException => cancel(s"RDF4J cannot read the result: ${e.getMessage}")
          }
        check(RdfUtils.parseTurtle(test.input, Some(test.baseIri)), expected)
      }

    for test <- W3cTestFiles.turtleTests if test.testType == Eval || test.testType == PositiveSyntax
    do
      s"read the same graph as RDF4J from '${test.name}'" in {
        val expected =
          try Rio.parse(StringReader(test.input), test.baseIri, RDFFormat.TURTLE)
          catch {
            case e: RDFParseException => cancel(s"RDF4J cannot read this file: ${e.getMessage}")
          }
        check(RdfUtils.parseTurtle(test.input, Some(test.baseIri)), expected)
      }
  }
}
