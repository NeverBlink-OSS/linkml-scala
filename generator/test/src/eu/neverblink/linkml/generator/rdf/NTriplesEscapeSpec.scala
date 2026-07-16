package eu.neverblink.linkml.generator.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Escaping tests for [[NTriplesEscape]], per the N-Triples string/IRI productions of the RDF Test
  * Cases spec (https://www.w3.org/TR/rdf-testcases/#ntriples). Cross-platform: run on the JVM and
  * Scala.js.
  *
  * Special/non-ASCII inputs are built from code points via [[cp]] so the source file stays pure
  * ASCII and unambiguous.
  */
class NTriplesEscapeSpec extends AnyWordSpec, Matchers {

  /** The string consisting of the single Unicode code point [[c]]. */
  private def cp(c: Int): String = new String(Character.toChars(c))

  private def escStr(s: String): String = {
    val sink = new StringNTriplesSink
    NTriplesEscape.escapeString(sink, s)
    sink.result
  }

  private def escIri(s: String): String = {
    val sink = new StringNTriplesSink
    NTriplesEscape.escapeIri(sink, s)
    sink.result
  }

  "String escaping" should {
    "pass through printable ASCII unchanged" in {
      escStr("Hello, world! 123 ~") shouldBe "Hello, world! 123 ~"
    }

    "escape the special characters with short escapes" in {
      escStr("a\\b") shouldBe "a\\\\b" // backslash -> \\
      escStr("a\"b") shouldBe "a\\\"b" // quote     -> \"
      escStr("a\nb") shouldBe "a\\nb" //  LF        -> \n
      escStr("a\rb") shouldBe "a\\rb" //  CR        -> \r
      escStr("a\tb") shouldBe "a\\tb" //  TAB       -> \t
    }

    "escape other C0 control characters as \\uXXXX (uppercase, 4 digits)" in {
      escStr(cp(0x00)) shouldBe "\\u0000"
      escStr(cp(0x07)) shouldBe "\\u0007" // BEL
      escStr(cp(0x08)) shouldBe "\\u0008" // BS  (no short escape in N-Triples)
      escStr(cp(0x0b)) shouldBe "\\u000B" // VT
      escStr(cp(0x0c)) shouldBe "\\u000C" // FF  (no short escape in N-Triples)
      escStr(cp(0x1f)) shouldBe "\\u001F"
    }

    "escape DEL and non-ASCII BMP characters as \\uXXXX" in {
      escStr(cp(0x7f)) shouldBe "\\u007F" // DEL
      escStr(cp(0xe9)) shouldBe "\\u00E9" // e-acute
      escStr(cp(0xf6)) shouldBe "\\u00F6" // o-umlaut
      escStr(cp(0x20ac)) shouldBe "\\u20AC" // euro sign
    }

    "escape supplementary (astral) characters as a single \\UXXXXXXXX from the surrogate pair" in {
      escStr(cp(0x1f600)) shouldBe "\\U0001F600" // grinning face
      escStr("a" + cp(0x1f600) + "b") shouldBe "a\\U0001F600b"
    }

    "escape a lone surrogate as \\uXXXX rather than crashing" in {
      escStr("\uD83D") shouldBe "\\uD83D" // high surrogate with no following low surrogate
    }

    "always produce US-ASCII output" in {
      escStr("a" + cp(0xe9) + cp(0x1f600) + " z").forall(_ < 0x80) shouldBe true
    }
  }

  "IRI escaping" should {
    "pass through a normal IRI unchanged" in {
      escIri("http://example.org/path#frag") shouldBe "http://example.org/path#frag"
    }

    "escape the IRIREF-disallowed delimiter characters" in {
      escIri("a b") shouldBe "a\\u0020b" // space
      escIri("a<b") shouldBe "a\\u003Cb"
      escIri("a>b") shouldBe "a\\u003Eb"
      escIri("a\"b") shouldBe "a\\u0022b"
      escIri("a\\b") shouldBe "a\\u005Cb"
      escIri("a{b}c") shouldBe "a\\u007Bb\\u007Dc"
      escIri("a|b") shouldBe "a\\u007Cb"
      escIri("a^b") shouldBe "a\\u005Eb"
      escIri("a`b") shouldBe "a\\u0060b"
    }

    "escape control and non-ASCII characters" in {
      escIri("a\nb") shouldBe "a\\u000Ab"
      escIri("caf" + cp(0xe9)) shouldBe "caf\\u00E9"
      escIri("x" + cp(0x1f600) + "y") shouldBe "x\\U0001F600y"
    }
  }
}
