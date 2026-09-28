package eu.neverblink.linkml.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class NTriplesParserSpec extends AnyWordSpec, Matchers {

  private def cp(c: Int): String = new String(Character.toChars(c))

  private def parse(document: String): Seq[Triple] = {
    val sink = new CollectingRdfSink
    NTriplesParser.parse(document, sink)
    sink.triples
  }

  private def parseBytes(bytes: Array[Byte], bufferSize: Int): Seq[Triple] = {
    val sink = new CollectingRdfSink
    NTriplesParser.parse(new ByteArrayInputStream(bytes), sink, bufferSize)
    sink.triples
  }

  private def error(document: String): RdfParseException =
    intercept[RdfParseException](parse(document))

  /** The object of the one triple in `<s> <p> $obj .`. */
  private def obj(term: String): Node = {
    val triples = parse(s"<http://ex/s> <http://ex/p> $term .")
    triples.size shouldBe 1
    triples.head.obj
  }

  private val s = Iri("http://ex/s")
  private val p = Iri("http://ex/p")
  private val o = Iri("http://ex/o")

  "NTriplesParser, on single terms," should {
    "read an IRI" in {
      obj("<http://ex/o>") shouldBe o
    }
    "read a blank node, keeping its label" in {
      obj("_:b0") shouldBe BlankNode("b0")
    }
    "read a simple literal as xsd:string, with the shared instance" in {
      val lit = obj("\"hello\"").asInstanceOf[Literal]
      lit shouldBe Literal("hello")
      lit.datatype should be theSameInstanceAs XmlSchema.string
    }
    "give an explicit xsd:string the shared instance too" in {
      obj("\"hi\"^^<http://www.w3.org/2001/XMLSchema#string>").asInstanceOf[Literal]
        .datatype should be theSameInstanceAs XmlSchema.string
    }
    "read a typed literal" in {
      obj("\"42\"^^<http://www.w3.org/2001/XMLSchema#integer>") shouldBe
        Literal("42", XmlSchema.integer)
    }
    "read a language literal, keeping the tag's case" in {
      obj("\"bonjour\"@fr-BE") shouldBe LanguageLiteral("bonjour", "fr-BE")
    }
    "read an empty literal" in {
      obj("\"\"") shouldBe Literal("")
    }
  }

  "NTriplesParser, on escapes and Unicode," should {
    "unescape every ECHAR" in {
      obj("\"\\t\\b\\n\\r\\f\\\"\\'\\\\\"") shouldBe Literal("\t\b\n\r\f\"'\\")
    }
    "unescape \\u and \\U, making a surrogate pair for astral characters" in {
      obj("\"caf\\u00E9 \\U0001F600\"") shouldBe Literal("caf" + cp(0xe9) + " " + cp(0x1f600))
    }
    "unescape \\u in IRIs" in {
      obj("<http://ex/caf\\u00e9>") shouldBe Iri("http://ex/caf" + cp(0xe9))
    }
    "decode UTF-8 in literals, IRIs and blank node labels" in {
      val text = "za" + cp(0x17c) + cp(0xf3) + cp(0x142) + cp(0x107) + cp(0x1f600)
      obj("\"" + text + "\"") shouldBe Literal(text)
      obj("<http://ex/" + text + ">") shouldBe Iri("http://ex/" + text)
      val label = "b" + cp(0xe9) + cp(0xb7)
      parse(s"_:$label <http://ex/p> _:$label .").head shouldBe
        Triple(BlankNode(label), p, BlankNode(label))
    }
    "accept a lone surrogate from a \\u escape, as NTriplesWriter writes one" in {
      obj("\"\\uD83D\"") shouldBe Literal("\uD83D")
    }
    "reject invalid UTF-8" in {
      val bytes = "<http://ex/s> <http://ex/p> \"x\" .".getBytes(UTF_8)
      bytes(bytes.length - 4) = 0xff.toByte
      intercept[RdfParseException](parseBytes(bytes, 1024)).reason shouldBe "Invalid UTF-8"
    }
  }

  "NTriplesParser, on document structure," should {
    "read one triple per line, in order" in {
      parse("<http://ex/s> <http://ex/p> <http://ex/o> .\n_:a <http://ex/p> \"x\" .\n") shouldBe
        Seq(Triple(s, p, o), Triple(BlankNode("a"), p, Literal("x")))
    }
    "accept an empty document" in {
      parse("") shouldBe Nil
    }
    "skip empty lines, blank lines and comments" in {
      parse("\n  \t\n# comment\n<http://ex/s> <http://ex/p> <http://ex/o> . # trailing\n#") shouldBe
        Seq(Triple(s, p, o))
    }
    "accept LF, CR and CRLF line ends, and no final line end" in {
      val t = "<http://ex/s> <http://ex/p> <http://ex/o> ."
      parse(s"$t\r\n$t\r$t\n$t") shouldBe Seq.fill(4)(Triple(s, p, o))
    }
    "accept the minimal whitespace the grammar allows" in {
      parse("<http://ex/s><http://ex/p><http://ex/o>.") shouldBe Seq(Triple(s, p, o))
      parse("_:a<http://ex/p>_:b.") shouldBe Seq(Triple(BlankNode("a"), p, BlankNode("b")))
      parse("<http://ex/s><http://ex/p>\"x\"@en.") shouldBe
        Seq(Triple(s, p, LanguageLiteral("x", "en")))
    }
    "end a blank node label before a '.' but keep one inside it" in {
      parse("_:a.b <http://ex/p> _:c.") shouldBe Seq(Triple(BlankNode("a.b"), p, BlankNode("c")))
    }
    "skip a leading UTF-8 BOM" in {
      parse("\uFEFF<http://ex/s> <http://ex/p> <http://ex/o> .") shouldBe Seq(Triple(s, p, o))
    }
    "not close the input stream" in {
      var closed = false
      val in = new ByteArrayInputStream("<http://ex/s> <http://ex/p> <http://ex/o> .".getBytes) {
        override def close(): Unit = closed = true
      }
      NTriplesParser.parse(in, new CollectingRdfSink)
      closed shouldBe false
    }
  }

  "NTriplesParser, reading through a small buffer," should {
    val text = "caf" + cp(0xe9) + cp(0x1f600)
    val document = (0 until 200).map { i =>
      s"<http://ex/s$i> <http://ex/p> \"$text $i\\n\"@en-GB .\r\n_:b$i <http://ex/p$text> _:c$i .\n"
    }.mkString
    val expected = parse(document)

    "give the same triples for any buffer size, however lines are cut" in {
      expected.size shouldBe 400
      for bufferSize <- Seq(16, 17, 31, 64, 100, 1000) do
        withClue(s"bufferSize=$bufferSize: ") {
          parseBytes(document.getBytes(UTF_8), bufferSize) shouldBe expected
        }
    }

    "grow the buffer for a line longer than it" in {
      val long = "x" * 10000
      parseBytes(s"<http://ex/s> <http://ex/p> \"$long\" .".getBytes(UTF_8), 16) shouldBe
        Seq(Triple(s, p, Literal(long)))
    }
  }

  "NTriplesParser, on errors," should {
    "report the line and column, counting CRLF as one line end" in {
      val e = error(
        "<http://ex/s> <http://ex/p> <http://ex/o> .\r\n\r\n<http://ex/s> <p> <http://ex/o> .",
      )
      e.reason shouldBe "Relative IRIs are not allowed"
      (e.line, e.column) shouldBe (3, 16)
    }
    "count columns in code points" in {
      error("<http://ex/" + cp(0x1f600) + cp(0xe9) + "> <http://ex/p> 1 .").column shouldBe 30
    }
    "reject a missing '.'" in {
      error("<http://ex/s> <http://ex/p> <http://ex/o>").reason shouldBe
        "Expected '.' at the end of the triple"
    }
    "reject a second triple on the same line" in {
      error(
        "<http://ex/s> <http://ex/p> <http://ex/o> . <http://ex/s> <http://ex/p> <http://ex/o> .",
      )
        .reason shouldBe "Expected the end of the line after '.'"
    }
    "reject a literal or blank node as the predicate" in {
      error("<http://ex/s> _:p <http://ex/o> .").reason shouldBe "Expected an IRI as the predicate"
      error(
        "<http://ex/s> \"p\" <http://ex/o> .",
      ).reason shouldBe "Expected an IRI as the predicate"
    }
    "reject a literal as the subject" in {
      error("\"s\" <http://ex/p> <http://ex/o> .").reason shouldBe
        "Expected an IRI or a blank node as the subject"
    }
    "reject a relative datatype IRI" in {
      error("<http://ex/s> <http://ex/p> \"x\"^^<dt> .").reason shouldBe
        "Relative IRIs are not allowed"
    }
    "reject a relative IRI that is only relative after unescaping" in {
      error("<http://ex/s> <http://ex/p> <\\u0061bc> .").reason shouldBe
        "Relative IRIs are not allowed"
    }
    "reject a whitespace before a language tag or datatype" in {
      error("<http://ex/s> <http://ex/p> \"x\" @en .")
      error("<http://ex/s> <http://ex/p> \"x\" ^^<http://ex/dt> .")
    }
    "reject an unterminated IRI or literal" in {
      error("<http://ex/s> <http://ex/p> <http://ex/o").reason shouldBe "Unterminated IRI"
      error("<http://ex/s> <http://ex/p> \"x .").reason shouldBe "Unterminated string literal"
    }
    "reject a code point above U+10FFFF" in {
      error("<http://ex/s> <http://ex/p> \"\\U00110000\" .").reason shouldBe
        "Escape is not a Unicode code point"
    }
    "reject a blank node label starting with '-' or '.'" in {
      error("_:-a <http://ex/p> <http://ex/o> .")
      error("_:.a <http://ex/p> <http://ex/o> .")
    }
    "not push the triples after an error" in {
      val sink = new CollectingRdfSink
      intercept[RdfParseException] {
        NTriplesParser.parse(
          "<http://ex/s> <http://ex/p> <http://ex/o> .\nbad\n<http://ex/s> <http://ex/p> <http://ex/o> .",
          sink,
        )
      }
      sink.triples shouldBe Seq(Triple(s, p, o))
    }
  }
}
