package eu.neverblink.linkml.generator.rdf

import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.{Statement, Value, IRI as Rdf4jIri}
import org.eclipse.rdf4j.rio.{RDFFormat, RDFParseException, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.StringReader
import scala.jdk.CollectionConverters.*

/** Shared harness for running a writer over the result files of the
  * [[https://www.w3.org/2013/TurtleTests/ W3C Turtle test suite]].
  *
  * We use RDF4J for parsing, because we don't implement parsers at all.
  */
abstract class W3cRoundTripSpec(format: RDFFormat) extends AnyWordSpec, Matchers {
  import W3cRoundTripSpec.*

  protected def makeTestCases(triples: Seq[Triple]): Seq[(String, String)]

  s"The ${format.getName} writer" should {
    for testFile <- testFiles do
      s"round-trip the W3C test result '${testFile.last}'" in {
        val expected =
          try Rio.parse(StringReader(os.read(testFile)), "", RDFFormat.NTRIPLES)
          catch {
            case e: RDFParseException =>
              cancel(s"RDF4J cannot read this test file: ${e.getMessage}")
          }

        for (label, document) <- makeTestCases(expected.asScala.map(toTriple).toSeq) do
          withClue(s"$label: ") {
            val actual =
              try Rio.parse(StringReader(document), "", format)
              catch {
                case e: Exception => fail(s"Could not parse the generated output:\n$document", e)
              }
            withClue(s"$document\nis not isomorphic to the expected graph: ") {
              Models.isomorphic(actual, expected) shouldBe true
            }
          }
      }
  }
}

object W3cRoundTripSpec {

  /** The vendored copy of the suite's result files. See the README next to them. */
  private def testFiles: Seq[os.Path] = {
    val dir = Option(getClass.getResource("/turtle-w3c"))
      .map(url => os.Path(java.nio.file.Paths.get(url.toURI)))
      .getOrElse(sys.error("The vendored W3C Turtle test files are not on the test classpath"))
    val files = os.list(dir).filter(_.ext == "nt").sorted
    if files.isEmpty then sys.error(s"No .nt files under $dir")
    files
  }

  private def toTriple(statement: Statement): Triple =
    Triple(
      toNode(statement.getSubject).asInstanceOf[Resource],
      Iri(statement.getPredicate.stringValue),
      toNode(statement.getObject),
    )

  private def toNode(value: Value): Node = value match {
    case iri: Rdf4jIri => Iri(iri.stringValue)
    case bnode: org.eclipse.rdf4j.model.BNode => BlankNode(bnode.getID)
    case literal: org.eclipse.rdf4j.model.Literal =>
      if literal.getLanguage.isPresent then
        LanguageLiteral(literal.getLabel, literal.getLanguage.get)
      else Literal(literal.getLabel, Iri(literal.getDatatype.stringValue))
    case other => sys.error(s"Unexpected RDF term: $other")
  }
}
