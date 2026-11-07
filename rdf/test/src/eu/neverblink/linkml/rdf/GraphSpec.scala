package eu.neverblink.linkml.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GraphSpec extends AnyWordSpec, Matchers {

  private val s = Iri("http://example.org/s")
  private val p = Iri("http://example.org/p")
  private def iri(n: Int) = Iri(s"http://example.org/$n")
  private def b(id: String) = BlankNode(id)

  /** The triples of a list of `members`, with cells `l0`, `l1`, … */
  private def listTriples(members: Node*): IndexedSeq[Triple] =
    members.indices.flatMap { i =>
      val rest = if i == members.size - 1 then Rdf.nil else b(s"l${i + 1}")
      Seq(Triple(b(s"l$i"), Rdf.first, members(i)), Triple(b(s"l$i"), Rdf.rest, rest))
    }

  "Graph" should {
    "count a repeated triple once" in {
      val t = Triple(s, p, iri(1))
      val graph = Graph(Vector(t, Triple(s, p, iri(2)), t))
      val is = graph.about(s)
      (0 until is.length).map(is(_)) shouldBe Seq(0, 1)
      graph.isUsed(2) shouldBe true
      graph.unused shouldBe Seq(t, Triple(s, p, iri(2)))
    }

    "count a repeated triple once for a subject with many triples" in {
      val many = (1 to 20).map(n => Triple(s, p, iri(n)))
      val graph = Graph(many ++ many)
      val is = graph.about(s)
      (0 until is.length).map(is(_)) shouldBe (0 until 20)
      graph.unused shouldBe many
    }

    "index triples by subject, in order of first appearance" in {
      val graph =
        Graph(Vector(Triple(s, p, iri(1)), Triple(b("x"), p, iri(2)), Triple(s, p, iri(3))))
      def indices(is: Graph.Indices): Seq[Int] = (0 until is.length).map(is(_))
      (0 until graph.subjectCount).map { k =>
        val is = graph.subjectAt(k)
        is.subject -> indices(is)
      } shouldBe Seq(s -> Seq(0, 2), b("x") -> Seq(1))
      indices(graph.about(s)) shouldBe Seq(0, 2)
      graph.about(iri(9)).isEmpty shouldBe true
    }

    "tell which blank nodes are objects" in {
      val graph = Graph(Vector(Triple(s, p, b("x")), Triple(b("y"), p, s)))
      graph.isObject(b("x")) shouldBe true
      graph.isObject(b("y")) shouldBe false
    }

    "list the triples not used" in {
      val graph = Graph(Vector(Triple(s, p, iri(1)), Triple(s, p, iri(2))))
      graph.use(0)
      graph.isUsed(0) shouldBe true
      graph.unused shouldBe Seq(Triple(s, p, iri(2)))
    }

    "read an RDF list and say which triples it read" in {
      val graph = Graph(Triple(s, p, b("l0")) +: listTriples(iri(1), iri(2)))
      val touched = IntBuffer()
      graph.list(b("l0"), touched) shouldBe Some(Seq(iri(1), iri(2)))
      (0 until touched.length).map(touched(_)).sorted shouldBe Seq(1, 2, 3, 4)
    }

    "read a list without recording its triples" in {
      val graph = Graph(Triple(s, p, b("l0")) +: listTriples(iri(1)))
      graph.list(b("l0"), null) shouldBe Some(Seq(iri(1)))
    }

    "read the empty list" in {
      Graph(Vector.empty).list(Rdf.nil, IntBuffer()) shouldBe Some(Nil)
    }

    "reject a list that is not well formed" in {
      def read(triples: IndexedSeq[Triple]) = Graph(triples).list(b("l0"), IntBuffer())
      // A loop.
      read(
        Vector(Triple(b("l0"), Rdf.first, iri(1)), Triple(b("l0"), Rdf.rest, b("l0"))),
      ) shouldBe None
      // No rdf:rest.
      read(Vector(Triple(b("l0"), Rdf.first, iri(1)))) shouldBe None
      // A cell that is an IRI.
      Graph(listTriples(iri(1))).list(iri(1), IntBuffer()) shouldBe None
    }
  }
}
