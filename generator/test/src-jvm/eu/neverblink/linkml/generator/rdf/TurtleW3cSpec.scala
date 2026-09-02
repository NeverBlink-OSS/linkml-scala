package eu.neverblink.linkml.generator.rdf

import org.eclipse.rdf4j.rio.RDFFormat

/** Conformance tests for [[TurtleWriter]] over the W3C corpus. See [[W3cRoundTripSpec]].
  *
  * Every file is written three times: with every blank node labeled, with the blank nodes that can
  * be inlined written as `[ ... ]`, and with prefixes declared so that IRIs come out as prefixed
  * names.
  */
class TurtleW3cSpec extends W3cRoundTripSpec(RDFFormat.TURTLE) {

  override protected def makeTestCases(triples: Seq[Triple]): Seq[(String, String)] = Seq(
    "with every blank node labeled" ->
      RdfUtils.toTurtle(sink => triples.foreach(writeTriple(sink, _))),
    "with blank nodes inlined" -> RdfUtils.toTurtle(writeInlined(_, triples)),
    "with prefixes declared" -> RdfUtils.toTurtle { sink =>
      declareNamespaces(sink, triples)
      triples.foreach(writeTriple(sink, _))
    },
  )

  private def declareNamespaces(sink: RdfSink, triples: Seq[Triple]): Unit = {
    val iris = triples.flatMap(t => Seq(t.subj, t.pred, t.obj)).collect { case Iri(value) => value }
    val namespaces = iris.map { value =>
      val cut = math.max(value.lastIndexOf('#'), value.lastIndexOf('/'))
      if (cut < 0) value else value.substring(0, cut + 1)
    }.distinct.filter(_.nonEmpty).sorted
    namespaces.zipWithIndex.foreach((name, i) => sink.namespace(s"ns$i", name))
  }

  private def writeTriple(sink: RdfSink, triple: Triple): Unit =
    sink.triple(triple.subj, triple.pred, triple.obj)

  /** Write [[triples]] grouped by subject, inlining the blank nodes that can be inlined.
    */
  private def writeInlined(sink: RdfSink, triples: Seq[Triple]): Unit = {
    val bySubject = triples.groupBy(_.subj)
    val objectUses = triples.collect { case Triple(_, _, o: BlankNode) => o }.groupBy(identity)
    val inlinable = bySubject.keySet.collect {
      case b: BlankNode
          if objectUses.get(b).exists(_.sizeIs == 1) &&
            !bySubject(b).exists(_.obj.isInstanceOf[BlankNode]) =>
        b
    }

    def objectFor(obj: Node): Node = obj match {
      case b: BlankNode if inlinable(b) => InlineBlankNode(b.id)
      case other => other
    }

    for
      subject <- triples.map(_.subj).distinct if !subject.isInstanceOf[BlankNode] ||
        !inlinable(subject.asInstanceOf[BlankNode])
    do
      for triple <- bySubject(subject) do {
        sink.triple(triple.subj, triple.pred, objectFor(triple.obj))
        // The inlined blank node's own triples have to come straight after the reference, and
        // under the same InlineBlankNode the reference used.
        triple.obj match {
          case b: BlankNode if inlinable(b) =>
            val inline = InlineBlankNode(b.id)
            bySubject(b).foreach(t => sink.triple(inline, t.pred, t.obj))
          case _ => ()
        }
      }
  }
}
