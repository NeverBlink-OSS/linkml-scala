package eu.neverblink.linkml.rdf

import eu.neverblink.linkml.rdf.W3cTestFiles.TurtleTestType.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Conformance tests for [[TurtleParser]] over the W3C test files. Cross-platform. The JVM-only
  * [[TurtleParserRdf4jSpec]] also checks the graphs it reads.
  */
class TurtleParserW3cSpec extends AnyWordSpec, Matchers {
  import TurtleParserW3cSpec.*

  private def parse(test: W3cTestFiles.TurtleTest): Seq[Triple] =
    RdfUtils.parseTurtle(test.input, Some(test.baseIri))

  "TurtleParser, on the W3C Turtle test suite," should {
    "find every test in the manifest" in {
      W3cTestFiles.turtleTests.groupMapReduce(_.testType)(_ => 1)(_ + _) shouldBe
        Map(Eval -> 132, PositiveSyntax -> 77, NegativeSyntax -> 78, NegativeEval -> 4)
    }

    for test <- W3cTestFiles.turtleTests do
      test.testType match {
        case NegativeSyntax | NegativeEval if !acceptedNegativeTests.contains(test.name) =>
          s"reject '${test.name}'" in {
            intercept[RdfParseException](parse(test))
          }
        case NegativeSyntax | NegativeEval =>
          s"accept '${test.name}', unlike the test suite: ${acceptedNegativeTests(test.name)}" in {
            noException should be thrownBy parse(test)
          }
        case PositiveSyntax =>
          s"accept '${test.name}'" in {
            noException should be thrownBy parse(test)
          }
        case Eval =>
          // Full isomorphism is checked on the JVM, with RDF4J.
          s"read the expected triples from '${test.name}', ignoring blank node labels" in {
            def unlabeled(triples: Seq[Triple]) = triples.map { case Triple(s, p, o) =>
              (anonymous(s), p, anonymous(o))
            }.groupMapReduce(identity)(_ => 1)(_ + _)
            unlabeled(parse(test)) shouldBe
              unlabeled(RdfUtils.parseNTriples(test.result.get))
          }
      }
  }

  "TurtleParser, on the W3C Turtle test results," should {
    for (name, content) <- W3cTestFiles.turtleResults do
      s"read back what TurtleWriter writes for '$name'" in {
        for (label, document, pushed) <- TurtleTestCases.withPushed(RdfUtils.parseNTriples(content))
        do
          withClue(s"$label:\n$document\n") {
            RdfUtils.sameUpToBlankNodes(RdfUtils.parseTurtle(document), pushed) shouldBe true
          }
      }
  }
}

object TurtleParserW3cSpec {

  private def anonymous(node: Node): Node = node match {
    case _: AnyBlankNode => BlankNode("")
    case other => other
  }

  /** Negative tests that the parser deliberately accepts, with the reason why. */
  private val acceptedNegativeTests: Map[String, String] = {
    // Bad IRIs are caught during the validation of a LinkML schema, as an ERROR.
    val reason =
      "an escaped character that IRIs cannot contain is accepted (relaxed IRI validation)"
    Map(
      "turtle-eval-bad-01" -> reason,
      "turtle-eval-bad-02" -> reason,
      "turtle-eval-bad-03" -> reason,
    )
  }
}
