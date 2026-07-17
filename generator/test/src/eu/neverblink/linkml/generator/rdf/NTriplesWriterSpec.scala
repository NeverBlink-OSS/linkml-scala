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

  "NTriplesWriter.format" should {
    "format an IRI in angle brackets" in {
      NTriplesWriter.format(Iri("http://example.org/x")) shouldBe "<http://example.org/x>"
    }
    "format a blank node with the _: prefix" in {
      NTriplesWriter.format(BlankNode("b0")) shouldBe "_:b0"
    }
    "format an xsd:string literal as a simple literal (no datatype, per RDF 1.1)" in {
      NTriplesWriter.format(Literal("hello")) shouldBe "\"hello\""
    }
    "format a typed integer literal with its datatype" in {
      NTriplesWriter.format(Literal("42", XmlSchema.integer)) shouldBe
        "\"42\"^^<http://www.w3.org/2001/XMLSchema#integer>"
    }
  }

  "NTriplesWriter.writeToString" should {
    "write a triple of IRIs as one terminated line" in {
      NTriplesWriter.writeToString(Seq(Triple(s, p, o))) shouldBe
        "<http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .\n"
    }

    "write a blank-node subject and object" in {
      NTriplesWriter.writeToString(Seq(Triple(BlankNode("b1"), p, BlankNode("b2")))) shouldBe
        "_:b1 <http://example.org/predicate> _:b2 .\n"
    }

    "write an xsd:string literal object as a simple literal" in {
      NTriplesWriter.writeToString(Seq(Triple(s, p, Literal("v")))) shouldBe
        "<http://example.org/subject> <http://example.org/predicate> \"v\" .\n"
    }

    "write a non-string literal object with its datatype" in {
      NTriplesWriter.writeToString(Seq(Triple(s, p, Literal("42", XmlSchema.integer)))) shouldBe
        "<http://example.org/subject> <http://example.org/predicate> " +
        "\"42\"^^<http://www.w3.org/2001/XMLSchema#integer> .\n"
    }

    "write one terminated line per triple" in {
      val out = NTriplesWriter.writeToString(
        Seq(
          Triple(s, p, o),
          Triple(s, Rdf.`type`, BlankNode("b0")),
        ),
      )
      out shouldBe
        "<http://example.org/subject> <http://example.org/predicate> <http://example.org/object> .\n" +
        "<http://example.org/subject> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> _:b0 .\n"
      out.linesIterator.size shouldBe 2
      out should endWith(" .\n")
    }

    "produce pure US-ASCII output even for non-ASCII content" in {
      // "caf<e-acute> <o-umlaut> <grinning-face>", built from code points to keep the source ASCII.
      val text = "caf" + new String(Character.toChars(0xe9)) + " " +
        new String(Character.toChars(0xf6)) + " " + new String(Character.toChars(0x1f600))
      val out = NTriplesWriter.writeToString(Seq(Triple(s, p, Literal(text))))
      out.forall(_ < 0x80) shouldBe true
      out should include("\"caf\\u00E9 \\u00F6 \\U0001F600\"")
    }

    "return an empty string for no triples" in {
      NTriplesWriter.writeToString(Nil) shouldBe ""
    }
  }
}
