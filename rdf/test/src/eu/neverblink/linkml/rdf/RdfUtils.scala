package eu.neverblink.linkml.rdf

import eu.neverblink.linkml.rdf.io.StringSink

import scala.collection.mutable

/** RDF output for tests that push triples into an [[RdfSink]] by hand, and the parsers to read it
  * back.
  */
object RdfUtils {
  def toTurtle(write: RdfSink => Unit): String = toString(new TurtleWriter(_), write)
  def toNTriples(write: RdfSink => Unit): String = toString(new NTriplesWriter(_), write)

  def writeAll(sink: RdfSink, triples: Seq[Triple]): Unit =
    triples.foreach(t => sink.triple(t.subj, t.pred, t.obj))

  def parseTurtle(document: String, baseIri: Option[String] = None): Seq[Triple] = {
    val sink = new CollectingRdfSink
    TurtleParser.parse(document, sink, baseIri)
    sink.triples
  }

  def parseNTriples(document: String): Seq[Triple] = {
    val sink = new CollectingRdfSink
    NTriplesParser.parse(document, sink)
    sink.triples
  }

  /** Whether `a` and `b` are the same triples in the same order, up to a one-to-one renaming of
    * blank nodes.
    *
    * Order matters, because both parsers push triples in the order a writer was given them, and
    * matching them up in order is much simpler than graph isomorphism.
    */
  def sameUpToBlankNodes(a: Seq[Triple], b: Seq[Triple]): Boolean = {
    val aToB = mutable.HashMap.empty[String, String]
    val bToA = mutable.HashMap.empty[String, String]
    def same(x: Node, y: Node): Boolean = (x, y) match {
      case (x: AnyBlankNode, y: AnyBlankNode) =>
        aToB.getOrElseUpdate(x.id, y.id) == y.id && bToA.getOrElseUpdate(y.id, x.id) == x.id
      case (_: AnyBlankNode, _) | (_, _: AnyBlankNode) => false
      case _ => x == y
    }
    a.size == b.size && a.lazyZip(b).forall { (x, y) =>
      same(x.subj, y.subj) && same(x.pred, y.pred) && same(x.obj, y.obj)
    }
  }

  private def toString(writer: StringSink => RdfSink, write: RdfSink => Unit): String = {
    val sink = new StringSink
    val rdf = writer(sink)
    write(rdf)
    rdf.finish()
    sink.result
  }
}
