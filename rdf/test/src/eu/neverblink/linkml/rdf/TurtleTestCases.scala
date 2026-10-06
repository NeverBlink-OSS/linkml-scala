package eu.neverblink.linkml.rdf

/** The ways the Turtle writer tests write a flat list of triples: with every blank node labeled,
  * with the blank nodes that can be inlined written as `[ ... ]`, and with prefixes declared so
  * that IRIs come out as prefixed names.
  */
object TurtleTestCases {

  def apply(triples: Seq[Triple]): Seq[(String, String)] =
    withPushed(triples).map((label, document, _) => (label, document))

  /** As [[apply]], with the triples in the order the writer was given them. */
  def withPushed(triples: Seq[Triple]): Seq[(String, String, Seq[Triple])] = Seq(
    "with every blank node labeled" -> (RdfUtils.writeAll(_, triples)),
    "with blank nodes inlined" -> (writeInlined(_, triples)),
    "with prefixes declared" -> { (sink: RdfSink) =>
      declareNamespaces(sink, triples)
      RdfUtils.writeAll(sink, triples)
    },
  ).map { (label, write) =>
    val pushed = new CollectingRdfSink
    write(pushed)
    (label, RdfUtils.toTurtle(write), pushed.triples)
  }

  private def declareNamespaces(sink: RdfSink, triples: Seq[Triple]): Unit = {
    val iris = triples.flatMap(t => Seq(t.subj, t.pred, t.obj)).collect { case Iri(value) => value }
    val namespaces = iris.map { value =>
      val cut = math.max(value.lastIndexOf('#'), value.lastIndexOf('/'))
      if (cut < 0) value else value.substring(0, cut + 1)
    }.distinct.filter(_.nonEmpty).sorted
    namespaces.zipWithIndex.foreach((name, i) => sink.namespace(s"ns$i", name))
  }

  /** Write `triples` grouped by subject, inlining the blank nodes that can be inlined.
    */
  def writeInlined(sink: RdfSink, triples: Seq[Triple]): Unit = {
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
