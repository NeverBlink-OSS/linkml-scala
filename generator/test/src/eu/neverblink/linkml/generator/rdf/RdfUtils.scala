package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.StringSink

/** RDF output for tests that push triples into an [[RdfSink]] by hand.
  */
object RdfUtils {
  def toTurtle(write: RdfSink => Unit): String = toString(new TurtleWriter(_), write)
  def toNTriples(write: RdfSink => Unit): String = toString(new NTriplesWriter(_), write)

  private def toString(writer: StringSink => RdfSink, write: RdfSink => Unit): String = {
    val sink = new StringSink
    val rdf = writer(sink)
    write(rdf)
    rdf.finish()
    sink.result
  }
}
