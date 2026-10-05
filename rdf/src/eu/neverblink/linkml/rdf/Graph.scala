package eu.neverblink.linkml.rdf

import scala.collection.mutable

/** An in-memory RDF graph for importers that read a model from triples.
  *
  * Triples are indexed by subject and referred to by their index in [[triples]]. The graph tracks
  * which triples the reader has used, so the rest can be reported as not imported.
  *
  * @param input
  *   The triples. Duplicates count once.
  */
final class Graph(input: Iterable[Triple]) {

  /** Without duplicates, in input order. */
  val triples: IndexedSeq[Triple] = input.iterator.distinct.toIndexedSeq

  private val bySubject = mutable.LinkedHashMap.empty[Resource, mutable.ArrayBuffer[Int]]

  /** Blank nodes used as an object, so they belong to something else. */
  private val objects = mutable.HashSet.empty[AnyBlankNode]

  private val used = new java.util.BitSet(triples.size)

  triples.indices.foreach { i =>
    val t = triples(i)
    bySubject.getOrElseUpdate(t.subj, mutable.ArrayBuffer.empty) += i
    t.obj match {
      case b: AnyBlankNode => objects += b
      case _ =>
    }
  }

  /** Subjects in order of first appearance, with the indices of their triples. */
  def subjects: Iterator[(Resource, collection.IndexedSeq[Int])] = bySubject.iterator

  def about(subject: Resource): collection.IndexedSeq[Int] =
    bySubject.getOrElse(subject, Graph.none)

  def isObject(node: AnyBlankNode): Boolean = objects.contains(node)

  def use(i: Int): Unit = used.set(i)

  def isUsed(i: Int): Boolean = used.get(i)

  def unused: Seq[Triple] = triples.indices.filterNot(used.get).map(triples)

  /** The members of the RDF list at `node`, or `None` if the list is malformed: a cell is missing
    * `rdf:first` or `rdf:rest`, is not a blank node, or the list loops. Adds the indices of the
    * list's triples to `touched`, so the caller can mark them as used once it is done.
    */
  def list(node: Node, touched: mutable.Growable[Int]): Option[Seq[Node]] = {
    val out = Seq.newBuilder[Node]
    var current = node
    val seen = mutable.HashSet.empty[Node]
    while current != Rdf.nil do {
      if !seen.add(current) then return None
      val cell = current match {
        case b: AnyBlankNode => about(b)
        case _ => return None
      }
      var first: Option[Node] = None
      var rest: Option[Node] = None
      cell.foreach { i =>
        triples(i) match {
          case Triple(_, Rdf.first, o) =>
            first = Some(o)
            touched += i
          case Triple(_, Rdf.rest, o) =>
            rest = Some(o)
            touched += i
          case _ =>
        }
      }
      (first, rest) match {
        case (Some(f), Some(r)) =>
          out += f
          current = r
        case _ => return None
      }
    }
    Some(out.result())
  }
}

object Graph {
  private val none: collection.IndexedSeq[Int] = IndexedSeq.empty
}
