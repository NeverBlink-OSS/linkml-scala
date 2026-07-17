package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.CharSink

/** A streaming sink for RDF output.
  */
trait RdfSink {

  /** Declare a namespace prefix. Sinks that have no prefix mechanism (e.g. N-Triples) ignore it. */
  def namespace(prefix: String, name: String): Unit

  /** Emit a triple. */
  def triple(subj: Resource, pred: Iri, obj: Node): Unit
}

/** Collects everything pushed to it into [[namespaces]] and [[triples]]. Used in tests and
  * benchmarks.
  */
final class CollectingRdfSink extends RdfSink {
  private val ns = Seq.newBuilder[Namespace]
  private val tr = Seq.newBuilder[Triple]

  def namespace(prefix: String, name: String): Unit = ns.addOne(Namespace(prefix, name))
  def triple(subj: Resource, pred: Iri, obj: Node): Unit = tr.addOne(Triple(subj, pred, obj))

  def namespaces: Seq[Namespace] = ns.result()
  def triples: Seq[Triple] = tr.result()
}

final class NTriplesRdfSink(out: CharSink) extends RdfSink {
  def namespace(prefix: String, name: String): Unit = ()
  def triple(subj: Resource, pred: Iri, obj: Node): Unit =
    NTriplesWriter.writeTriple(out, subj, pred, obj)
}
