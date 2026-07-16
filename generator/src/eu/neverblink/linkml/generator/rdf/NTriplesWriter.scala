package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.{CharSink, StringSink}

/** N-Triples serializer that works directly on [[Triple]] instances.
  *
  * In general written to be as simple and fast as possible. Escapes ALL non-ASCII characters.
  */
object NTriplesWriter {

  /** Serialize all [[triples]] to a single N-Triples string. */
  def writeToString(triples: IterableOnce[Triple]): String = {
    val sink = new StringSink
    writeAll(sink, triples)
    sink.result
  }

  /** Format a single node as its N-Triples term (IRI, blank node or literal). */
  def format(node: Node): String = {
    val sink = new StringSink
    writeNode(sink, node)
    sink.result
  }

  /** Write all [[triples]] to [[sink]], one terminated line each. */
  def writeAll(sink: CharSink, triples: IterableOnce[Triple]): Unit = {
    val it = triples.iterator
    while (it.hasNext) writeTriple(sink, it.next())
  }

  /** Write a single triple to [[sink]] as `subject predicate object .` followed by a line feed. */
  def writeTriple(sink: CharSink, triple: Triple): Unit =
    writeTriple(sink, triple.subj, triple.pred, triple.obj)

  /** Write a triple from its components, without materializing a [[Triple]]. */
  def writeTriple(sink: CharSink, subj: Resource, pred: Iri, obj: Node): Unit = {
    writeNode(sink, subj)
    sink.append(' ')
    writeNode(sink, pred)
    sink.append(' ')
    writeNode(sink, obj)
    sink.append(" .\n")
  }

  /** Write a single node to [[sink]] as an N-Triples term. */
  private def writeNode(sink: CharSink, node: Node): Unit = node match {
    case Iri(value) =>
      sink.append('<')
      NTriplesEscape.escapeIri(sink, value)
      sink.append('>')
    case BlankNode(id) =>
      sink.append("_:")
      sink.append(id)
    case Literal(value, datatype) =>
      sink.append('"')
      NTriplesEscape.escapeString(sink, value)
      sink.append('"')
      // Reference equality because generators use the constant anyway.
      // Equality miss here is safe (still valid RDF)
      if (!(datatype eq XmlSchema.string)) {
        sink.append("^^<")
        NTriplesEscape.escapeIri(sink, datatype.value)
        sink.append('>')
      }
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
  private val StringSafe: Array[Boolean] = {
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
  private val IriSafe: Array[Boolean] = {
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
      if (c < 0x80 && safe(c)) sink.append(c)
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
      if (c < 0x80 && safe(c)) sink.append(c)
      else i = escapeHex(sink, s, i, c)
      i += 1
    }
  }

  /** Write a `\\uXXXX` or `\\UXXXXXXXX` escape for the character at index [[i]]. If it is a high
    * surrogate forming a valid pair, the pair is encoded as one `\\U` escape and the index of the
    * consumed low surrogate is returned; otherwise [[i]] is returned unchanged.
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
      sink.append(Hex((value >>> shift) & 0xf))
      shift -= 4
    }
  }
}
