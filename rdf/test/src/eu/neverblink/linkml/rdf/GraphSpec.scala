package eu.neverblink.linkml.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

class GraphSpec extends AnyWordSpec, Matchers {

  private val s = Iri("http://example.org/s")
  private val p = Iri("http://example.org/p")
  private def iri(n: Int) = Iri(s"http://example.org/$n")
  private def b(id: String) = BlankNode(id)

  /** The triples of a list of `members`, with cells `l0`, `l1`, … */
  private def listTriples(members: Node*): Seq[Triple] =
    members.indices.flatMap { i =>
      val rest = if i == members.size - 1 then Rdf.nil else b(s"l${i + 1}")
      Seq(Triple(b(s"l$i"), Rdf.first, members(i)), Triple(b(s"l$i"), Rdf.rest, rest))
    }

  "Graph" should {
    "count a repeated triple once" in {
      val t = Triple(s, p, iri(1))
      Graph(Seq(t, t)).triples shouldBe Seq(t)
    }

    "index triples by subject, in order of first appearance" in {
      val graph = Graph(Seq(Triple(s, p, iri(1)), Triple(b("x"), p, iri(2)), Triple(s, p, iri(3))))
      graph.subjects.map((subject, is) => subject -> is.toSeq).toSeq shouldBe
        Seq(s -> Seq(0, 2), b("x") -> Seq(1))
      graph.about(iri(9)) shouldBe empty
    }

    "tell which blank nodes are objects" in {
      val graph = Graph(Seq(Triple(s, p, b("x")), Triple(b("y"), p, s)))
      graph.isObject(b("x")) shouldBe true
      graph.isObject(b("y")) shouldBe false
    }

    "list the triples not used" in {
      val graph = Graph(Seq(Triple(s, p, iri(1)), Triple(s, p, iri(2))))
      graph.use(0)
      graph.isUsed(0) shouldBe true
      graph.unused shouldBe Seq(Triple(s, p, iri(2)))
    }

    "read an RDF list and say which triples it read" in {
      val graph = Graph(Triple(s, p, b("l0")) +: listTriples(iri(1), iri(2)))
      val touched = mutable.ArrayBuffer.empty[Int]
      graph.list(b("l0"), touched) shouldBe Some(Seq(iri(1), iri(2)))
      touched.sorted shouldBe Seq(1, 2, 3, 4)
    }

    "read the empty list" in {
      Graph(Nil).list(Rdf.nil, mutable.ArrayBuffer.empty) shouldBe Some(Nil)
    }

    "reject a list that is not well formed" in {
      def read(triples: Seq[Triple]) = Graph(triples).list(b("l0"), mutable.ArrayBuffer.empty)
      // A loop.
      read(
        Seq(Triple(b("l0"), Rdf.first, iri(1)), Triple(b("l0"), Rdf.rest, b("l0"))),
      ) shouldBe None
      // No rdf:rest.
      read(Seq(Triple(b("l0"), Rdf.first, iri(1)))) shouldBe None
      // A cell that is an IRI.
      Graph(listTriples(iri(1))).list(iri(1), mutable.ArrayBuffer.empty) shouldBe None
    }
  }
}
