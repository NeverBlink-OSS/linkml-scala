package eu.neverblink.linkml.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class TurtleParserSpec extends AnyWordSpec, Matchers {

  private def cp(c: Int): String = new String(Character.toChars(c))

  private def parse(document: String, baseIri: Option[String] = None): Seq[Triple] =
    RdfUtils.parseTurtle(document, baseIri)

  private def parseBytes(bytes: Array[Byte], bufferSize: Int): Seq[Triple] = {
    val sink = new CollectingRdfSink
    TurtleParser.parse(new ByteArrayInputStream(bytes), sink, bufferSize = bufferSize)
    sink.triples
  }

  private def error(document: String): RdfParseException =
    intercept[RdfParseException](parse(document))

  private val prefixes = "@prefix ex: <http://ex/> .\n@prefix : <http://default/> .\n"

  /** The object of the one triple in `ex:s ex:p $obj .`. */
  private def obj(term: String): Node = {
    val triples = parse(s"$prefixes ex:s ex:p $term .")
    triples.size shouldBe 1
    triples.head.obj
  }

  private val s = Iri("http://ex/s")
  private val p = Iri("http://ex/p")
  private val q = Iri("http://ex/q")
  private val o = Iri("http://ex/o")

  "TurtleParser, on single terms," should {
    "read an IRI" in {
      obj("<http://ex/o>") shouldBe o
    }
    "read prefixed names, including the default prefix and an empty local name" in {
      obj("ex:o") shouldBe o
      obj(":o") shouldBe Iri("http://default/o")
      obj("ex:") shouldBe Iri("http://ex/")
    }
    "read a local name with ':', '-', '.' and a leading digit, but not a trailing '.'" in {
      obj("ex:a:b-c.d") shouldBe Iri("http://ex/a:b-c.d")
      obj("ex:1a") shouldBe Iri("http://ex/1a")
      parse(s"${prefixes}ex:s ex:p ex:o.").head.obj shouldBe o
    }
    "unescape a backslash-escaped local name, but keep percent escapes" in {
      obj("ex:a\\~b\\.") shouldBe Iri("http://ex/a~b.")
      obj("ex:a%20b") shouldBe Iri("http://ex/a%20b")
    }
    "read 'a' as rdf:type, but only as a predicate" in {
      parse(s"$prefixes ex:s a ex:o .").head.pred should be theSameInstanceAs Rdf.`type`
      error(s"$prefixes ex:s ex:p a .").reason shouldBe "Expected an object"
    }
    "read a prefixed name that starts like a keyword" in {
      val doc = "@prefix a: <http://a/> . @prefix true: <http://t/> . a:s a:p true:o ."
      parse(doc) shouldBe Seq(Triple(Iri("http://a/s"), Iri("http://a/p"), Iri("http://t/o")))
    }
    "read a blank node, keeping its label" in {
      obj("_:b0") shouldBe BlankNode("b0")
    }
    "add a '_' to a label starting with '_', to keep clear of the made-up ones" in {
      obj("_:_1") shouldBe BlankNode("__1")
    }
    "read a simple literal in every quoting style, as xsd:string with the shared instance" in {
      for term <- Seq("\"hi\"", "'hi'", "\"\"\"hi\"\"\"", "'''hi'''") do {
        val lit = obj(term).asInstanceOf[Literal]
        lit shouldBe Literal("hi")
        lit.datatype should be theSameInstanceAs XmlSchema.string
      }
    }
    "read empty literals" in {
      for term <- Seq("\"\"", "''", "\"\"\"\"\"\"", "''''''") do obj(term) shouldBe Literal("")
    }
    "read line breaks and lone quotes in a long literal" in {
      obj("\"\"\"a\n\"b\"\"c\r\n\"\"\"") shouldBe Literal("a\n\"b\"\"c\r\n")
      obj("'''it's'''") shouldBe Literal("it's")
    }
    "read a typed literal, with the datatype as an IRI or a prefixed name" in {
      obj("\"42\"^^<http://www.w3.org/2001/XMLSchema#integer>") shouldBe
        Literal("42", XmlSchema.integer)
      obj("\"x\"^^ex:dt") shouldBe Literal("x", Iri("http://ex/dt"))
    }
    "give an explicit xsd:string the shared instance too" in {
      obj("\"hi\"^^<http://www.w3.org/2001/XMLSchema#string>").asInstanceOf[Literal]
        .datatype should be theSameInstanceAs XmlSchema.string
    }
    "read a language literal, keeping the tag's case" in {
      obj("\"bonjour\"@fr-BE") shouldBe LanguageLiteral("bonjour", "fr-BE")
      obj("'''bonjour'''@fr") shouldBe LanguageLiteral("bonjour", "fr")
    }
    "allow whitespace before a language tag and around '^^', as the grammar does" in {
      obj("\"x\" @en") shouldBe LanguageLiteral("x", "en")
      obj("\"x\" ^^ ex:dt") shouldBe Literal("x", Iri("http://ex/dt"))
    }
    "read numbers as written, with their XSD datatype" in {
      obj("42") shouldBe Literal("42", XmlSchema.integer)
      obj("-0042") shouldBe Literal("-0042", XmlSchema.integer)
      obj("+1.50") shouldBe Literal("+1.50", XmlSchema.decimal)
      obj(".5") shouldBe Literal(".5", XmlSchema.decimal)
      obj("1e10") shouldBe Literal("1e10", XmlSchema.double)
      obj("-1.5E-3") shouldBe Literal("-1.5E-3", XmlSchema.double)
      obj("1.e5") shouldBe Literal("1.e5", XmlSchema.double)
      obj(".5e+1") shouldBe Literal(".5e+1", XmlSchema.double)
    }
    "end an integer before a '.' that ends the statement" in {
      parse(s"${prefixes}ex:s ex:p 1.").head.obj shouldBe Literal("1", XmlSchema.integer)
      parse(s"${prefixes}ex:s ex:p 1.5.").head.obj shouldBe Literal("1.5", XmlSchema.decimal)
    }
    "read booleans" in {
      obj("true") shouldBe Literal("true", XmlSchema.boolean)
      obj("false") shouldBe Literal("false", XmlSchema.boolean)
      parse(s"${prefixes}ex:s ex:p true.").head.obj shouldBe Literal("true", XmlSchema.boolean)
    }
  }

  "TurtleParser, on escapes and Unicode," should {
    "unescape every ECHAR" in {
      obj("\"\\t\\b\\n\\r\\f\\\"\\'\\\\\"") shouldBe Literal("\t\b\n\r\f\"'\\")
      obj("'''\\t\\b\\n\\r\\f\\\"\\'\\\\'''") shouldBe Literal("\t\b\n\r\f\"'\\")
    }
    "unescape \\u and \\U, making a surrogate pair for astral characters" in {
      obj("\"caf\\u00E9 \\U0001F600\"") shouldBe Literal("caf" + cp(0xe9) + " " + cp(0x1f600))
    }
    "unescape \\u in IRIs" in {
      obj("<http://ex/caf\\u00e9>") shouldBe Iri("http://ex/caf" + cp(0xe9))
    }
    "accept an escaped character that an IRI cannot contain, as TurtleWriter writes one" in {
      obj("<http://ex/a\\u0020b>") shouldBe Iri("http://ex/a b")
    }
    "decode UTF-8 in literals, IRIs, prefixed names and blank node labels" in {
      val text = "za" + cp(0x17c) + cp(0xf3) + cp(0x142) + cp(0x107) + cp(0x1f600)
      obj("\"" + text + "\"") shouldBe Literal(text)
      obj("'''" + text + "'''") shouldBe Literal(text)
      obj("<http://ex/" + text + ">") shouldBe Iri("http://ex/" + text)
      val name = cp(0xe9) + "t" + cp(0xe9) + cp(0xb7)
      parse(s"@prefix $name: <http://ex/> . $name:$name $name:p _:$name .") shouldBe
        Seq(Triple(Iri("http://ex/" + name), p, BlankNode(name)))
    }
    "accept a lone surrogate from a \\u escape, as the writers write one" in {
      obj("\"\\uD83D\"") shouldBe Literal("\uD83D")
    }
    "reject invalid UTF-8" in {
      val bytes = "<http://ex/s> <http://ex/p> \"x\" .".getBytes(UTF_8)
      bytes(bytes.length - 4) = 0xff.toByte
      intercept[RdfParseException](parseBytes(bytes, 1024)).reason shouldBe "Invalid UTF-8"
    }
  }

  "TurtleParser, on document structure," should {
    "read every triple of a predicate-object list, in order" in {
      parse(s"${prefixes}ex:s ex:p ex:o, \"x\" ; ex:q _:b ; .") shouldBe Seq(
        Triple(s, p, o),
        Triple(s, p, Literal("x")),
        Triple(s, q, BlankNode("b")),
      )
    }
    "accept repeated ';'" in {
      parse(s"${prefixes}ex:s ex:p ex:o ;; ex:q ex:o ;;.") shouldBe
        Seq(Triple(s, p, o), Triple(s, q, o))
    }
    "accept an empty document, and one with only directives and comments" in {
      parse("") shouldBe Nil
      parse(s"$prefixes # nothing\n") shouldBe Nil
    }
    "accept every form of the directives" in {
      val doc =
        "PREFIX ex: <http://ex/>\nprefix e2: <http://ex/>\n@base <http://ex/> .\nBASE <http://ex/>\n" +
          "ex:s e2:p <o> ."
      parse(doc) shouldBe Seq(Triple(s, p, o))
    }
    "use the last declaration of a prefix" in {
      parse("@prefix x: <http://a/> . x:s x:p x:o . @prefix x: <http://ex/> . x:s x:p x:o .")
        .last shouldBe Triple(s, p, o)
    }
    "accept the minimal whitespace the grammar allows" in {
      parse("@prefix ex:<http://ex/>.ex:s ex:p ex:o,ex:o;ex:q[ex:p ex:o].") should have size 4
      parse("<http://ex/s><http://ex/p>\"x\"@en.") shouldBe
        Seq(Triple(s, p, LanguageLiteral("x", "en")))
    }
    "skip comments, but not '#' in IRIs and strings" in {
      parse("# c\n<http://ex/s#a> <http://ex/p> \"#x\" . # c\n# c") shouldBe
        Seq(Triple(Iri("http://ex/s#a"), p, Literal("#x")))
    }
    "end a blank node label before a '.' but keep one inside it" in {
      parse(s"${prefixes}_:a.b ex:p _:c.") shouldBe Seq(Triple(BlankNode("a.b"), p, BlankNode("c")))
    }
    "skip a leading UTF-8 BOM" in {
      parse("\uFEFF<http://ex/s> <http://ex/p> <http://ex/o> .") shouldBe Seq(Triple(s, p, o))
    }
    "not close the input stream" in {
      var closed = false
      val in = new ByteArrayInputStream("<http://ex/s> <http://ex/p> <http://ex/o> .".getBytes) {
        override def close(): Unit = closed = true
      }
      TurtleParser.parse(in, new CollectingRdfSink)
      closed shouldBe false
    }
  }

  "TurtleParser, on blank node property lists and collections," should {
    "push the referencing triple before the nested ones, with an inline blank node" in {
      parse(s"${prefixes}ex:s ex:p [ ex:q [ ex:p ex:o ] ; ex:p ex:o ], [] .") shouldBe Seq(
        Triple(s, p, InlineBlankNode("_1")),
        Triple(InlineBlankNode("_1"), q, InlineBlankNode("_2")),
        Triple(InlineBlankNode("_2"), p, o),
        Triple(InlineBlankNode("_1"), p, o),
        Triple(s, p, InlineBlankNode("_3")),
      )
    }
    "read a property list as the subject, with or without more predicates after it" in {
      parse(s"$prefixes [ ex:p ex:o ] .") shouldBe Seq(Triple(BlankNode("_1"), p, o))
      parse(s"$prefixes [ ex:p ex:o ] ex:q ex:o .") shouldBe
        Seq(Triple(BlankNode("_1"), p, o), Triple(BlankNode("_1"), q, o))
      parse(s"$prefixes [] ex:p ex:o .") shouldBe Seq(Triple(BlankNode("_1"), p, o))
    }
    "push a collection in the same order as RdfSink.list" in {
      val expected = new CollectingRdfSink
      expected.list(s, p, Seq(o, Literal("x"), Literal("1", XmlSchema.integer)))
      RdfUtils.sameUpToBlankNodes(
        parse(s"$prefixes ex:s ex:p ( ex:o 'x' 1 ) ."),
        expected.triples,
      ) shouldBe true
    }
    "read an empty collection as rdf:nil" in {
      obj("()") should be theSameInstanceAs Rdf.nil
      parse(s"$prefixes () ex:p ex:o .") shouldBe Seq(Triple(Rdf.nil, p, o))
    }
    "read nested collections and a collection as the subject" in {
      val a = BlankNode("_1")
      val b = BlankNode("_2")
      val c = BlankNode("_3")
      parse(s"$prefixes ( ( ex:o ) [ ex:p ex:o ] ) ex:p ex:o .") shouldBe Seq(
        Triple(a, Rdf.first, b),
        Triple(b, Rdf.first, o),
        Triple(b, Rdf.rest, Rdf.nil),
        Triple(a, Rdf.rest, c),
        Triple(c, Rdf.first, InlineBlankNode("_4")),
        Triple(InlineBlankNode("_4"), p, o),
        Triple(c, Rdf.rest, Rdf.nil),
        Triple(a, p, o),
      )
    }
    "limit how deep they nest, counting both kinds, as subjects and as objects" in {
      def nested(depth: Int) = "[ <http://ex/p> ( " * (depth / 2) + "1" + " ) ]" * (depth / 2)
      noException should be thrownBy parse(s"${nested(TurtleParser.DefaultMaxNesting)} .")
      val e = error(s"<http://ex/s> <http://ex/p> ${nested(TurtleParser.DefaultMaxNesting + 2)} .")
      e.reason shouldBe "Nesting deeper than 80 levels of '[ ... ]' and '( ... )'"
      e.column shouldBe 28 + 18 * 40 + 1
    }
    "take a different nesting limit" in {
      val sink = new CollectingRdfSink
      val doc = "<http://ex/s> <http://ex/p> " + "( " * 500 + ")" * 500 + " ."
      TurtleParser.parse(
        new ByteArrayInputStream(doc.getBytes(UTF_8)),
        sink,
        maxNesting = 500,
      )
      sink.triples should not be empty
      intercept[RdfParseException](
        TurtleParser.parse(new ByteArrayInputStream(doc.getBytes(UTF_8)), sink, maxNesting = 499),
      )
    }
    "make up labels that do not clash with the document's own" in {
      val triples = parse(s"$prefixes _:_1 ex:p [ ex:p _:_2 ] .")
      triples.map(_.subj.asInstanceOf[AnyBlankNode].id) shouldBe Seq("__1", "_1")
      triples.last.obj shouldBe BlankNode("__2")
    }
  }

  "TurtleParser, on IRI resolution," should {
    val base = Some("http://ex/a/b?q#f")

    "resolve relative IRIs against the base given to it" in {
      parse("<s> <../p> <#o> .", base) shouldBe
        Seq(Triple(Iri("http://ex/a/s"), Iri("http://ex/p"), Iri("http://ex/a/b?q#o")))
    }
    "resolve each base against the one before it" in {
      parse("@base <c/> . BASE <d/> <s> <p> <o> .", base).head.subj shouldBe
        Iri("http://ex/a/c/d/s")
    }
    "resolve prefix IRIs when they are declared" in {
      parse("@prefix x: <x/> . @base <http://other/> . x:s x:p x:o .", base).head.subj shouldBe
        Iri("http://ex/a/x/s")
    }
    "keep absolute IRIs as they are written" in {
      obj("<http://ex/a/../b/./c>") shouldBe Iri("http://ex/a/../b/./c")
    }
    "reject a relative IRI with no base IRI" in {
      val e = error("<http://ex/s> <http://ex/p> <o> .")
      e.reason shouldBe "Relative IRI with no base IRI to resolve it against"
      e.column shouldBe 29
    }
    "reject a base IRI given to it that is not absolute" in {
      intercept[IllegalArgumentException](parse("", Some("a/b")))
    }
  }

  "TurtleParser, on the directives, before and after the first triple," should {
    "pass on the ones before the first triple, resolved, and keep the others to itself" in {
      var directives = Seq.empty[String]
      val sink = new RdfSink {
        def namespace(prefix: String, name: String): Unit = directives :+= s"$prefix: $name"
        override def base(iri: String): Unit = directives :+= s"base $iri"
        def triple(subj: Resource, pred: Iri, obj: Node): Unit = ()
      }
      TurtleParser.parse(
        "BASE <http://ex/> PREFIX x: <x#> <s> <p> <o> . PREFIX y: <y#> BASE <http://other/>",
        sink,
      )
      directives shouldBe Seq("base http://ex/", "x: http://ex/x#")
    }
    "write a document back through TurtleWriter with its prefixes and nesting intact" in {
      val doc =
        """PREFIX ex: <http://ex/>
          |
          |ex:s ex:p [
          |    ex:q ex:o
          |  ] ;
          |  ex:q ex:o .
          |""".stripMargin
      RdfUtils.toTurtle(sink => TurtleParser.parse(doc, sink)) shouldBe doc
    }
  }

  "TurtleParser, reading through a small buffer," should {
    val text = "caf" + cp(0xe9) + cp(0x1f600)
    val document = prefixes + (0 until 200).map { i =>
      s"<http://ex/s$i> ex:p \"$text $i\\n\"@en-GB, '''$text\r\n$i''' ;\r\n  ex:q$text [ ex:p (1 2.5 _:c$i) ] .\n# $text\n"
    }.mkString
    val expected = parse(document)

    "give the same triples for any buffer size, however tokens are cut" in {
      expected.size shouldBe 2000
      for bufferSize <- Seq(16, 17, 31, 64, 100, 1000) do
        withClue(s"bufferSize=$bufferSize: ") {
          parseBytes(document.getBytes(UTF_8), bufferSize) shouldBe expected
        }
    }

    "grow the buffer for a token longer than it" in {
      val long = "x" * 10000
      parseBytes(s"<http://ex/s> <http://ex/p> \"$long\" .".getBytes(UTF_8), 16) shouldBe
        Seq(Triple(s, p, Literal(long)))
    }

    "report the right line and column after many buffer refills" in {
      val bytes = (document + "\n  <http://ex/s> <http://ex/p> " + text + " .").getBytes(UTF_8)
      for bufferSize <- Seq(16, 1000, 100000) do {
        val e = intercept[RdfParseException](parseBytes(bytes, bufferSize))
        (e.line, e.column) shouldBe (2 + 200 * 4 + 2, 31)
      }
    }
  }

  "TurtleParser, on errors," should {
    "report the line and column, counting CRLF as one line end and columns in code points" in {
      val e = error("<http://ex/s> <http://ex/p>\r\n\"" + cp(0x1f600) + "\n\" .")
      e.reason shouldBe "Line breaks are not allowed in a string literal unless it is \"\"\"-quoted"
      (e.line, e.column) shouldBe (2, 3)
    }
    "count the lines inside a long string" in {
      val e = error("<http://ex/s> <http://ex/p> '''a\nb\r\nc\rd''' x .")
      e.reason shouldBe "Expected '.' at the end of the statement"
      (e.line, e.column) shouldBe (4, 6)
    }
    "reject a missing '.'" in {
      error("<http://ex/s> <http://ex/p> <http://ex/o>").reason shouldBe
        "Expected '.' at the end of the statement"
      error("PREFIX ex: <http://ex/> ex:s ex:p ex:o").reason shouldBe
        "Expected '.' at the end of the statement"
    }
    "reject a '.' after a SPARQL-style directive, and none after an @-directive" in {
      error("PREFIX ex: <http://ex/> .").reason shouldBe "Expected a subject"
      error("@prefix ex: <http://ex/>").reason shouldBe "Expected '.' at the end of the directive"
    }
    "reject an undefined prefix" in {
      error("ex:s <http://ex/p> <http://ex/o> .").reason shouldBe "Undefined prefix 'ex:'"
    }
    "reject a literal or blank node as the predicate" in {
      error("<http://ex/s> _:p <http://ex/o> .").reason shouldBe "Expected a predicate"
      error("<http://ex/s> \"p\" <http://ex/o> .").reason shouldBe "Expected a predicate"
    }
    "reject a literal as the subject" in {
      error("\"s\" <http://ex/p> <http://ex/o> .").reason shouldBe "Expected a subject"
    }
    "reject a missing object" in {
      error("<http://ex/s> <http://ex/p> .").reason shouldBe "Expected an object"
      error("<http://ex/s> <http://ex/p> ").reason shouldBe "Expected an object"
    }
    "reject '[]' as a statement on its own" in {
      error("[] .").reason shouldBe "Expected a predicate"
    }
    "reject unterminated terms" in {
      error("<http://ex/s> <http://ex/p> <http://ex/o").reason shouldBe "Unterminated IRI"
      error("<http://ex/s> <http://ex/p> \"x .").reason shouldBe "Unterminated string literal"
      error("<http://ex/s> <http://ex/p> '''x .").reason shouldBe "Unterminated string literal"
      error("<http://ex/s> <http://ex/p> [ <http://ex/p> 1 .").reason shouldBe
        "Expected ']' at the end of the blank node"
      error("<http://ex/s> <http://ex/p> ( 1 ").reason shouldBe
        "Expected ')' at the end of the collection"
    }
    "reject bad escapes" in {
      error("<http://ex/s> <http://ex/p> \"\\a\" .").reason shouldBe "Invalid escape sequence"
      error("<http://ex/s> <http://ex/p\\n> 1 .").reason shouldBe
        "Only \\u and \\U escapes are allowed in an IRI"
      error("@prefix ex: <http://ex/> . ex:s ex:p ex:a\\b .").reason shouldBe
        "Invalid escape sequence in a local name"
      error("@prefix ex: <http://ex/> . ex:s ex:p ex:a%2 .").reason shouldBe
        "Expected two hex digits after '%'"
      error("<http://ex/s> <http://ex/p> \"\\U00110000\" .").reason shouldBe
        "Escape is not a Unicode code point"
    }
    "reject a blank node label starting with '-' or '.'" in {
      error("_:-a <http://ex/p> <http://ex/o> .").reason shouldBe "Invalid blank node label"
      error("_:.a <http://ex/p> <http://ex/o> .").reason shouldBe "Invalid blank node label"
    }
    "reject a prefix name ending with '.'" in {
      error("@prefix ex.: <http://ex/> .").reason shouldBe "A prefix name cannot end with '.'"
    }
    "not push the triples after an error" in {
      val sink = new CollectingRdfSink
      intercept[RdfParseException] {
        TurtleParser.parse(
          "<http://ex/s> <http://ex/p> <http://ex/o> .\nbad\n<http://ex/s> <http://ex/p> <http://ex/o> .",
          sink,
        )
      }
      sink.triples shouldBe Seq(Triple(s, p, o))
    }
  }
}
