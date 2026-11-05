package eu.neverblink.linkml.rdf

import scala.collection.mutable

/** An in-memory RDF graph for importers that read a model from triples.
  *
  * Triples are indexed by subject and referred to by their index in [[triples]]. The graph tracks
  * which triples the reader has used, so the rest can be reported as not imported.
  *
  * @param triples
  *   The triples, in input order. A graph is a set, so a repeated triple counts once: only its
  *   first one is in the index of its subject, and the others are marked as used from the start.
  */
final class Graph(val triples: IndexedSeq[Triple]) {
  import Graph.*

  private val bySubject = mutable.HashMap.empty[Resource, Indices]

  /** The subjects, in order of first appearance. */
  private val subjects = mutable.ArrayBuffer.empty[Indices]

  /** Blank nodes used as an object, so they belong to something else. */
  private val objects = mutable.HashSet.empty[AnyBlankNode]

  private val used = new java.util.BitSet(triples.size)

  {
    // Count the triples of each subject, then put their indices in one array, grouped by subject.
    val n = triples.size
    var i = 0
    while (i < n) {
      val t = triples(i)
      var indices = bySubject.getOrElse(t.subj, null)
      if (indices eq null) {
        indices = new Indices(t.subj)
        bySubject.update(t.subj, indices)
        subjects.addOne(indices)
      }
      indices.count += 1
      t.obj match {
        case b: AnyBlankNode => objects.addOne(b)
        case _ =>
      }
      i += 1
    }
    val all = new Array[Int](n)
    var start = 0
    subjects.foreach { indices =>
      indices.all = all
      indices.start = start
      start += indices.count
      indices.count = 0
    }
    i = 0
    while (i < n) {
      val indices = bySubject(triples(i).subj)
      all(indices.start + indices.count) = i
      indices.count += 1
      i += 1
    }
    // A repeated triple has the same subject, so repeats are looked for within each subject.
    subjects.foreach(removeRepeats)
  }

  /** Drops the repeats of a triple from the indices of its subject and marks them as used. */
  private def removeRepeats(indices: Indices): Unit =
    if (indices.count > 1) {
      val all = indices.all
      val start = indices.start
      val end = start + indices.count
      var kept = start
      var j = start
      if (indices.count <= 16) {
        // Few triples: compare each with those kept before it.
        while (j < end) {
          val i = all(j)
          val t = triples(i)
          var k = start
          while (k < kept && triples(all(k)) != t) k += 1
          if (k < kept) used.set(i)
          else {
            all(kept) = i
            kept += 1
          }
          j += 1
        }
      } else {
        val seen = mutable.HashSet.empty[Triple]
        while (j < end) {
          val i = all(j)
          if (seen.add(triples(i))) {
            all(kept) = i
            kept += 1
          } else used.set(i)
          j += 1
        }
      }
      indices.count = kept - start
    }

  /** The number of subjects. */
  def subjectCount: Int = subjects.length

  /** The triples of the subject at `k`, in order of first appearance. */
  def subjectAt(k: Int): Indices = subjects(k)

  /** The triples about the subject. */
  def about(subject: Resource): Indices = bySubject.getOrElse(subject, Indices.empty)

  def isObject(node: AnyBlankNode): Boolean = objects.contains(node)

  def use(i: Int): Unit = used.set(i)

  def useAll(indices: Indices): Unit = indices.foreach(used.set)

  def useAll(indices: IntBuffer): Unit = indices.foreach(used.set)

  def isUsed(i: Int): Boolean = used.get(i)

  def unused: Seq[Triple] = {
    val out = Vector.newBuilder[Triple]
    var i = used.nextClearBit(0)
    while (i < triples.size) {
      out.addOne(triples(i))
      i = used.nextClearBit(i + 1)
    }
    out.result()
  }

  /** The members of the RDF list at `node`, or `None` if the list is malformed: a cell is missing
    * `rdf:first` or `rdf:rest`, is not a blank node, or the list loops. Adds the indices of the
    * list's triples to `touched`, unless it is `null`, so the caller can mark them as used once it
    * is done.
    */
  def list(node: Node, touched: IntBuffer): Option[Seq[Node]] = {
    val out = Vector.newBuilder[Node]
    var current = node
    val seen = mutable.HashSet.empty[Node]
    while (current != Rdf.nil) {
      if (!seen.add(current)) return None
      val cell = current match {
        case b: AnyBlankNode => about(b)
        case _ => return None
      }
      var first: Node = null
      var rest: Node = null
      var j = 0
      while (j < cell.length) {
        val i = cell(j)
        val t = triples(i)
        if (t.pred == Rdf.first) {
          first = t.obj
          if (touched ne null) touched.add(i)
        } else if (t.pred == Rdf.rest) {
          rest = t.obj
          if (touched ne null) touched.add(i)
        }
        j += 1
      }
      if ((first eq null) || (rest eq null)) return None
      out.addOne(first)
      current = rest
    }
    Some(out.result())
  }
}

object Graph {

  /** The indices in [[Graph.triples]] of the triples about one subject, in input order. */
  final class Indices private[Graph] (val subject: Resource) {
    private[Graph] var all: Array[Int] = Array.emptyIntArray
    private[Graph] var start = 0
    private[Graph] var count = 0

    def length: Int = count

    def isEmpty: Boolean = count == 0

    def apply(k: Int): Int = all(start + k)

    def foreach(f: Int => Unit): Unit = {
      var k = start
      val end = start + count
      while (k < end) {
        f(all(k))
        k += 1
      }
    }

    def exists(p: Int => Boolean): Boolean = {
      var k = start
      val end = start + count
      while (k < end && !p(all(k))) k += 1
      k < end
    }
  }

  object Indices {
    val empty: Indices = new Indices(null)
  }
}
