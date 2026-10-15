package eu.neverblink.linkml.rdf

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
    * A list member may not be an [[InlineBlankNode]]. Use [[beginList]] for that.
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

  /** Start `subj pred <list>`. Push the members one by one with [[listMember]], then finish the
    * list with [[endList]].
    *
    * Unlike [[list]], a member may be an [[InlineBlankNode]]. Push its own triples right after its
    * [[listMember]] call, before the next member. These triples may start lists of their own, which
    * must be ended before the next member of this list.
    *
    * The default implementation writes the `rdf:first` / `rdf:rest` cells as triples.
    *
    * @param subj
    *   The subject that the list belongs to
    * @param pred
    *   The predicate that links `subj` to the list
    */
  def beginList(subj: Resource, pred: Iri): Unit =
    openLists.addOne(new RdfSink.OpenList(subj, pred))

  /** Add a member to the innermost list that is not ended yet. See [[beginList]].
    *
    * @param value
    *   The member
    */
  def listMember(value: Node): Unit = {
    val list = openLists.last
    val next = nextListCell()
    if (list.cell == null) triple(list.subj, list.pred, next)
    else triple(list.cell, Rdf.rest, next)
    list.cell = next
    triple(next, Rdf.first, value)
  }

  /** End the innermost list that is not ended yet. See [[beginList]]. */
  def endList(): Unit = {
    val list = openLists.remove(openLists.length - 1)
    if (list.cell == null) triple(list.subj, list.pred, Rdf.nil)
    else triple(list.cell, Rdf.rest, Rdf.nil)
  }

  /** The lists started by [[beginList]] and not ended yet, innermost last. */
  private val openLists = scala.collection.mutable.ArrayBuffer.empty[RdfSink.OpenList]

  /** Finish the document. Sinks with nothing to close ignore it. */
  def finish(): Unit = ()

  private var listCells = 0

  /** A blank node for the next `rdf:first` / `rdf:rest` cell. The `l` keeps these clear of the
    * plain numeric labels the generators hand out.
    */
  private def nextListCell(): BlankNode = {
    listCells += 1
    new BlankNode("l".concat(listCells.toString))
  }
}

object RdfSink {

  /** A list started by [[RdfSink.beginList]].
    *
    * @param subj
    *   The subject that the list belongs to
    * @param pred
    *   The predicate that links `subj` to the list
    */
  private final class OpenList(val subj: Resource, val pred: Iri) {

    /** The cell of the last member, or null before the first member. */
    var cell: BlankNode = null
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
