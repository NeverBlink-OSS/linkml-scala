package eu.neverblink.linkml.generator.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Serialization tests for [[TurtleWriter]]. See also `TurtleW3cSpec` for conformance tests.
  */
class TurtleWriterSpec extends AnyWordSpec, Matchers {

  private val ex = "http://example.org/"
  private def iri(local: String): Iri = Iri(ex + local)
  private val s = iri("s")
  private val p = iri("p")
  private val o = iri("o")

  private def turtle(write: RdfSink => Unit): String = RdfUtils.toTurtle(write)

  "TurtleWriter" should {
    "write nothing for an empty document" in {
      turtle(_ => ()) shouldBe ""
    }

    "write the directives even when no triple follows" in {
      turtle(_.namespace("ex", ex)) shouldBe "PREFIX ex: <http://example.org/>\n"
    }

    "write a lone triple with full IRIs" in {
      turtle(_.triple(s, p, o)) shouldBe
        "<http://example.org/s> <http://example.org/p> <http://example.org/o> .\n"
    }

    "separate statements with a blank line" in {
      turtle { sink =>
        sink.triple(s, p, o)
        sink.triple(o, p, s)
      } shouldBe
        """<http://example.org/s> <http://example.org/p> <http://example.org/o> .
          |
          |<http://example.org/o> <http://example.org/p> <http://example.org/s> .
          |""".stripMargin
    }
  }

