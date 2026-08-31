package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.CharSink

/** Streaming N-Triples serializer: one `subject predicate object .` line per triple.
  *
  * In general written to be as simple and fast as possible.
  */
final class NTriplesWriter(out: CharSink) extends RdfSink {

  /** N-Triples has no prefix mechanism, so this is dropped. */
  def namespace(prefix: String, name: String): Unit = ()

  def triple(subj: Resource, pred: Iri, obj: Node): Unit = {
    writeNode(subj)
    out.appendAscii(' ')
    writeNode(pred)
    out.appendAscii(' ')
    writeNode(obj)
    out.append(" .\n")
  }

  /** Write a single node as an N-Triples term. */
  private def writeNode(node: Node): Unit = node match {
    case Iri(value) =>
      out.appendAscii('<')
      NTriplesEscape.escapeIri(out, value)
      out.appendAscii('>')
    // Both blank node flavours are labeled here: only Turtle can inline one.
    case b: AnyBlankNode =>
      out.append("_:")
      out.append(b.id)
    case Literal(value, datatype) =>
      out.appendAscii('"')
      NTriplesEscape.escapeString(out, value)
      out.appendAscii('"')
      // Reference equality because generators use the constant anyway.
      // Equality miss here is safe (still valid RDF)
      if (!(datatype eq XmlSchema.string)) {
        out.append("^^<")
        NTriplesEscape.escapeIri(out, datatype.value)
        out.appendAscii('>')
      }
    case LanguageLiteral(value, languageTag) =>
      out.appendAscii('"')
      NTriplesEscape.escapeString(out, value)
      out.appendAscii('"')
      out.appendAscii('@')
      out.append(languageTag)
  }
}

/** N-Triples escaping, following the RDF Test Cases grammar. Output is always US-ASCII: characters
  * outside the printable ASCII range (and the grammar's disallowed characters) are written as
  * `\\uXXXX` (BMP) or `\\UXXXXXXXX` (supplementary/astral, from surrogate pairs).
  */
private object NTriplesEscape {

  private val Hex: Array[Char] = "0123456789ABCDEF".toCharArray

  /** `Safe(c)` is true for the ASCII characters that may appear verbatim in a string literal's
    * lexical form: printable ASCII (0x20..0x7E) except `"` and `\\`.
    */
  val StringSafe: Array[Boolean] = {
    val a = new Array[Boolean](0x80)
    var c = 0x20
    while (c <= 0x7e) { a(c) = true; c += 1 }
    a('"') = false
    a('\\') = false
    a
  }

  /** `Safe(c)` is true for the ASCII characters that may appear verbatim in an IRIREF: printable
    * ASCII above space (0x21..0x7E) except the delimiters `<>"{}|^`\\` and backtick.
    */
  val IriSafe: Array[Boolean] = {
    val a = new Array[Boolean](0x80)
    var c = 0x21 // space (0x20) is not allowed in an IRIREF
    while (c <= 0x7e) { a(c) = true; c += 1 }
    "<>\"{}|^`\\".foreach(ch => a(ch) = false)
    a
  }

  /** Escape the lexical form of a string literal (the text between the quotes). */
  def escapeString(sink: CharSink, s: String): Unit = {
    val safe = StringSafe
    val len = s.length
    var i = 0
    while (i < len) {
      val c = s.charAt(i)
      if (c < 0x80 && safe(c)) sink.appendAscii(c)
      else
        c match {
          case '\\' => sink.append("\\\\")
          case '"' => sink.append("\\\"")
          case '\n' => sink.append("\\n")
          case '\r' => sink.append("\\r")
          case '\t' => sink.append("\\t")
          case _ => i = escapeHex(sink, s, i, c)
        }
      i += 1
    }
  }

  /** Escape the content of an IRI (the text between the angle brackets). */
  def escapeIri(sink: CharSink, s: String): Unit = {
    val safe = IriSafe
    val len = s.length
    var i = 0
    while (i < len) {
      val c = s.charAt(i)
      if (c < 0x80 && safe(c)) sink.appendAscii(c)
      else i = escapeHex(sink, s, i, c)
      i += 1
    }
  }

  /** Write a `\\uXXXX` or `\\UXXXXXXXX` escape for the character at index `i`. If it is a high
    * surrogate forming a valid pair, the pair is encoded as one `\\U` escape and the index of the
    * consumed low surrogate is returned; otherwise `i` is returned unchanged.
    */
  private def escapeHex(sink: CharSink, s: String, i: Int, c: Char): Int =
    if (
      Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))
    ) {
      sink.append("\\U")
      appendHex(sink, Character.toCodePoint(c, s.charAt(i + 1)), 8)
      i + 1
    } else {
      sink.append("\\u")
      appendHex(sink, c.toInt, 4)
      i
    }

  private def appendHex(sink: CharSink, value: Int, digits: Int): Unit = {
    var shift = (digits - 1) * 4
    while (shift >= 0) {
      sink.appendAscii(Hex((value >>> shift) & 0xf))
      shift -= 4
    }
  }
}
