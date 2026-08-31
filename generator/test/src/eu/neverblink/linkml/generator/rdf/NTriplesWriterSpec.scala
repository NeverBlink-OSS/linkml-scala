package eu.neverblink.linkml.generator.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Serialization + conformance tests for [[NTriplesWriter]], following the grammar of the RDF Test
  * Cases spec (https://www.w3.org/TR/rdf-testcases/#ntriples).
  */
class NTriplesWriterSpec extends AnyWordSpec, Matchers {

  private val s = Iri("http://example.org/subject")
  private val p = Iri("http://example.org/predicate")
  private val o = Iri("http://example.org/object")

  private def nTriples(triples: Triple*): String =
    RdfUtils.toNTriples(sink => triples.foreach(t => sink.triple(t.subj, t.pred, t.obj)))

  /** One node, written as the object of a triple - the position every node shape can appear in. */
  private def term(node: Node): String =
    nTriples(Triple(s, p, node))
      .stripPrefix("<http://example.org/subject> <http://example.org/predicate> ")
      .stripSuffix(" .\n")

  "NTriplesWriter, on single terms," should {
    "write an IRI in angle brackets" in {
      term(Iri("http://example.org/x")) shouldBe "<http://example.org/x>"
    }
    "write a blank node with the _: prefix" in {
      term(BlankNode("b0")) shouldBe "_:b0"
    }
    "write an inline blank node with a label too, having no way to inline it" in {
      term(InlineBlankNode("1")) shouldBe "_:1"
    }
    "write an xsd:string literal as a simple literal (no datatype, per RDF 1.1)" in {
      term(Literal("hello")) shouldBe "\"hello\""
    }
    "write a typed integer literal with its datatype" in {
      term(Literal("42", XmlSchema.integer)) shouldBe
        "\"42\"^^<http://www.w3.org/2001/XMLSchema#integer>"
    }
    "write a language literal with its tag" in {
      term(LanguageLiteral("bonjour", "fr-BE")) shouldBe "\"bonjour\"@fr-BE"
    }
  }

  "NTriplesWriter" should {
    "write a triple of IRIs as one terminated line" in {
      nTriples(Triple(s, p, o)) shouldBe
        "<http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .\n"
    }

    "write a blank-node subject and object" in {
      nTriples(Triple(BlankNode("b1"), p, BlankNode("b2"))) shouldBe
        "_:b1 <http://example.org/predicate> _:b2 .\n"
    }

    "write one terminated line per triple" in {
      val out = nTriples(
        Triple(s, p, o),
        Triple(s, Rdf.`type`, BlankNode("b0")),
      )
      out shouldBe
        "<http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .\n" +
        "<http://example.org/subject> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> _:b0 .\n"
      out.linesIterator.size shouldBe 2
      out should endWith(" .\n")
    }

    "repeat the subject rather than grouping it, unlike Turtle" in {
      nTriples(Triple(s, p, o), Triple(s, p, o)) shouldBe
        "<http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .\n" +
        "<http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .\n"
    }

    "produce pure US-ASCII output even for non-ASCII content" in {
      // "caf<e-acute> <o-umlaut> <grinning-face>", built from code points to keep the source ASCII.
      val text = "caf" + new String(Character.toChars(0xe9)) + " " +
        new String(Character.toChars(0xf6)) + " " + new String(Character.toChars(0x1f600))
      val out = nTriples(Triple(s, p, Literal(text)))
      out.forall(_ < 0x80) shouldBe true
      out should include("\"caf\\u00E9 \\u00F6 \\U0001F600\"")
    }

    "return an empty string for no triples" in {
      nTriples() shouldBe ""
    }

    "drop the namespaces and base it has nowhere to put" in {
      RdfUtils.toNTriples { sink =>
        sink.namespace("ex", "http://example.org/")
        sink.base("http://example.org/")
        sink.triple(s, p, o)
      } shouldBe
        "<http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .\n"
    }

    "spell an RDF list out as rdf:first / rdf:rest, having no collections" in {
      RdfUtils.toNTriples(_.list(s, p, Seq(o))) shouldBe
        "<http://example.org/subject> <http://example.org/predicate> _:l1 .\n" +
        "_:l1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#first> <http://example.org/object> .\n" +
        "_:l1 <http://www.w3.org/1999/02/22-rdf-syntax-ns#rest> " +
        "<http://www.w3.org/1999/02/22-rdf-syntax-ns#nil> .\n"
    }
  }
}
