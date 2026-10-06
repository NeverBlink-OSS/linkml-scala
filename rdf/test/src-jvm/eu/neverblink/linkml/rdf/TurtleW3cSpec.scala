package eu.neverblink.linkml.rdf

import org.eclipse.rdf4j.rio.RDFFormat

/** Conformance tests for [[TurtleWriter]] over the W3C corpus. See [[W3cRoundTripSpec]], and
  * [[TurtleTestCases]] for the ways each file is written.
  */
class TurtleW3cSpec extends W3cRoundTripSpec(RDFFormat.TURTLE) {

  override protected def makeTestCases(triples: Seq[Triple]): Seq[(String, String)] =
    TurtleTestCases(triples)
}
