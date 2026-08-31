package eu.neverblink.linkml.generator.rdf

/** A streaming sink for RDF output.
  *
  * Everything a producer pushes must arrive in document order, and [[finish]] must be called once
  * at the end. Sinks are single-use and not thread-safe.
  */
trait RdfSink {

  /** Declare a namespace prefix, before any triple. Sinks that have no prefix mechanism (e.g.
    * N-Triples) ignore it, as does any sink that cannot write `prefix` as a prefixed name.
    */
  def namespace(prefix: String, name: String): Unit

  /** Declare the document's base IRI, before any triple.
    */
  def base(iri: String): Unit = ()

  /** Emit a triple. */
  def triple(subj: Resource, pred: Iri, obj: Node): Unit

  /** Emit `subj pred <list>`, where `<list>` is the RDF list of `values`, or `rdf:nil` when
    * `values` is empty.
    *
    * The referencing triple comes first so that Turtle can write the whole thing as one `( ... )`
    * collection.
    *
    * A list member may not be an [[InlineBlankNode]].
    */
  def list(subj: Resource, pred: Iri, values: Seq[Node]): Unit =
    if (values.isEmpty) triple(subj, pred, Rdf.nil)
    else {
      var cell = nextListCell()
      triple(subj, pred, cell)
      val it = values.iterator
      while (it.hasNext) {
        triple(cell, Rdf.first, it.next())
        if (it.hasNext) {
          val next = nextListCell()
          triple(cell, Rdf.rest, next)
          cell = next
        } else triple(cell, Rdf.rest, Rdf.nil)
      }
    }

  /** Finish the document. Sinks with nothing to close ignore it. */
  def finish(): Unit = ()

  private var listCells = 0

  /** A blank node for the next `rdf:first` / `rdf:rest` cell. The `l` keeps these clear of the
    * plain numeric labels [[RdfGenerator.blankNode]] hands out.
    */
  private def nextListCell(): BlankNode = {
    listCells += 1
    new BlankNode("l".concat(listCells.toString))
  }
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