  "TurtleWriter, on prefixes," should {
    "shorten IRIs whose namespace was declared" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, p, o)
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p ex:o .
          |""".stripMargin
    }

    "keep the declaration order of the prefixes" in {
      turtle { sink =>
        sink.namespace("z", "http://z.example/")
        sink.namespace("a", "http://a.example/")
      } shouldBe
        """PREFIX z: <http://z.example/>
          |PREFIX a: <http://a.example/>
          |""".stripMargin
    }

    "shorten against the longest matching namespace" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.namespace("sub", ex + "sub/")
        sink.triple(iri("sub/s"), p, iri("x"))
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |PREFIX sub: <http://example.org/sub/>
          |
          |sub:s ex:p ex:x .
          |""".stripMargin
    }

    "accept the default (empty) prefix" in {
      turtle { sink =>
        sink.namespace("", ex)
        sink.triple(s, p, o)
      } shouldBe
        """PREFIX : <http://example.org/>
          |
          |:s :p :o .
          |""".stripMargin
    }

    "write an IRI in full when its local part cannot be a prefixed name" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        // A local part may not start with a hyphen, end with a dot, or hold a percent escape.
        sink.triple(iri("-x"), iri("x."), iri("%20"))
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |<http://example.org/-x> <http://example.org/x.> <http://example.org/%20> .
          |""".stripMargin
    }

    "shorten an IRI that is exactly a namespace" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(Iri(ex), p, o)
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex: ex:p ex:o .
          |""".stripMargin
    }

    "drop a prefix that cannot be written as one, rather than emit a broken directive" in {
      turtle { sink =>
        sink.namespace("1bad", ex)
        sink.triple(s, p, o)
      } shouldBe
        "<http://example.org/s> <http://example.org/p> <http://example.org/o> .\n"
    }

    "let a repeated prefix take the new namespace in its original place" in {
      turtle { sink =>
        sink.namespace("a", "http://a.example/")
        sink.namespace("b", "http://b.example/")
        sink.namespace("a", ex)
        sink.triple(s, p, o)
      } shouldBe
        """PREFIX a: <http://example.org/>
          |PREFIX b: <http://b.example/>
          |
          |a:s a:p a:o .
          |""".stripMargin
    }

    "escape what a namespace IRI cannot hold verbatim" in {
      turtle(_.namespace("ex", "http://example.org/a b/")) shouldBe
        "PREFIX ex: <http://example.org/a\\u0020b/>\n"
    }

    "refuse a prefix declared after the first triple" in {
      val thrown = intercept[IllegalStateException] {
        turtle { sink =>
          sink.triple(s, p, o)
          sink.namespace("ex", ex)
        }
      }
      thrown.getMessage should include("Turtle directives come first")
    }
  }

  "TurtleWriter, on the base IRI," should {
    "declare it ahead of the prefixes" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.base(ex)
        sink.triple(s, p, o)
      } shouldBe
        """BASE <http://example.org/>
          |PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p ex:o .
          |""".stripMargin
    }

    "not shorten IRIs against it" in {
      turtle { sink =>
        sink.base(ex)
        sink.triple(s, p, o)
      } shouldBe
        """BASE <http://example.org/>
          |
          |<http://example.org/s> <http://example.org/p> <http://example.org/o> .
          |""".stripMargin
    }
  }

  "TurtleWriter, on predicate and object lists," should {
    "write `a` for rdf:type in the predicate position" in {
      turtle(_.triple(s, Rdf.`type`, o)) shouldBe
        "<http://example.org/s> a <http://example.org/o> .\n"
    }

    "write rdf:type in full anywhere else" in {
      turtle(_.triple(s, p, Rdf.`type`)) shouldBe
        "<http://example.org/s> <http://example.org/p> " +
        "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> .\n"
    }

    "join a repeated subject with a semicolon" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, iri("p1"), o)
        sink.triple(s, iri("p2"), o)
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p1 ex:o ;
          |  ex:p2 ex:o .
          |""".stripMargin
    }

    "join a repeated subject and predicate with a comma" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, p, iri("o1"))
        sink.triple(s, p, iri("o2"))
        sink.triple(s, iri("q"), iri("o3"))
        sink.triple(s, iri("q"), iri("o4"))
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p ex:o1 , ex:o2 ;
          |  ex:q ex:o3 , ex:o4 .
          |""".stripMargin
    }

    "start a new statement when the predicate comes back after another one" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, iri("p1"), iri("o1"))
        sink.triple(s, iri("p2"), iri("o2"))
        sink.triple(s, iri("p1"), iri("o3"))
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p1 ex:o1 ;
          |  ex:p2 ex:o2 ;
          |  ex:p1 ex:o3 .
          |""".stripMargin
    }
  }

  "TurtleWriter, on blank nodes," should {
    "label a plain blank node" in {
      turtle(_.triple(BlankNode("b0"), p, BlankNode("b1"))) shouldBe
        "_:b0 <http://example.org/p> _:b1 .\n"
    }

    "inline a blank node whose triples follow it" in {
      val b = InlineBlankNode("1")
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, p, b)
        sink.triple(b, iri("q1"), iri("o1"))
        sink.triple(b, iri("q2"), iri("o2"))
        sink.triple(s, iri("p2"), iri("o3"))
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p [
          |    ex:q1 ex:o1 ;
          |    ex:q2 ex:o2
          |  ] ;
          |  ex:p2 ex:o3 .
          |""".stripMargin
    }

    "write an inline blank node with no triples of its own as []" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, p, InlineBlankNode("1"))
        sink.triple(s, iri("p2"), o)
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p [] ;
          |  ex:p2 ex:o .
          |""".stripMargin
    }

    "nest inline blank nodes" in {
      val outer = InlineBlankNode("1")
      val inner = InlineBlankNode("2")
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, p, outer)
        sink.triple(outer, iri("q"), inner)
        sink.triple(inner, iri("r"), o)
        sink.triple(outer, iri("q2"), o)
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p [
          |    ex:q [
          |      ex:r ex:o
          |    ] ;
          |    ex:q2 ex:o
          |  ] .
          |""".stripMargin
    }

    "close an inline blank node when the whole document ends" in {
      val b = InlineBlankNode("1")
      turtle { sink =>
        sink.triple(s, p, b)
        sink.triple(b, p, o)
      } shouldBe
        """<http://example.org/s> <http://example.org/p> [
          |    <http://example.org/p> <http://example.org/o>
          |  ] .
          |""".stripMargin
    }

    // The frame stack starts at 8 and doubles, so this is the only test that grows it.
    "nest inline blank nodes deeper than the initial frame stack" in {
      val depth = 20
      val nodes = (1 to depth).map(i => InlineBlankNode(i.toString))
      val out = turtle { sink =>
        sink.triple(s, p, nodes.head)
        nodes.sliding(2).foreach(pair => sink.triple(pair.head, p, pair.last))
        sink.triple(nodes.last, p, o)
      }
      // One `[` per level, all still open when the innermost triple is written, then one `]` each.
      out.count(_ == '[') shouldBe depth
      out.count(_ == ']') shouldBe depth
      out should include("<http://example.org/o>")
      out should endWith("] .\n")
      // The innermost object sits one indent step below the deepest `[`.
      out should include(
        s"\n${" " * (2 * (depth + 1))}<http://example.org/p> <http://example.org/o>",
      )
    }

    "put two inline blank nodes under one predicate in an object list" in {
      val b1 = InlineBlankNode("1")
      val b2 = InlineBlankNode("2")
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, p, b1)
        sink.triple(b1, iri("q"), iri("o1"))
        sink.triple(s, p, b2)
        sink.triple(b2, iri("q"), iri("o2"))
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p [
          |    ex:q ex:o1
          |  ] , [
          |    ex:q ex:o2
          |  ] .
          |""".stripMargin
    }

    "refuse an inline blank node whose triples do not follow its reference" in {
      val b = InlineBlankNode("1")
      val thrown = intercept[IllegalStateException] {
        turtle { sink =>
          sink.triple(s, p, b)
          sink.triple(o, p, o) // some other subject in between
          sink.triple(b, p, o)
        }
      }
      thrown.getMessage should include("_:1 is not open")
    }

    "refuse an inline blank node that was never referenced" in {
      intercept[IllegalStateException] {
        turtle(_.triple(InlineBlankNode("1"), p, o))
      }.getMessage should include("_:1 is not open")
    }

    "refuse an inline blank node used as an object twice" in {
      val b = InlineBlankNode("1")
      intercept[IllegalStateException] {
        turtle { sink =>
          sink.triple(s, p, b)
          sink.triple(b, iri("q"), b)
        }
      }.getMessage should include("referenced twice")
    }
  }

  "TurtleWriter, on RDF lists," should {
    "write a collection" in {
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.list(s, p, Seq(iri("a"), iri("b"), Literal("1", XmlSchema.integer)))
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p ( ex:a ex:b 1 ) .
          |""".stripMargin
    }

    "write an empty collection" in {
      turtle(_.list(s, p, Nil)) shouldBe
        "<http://example.org/s> <http://example.org/p> () .\n"
    }

    "write a collection inside an inline blank node" in {
      val b = InlineBlankNode("1")
      turtle { sink =>
        sink.namespace("ex", ex)
        sink.triple(s, p, b)
        sink.list(b, iri("q"), Seq(iri("a")))
        sink.triple(b, iri("r"), o)
      } shouldBe
        """PREFIX ex: <http://example.org/>
          |
          |ex:s ex:p [
          |    ex:q ( ex:a ) ;
          |    ex:r ex:o
          |  ] .
          |""".stripMargin
    }

    "refuse an inline blank node as a list member" in {
      intercept[IllegalArgumentException] {
        turtle(_.list(s, p, Seq(InlineBlankNode("1"))))
      }.getMessage should include("cannot be a member of an RDF list")
    }

    "expand a list into rdf:first / rdf:rest for sinks that cannot write collections" in {
      val sink = new CollectingRdfSink
      sink.list(s, p, Seq(iri("a"), iri("b")))
      sink.triples shouldBe Seq(
        Triple(s, p, BlankNode("l1")),
        Triple(BlankNode("l1"), Rdf.first, iri("a")),
        Triple(BlankNode("l1"), Rdf.rest, BlankNode("l2")),
        Triple(BlankNode("l2"), Rdf.first, iri("b")),
        Triple(BlankNode("l2"), Rdf.rest, Rdf.nil),
      )
    }

    "expand an empty list to rdf:nil for sinks that cannot write collections" in {
      val sink = new CollectingRdfSink
      sink.list(s, p, Nil)
      sink.triples shouldBe Seq(Triple(s, p, Rdf.nil))
    }
  }

  "TurtleWriter, on literals," should {
    "write a plain string without a datatype" in {
      turtle(_.triple(s, p, Literal("hello"))) shouldBe
        "<http://example.org/s> <http://example.org/p> \"hello\" .\n"
    }

    "write xsd:string the same way, however the datatype was built" in {
      turtle(_.triple(s, p, Literal("hello", Iri(XmlSchema.string.value)))) shouldBe
        "<http://example.org/s> <http://example.org/p> \"hello\" .\n"
    }

    "write a language literal" in {
      turtle(_.triple(s, p, LanguageLiteral("bonjour", "fr-BE"))) shouldBe
        "<http://example.org/s> <http://example.org/p> \"bonjour\"@fr-BE .\n"
    }

    "keep the datatype of anything else" in {
      turtle { sink =>
        sink.namespace("xsd", "http://www.w3.org/2001/XMLSchema#")
        sink.triple(s, p, Literal("2024-01-01", XmlSchema.date))
      } shouldBe
        """PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
          |
          |<http://example.org/s> <http://example.org/p> "2024-01-01"^^xsd:date .
          |""".stripMargin
    }

    "abbreviate the XSD literals that are already Turtle tokens" in {
      val bare = Seq(
        Literal("1", XmlSchema.integer) -> "1",
        Literal("-42", XmlSchema.integer) -> "-42",
        Literal("+7", XmlSchema.integer) -> "+7",
        Literal("0.5", XmlSchema.decimal) -> "0.5",
        Literal("-.5", XmlSchema.decimal) -> "-.5",
        Literal("1.0e6", XmlSchema.double) -> "1.0e6",
        Literal("-1E-6", XmlSchema.double) -> "-1E-6",
        Literal("true", XmlSchema.boolean) -> "true",
        Literal("false", XmlSchema.boolean) -> "false",
      )
      for (literal, expected) <- bare do
        withClue(literal) {
          turtle(_.triple(s, p, literal)) shouldBe
            s"<http://example.org/s> <http://example.org/p> $expected .\n"
        }
    }

    "keep the quotes when a lexical form is not already a Turtle token" in {
      val quoted = Seq(
        // Reading `5` back would give an xsd:integer, and `1.0` an xsd:decimal.
        Literal("5", XmlSchema.decimal) -> "\"5\"^^<http://www.w3.org/2001/XMLSchema#decimal>",
        Literal("1.0", XmlSchema.double) -> "\"1.0\"^^<http://www.w3.org/2001/XMLSchema#double>",
        Literal("1.5", XmlSchema.integer) -> "\"1.5\"^^<http://www.w3.org/2001/XMLSchema#integer>",
        Literal(" 1", XmlSchema.integer) -> "\" 1\"^^<http://www.w3.org/2001/XMLSchema#integer>",
        Literal("", XmlSchema.integer) -> "\"\"^^<http://www.w3.org/2001/XMLSchema#integer>",
        Literal("1", XmlSchema.boolean) -> "\"1\"^^<http://www.w3.org/2001/XMLSchema#boolean>",
        Literal("TRUE", XmlSchema.boolean) -> "\"TRUE\"^^<http://www.w3.org/2001/XMLSchema#boolean>",
      )
      for (literal, expected) <- quoted do
        withClue(literal) {
          turtle(_.triple(s, p, literal)) shouldBe
            s"<http://example.org/s> <http://example.org/p> $expected .\n"
        }
    }
  }

  "TurtleWriter, on escaping," should {
    def literal(value: String): String = {
      val out = turtle(_.triple(s, p, Literal(value)))
      out.stripPrefix("<http://example.org/s> <http://example.org/p> ").stripSuffix(" .\n")
    }

    "escape quotes and backslashes" in {
      literal("a\"b\\c") shouldBe "\"a\\\"b\\\\c\""
    }

    "escape tabs and carriage returns" in {
      literal("a\tb\rc") shouldBe "\"a\\tb\\rc\""
    }

    "escape other control characters as \\u" in {
      literal("a" + 0.toChar + "b" + 0x1f.toChar + "c") shouldBe "\"a\\u0000b\\u001Fc\""
    }

    "write non-ASCII characters as themselves" in {
      literal("zażółć gęślą jaźń – ☃ – 😀") shouldBe
        "\"zażółć gęślą jaźń – ☃ – 😀\""
    }

    "use the long form for a value spanning lines" in {
      literal("first\nsecond") shouldBe "\"\"\"first\nsecond\"\"\""
    }

    "escape every quote inside the long form" in {
      literal("a\nsaid \"hi\"\"\"") shouldBe "\"\"\"a\nsaid \\\"hi\\\"\\\"\\\"\"\"\""
    }

    "keep escaping carriage returns inside the long form" in {
      literal("a\nb\r\nc") shouldBe "\"\"\"a\nb\\r\nc\"\"\""
    }

    "escape what an IRI cannot hold verbatim" in {
      val odd = "http://example.org/a b<c>d" + 1.toChar
      turtle(_.triple(Iri(odd), p, o)) shouldBe
        "<http://example.org/a\\u0020b\\u003Cc\\u003Ed\\u0001> <http://example.org/p> " +
        "<http://example.org/o> .\n"
    }

    // An IRIREF admits UCHAR and nothing else, so the `\"` and `\n` a string literal would use
    // are a syntax error between angle brackets.
    "never use the string-literal escapes inside an IRI" in {
      val odd = "http://example.org/" + "\" \\ \t \n \r".filterNot(_ == ' ')
      turtle(_.triple(Iri(odd), p, o)) shouldBe
        "<http://example.org/\\u0022\\u005C\\u0009\\u000A\\u000D> <http://example.org/p> " +
        "<http://example.org/o> .\n"
    }

    "never use the string-literal escapes inside a namespace directive" in {
      turtle(_.namespace("ex", "http://example.org/a\"b\\c/")) shouldBe
        "PREFIX ex: <http://example.org/a\\u0022b\\u005Cc/>\n"
    }
  }
}
