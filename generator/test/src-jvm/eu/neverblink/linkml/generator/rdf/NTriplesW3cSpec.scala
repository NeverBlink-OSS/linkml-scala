package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.Utf8ByteSink
import org.eclipse.rdf4j.rio.RDFFormat

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** Conformance tests for [[NTriplesWriter]] over the W3C corpus. See [[W3cRoundTripSpec]].
  *
  * Every file is written twice, once into a string and once into a byte sink. The byte sink uses a
  * deliberately awkward buffer size so that the corpus is also cut across buffer boundaries in
  * every possible place.
  */
class NTriplesW3cSpec extends W3cRoundTripSpec(RDFFormat.NTRIPLES) {

  override protected def makeTestCases(triples: Seq[Triple]): Seq[(String, String)] = Seq(
    "written to a string" ->
      RdfUtils.toNTriples(sink => triples.foreach(t => sink.triple(t.subj, t.pred, t.obj))),
    "written to a byte sink" -> {
      val out = new ByteArrayOutputStream
      val sink = new Utf8ByteSink(out, bufferSize = 17)
      val writer = new NTriplesWriter(sink)
      triples.foreach(t => writer.triple(t.subj, t.pred, t.obj))
      writer.finish()
      sink.flush()
      new String(out.toByteArray, UTF_8)
    },
  )
}
