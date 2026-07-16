package eu.neverblink.linkml.generator.rdf

/** N-Triples serializer that works directly on the [[Triple]] model. Modelled on Jena's
  * `WriterStreamRDFPlain` + `NodeFormatterNT`: node formatting and escaping are decoupled from the
  * output sink so the JVM source set can plug in a buffered byte sink for speed while sharing all
  * of the formatting/escaping logic here.
  *
  * The output conforms to the N-Triples grammar of the RDF Test Cases specification
  * (https://www.w3.org/TR/rdf-testcases/#ntriples): angle-bracketed IRIs, `"lexical"^^<datatype>`
  * typed literals, US-ASCII output with numeric escapes for everything else, and one
  * `subject predicate object .` line per triple terminated by a line feed.
  */
object NTriplesWriter {

  /** Serialize all [[triples]] to a single N-Triples string. */
  def writeToString(triples: IterableOnce[Triple]): String = {
    val sink = new StringNTriplesSink
    writeAll(sink, triples)
    sink.result
  }

  /** Format a single node as its N-Triples term (IRI, blank node or literal). */
  def format(node: Node): String = {
    val sink = new StringNTriplesSink
    writeNode(sink, node)
    sink.result
  }

  /** Write all [[triples]] to [[sink]], one terminated line each. */
  def writeAll(sink: NTriplesSink, triples: IterableOnce[Triple]): Unit = {
    val it = triples.iterator
    while (it.hasNext) writeTriple(sink, it.next())
  }

  /** Write a single triple to [[sink]] as `subject predicate object .` followed by a line feed. */
  def writeTriple(sink: NTriplesSink, triple: Triple): Unit = {
    writeNode(sink, triple.subj)
    sink.append(' ')
    writeNode(sink, triple.pred)
    sink.append(' ')
    writeNode(sink, triple.obj)
    sink.append(" .\n")
  }

  /** Write a single node to [[sink]] as an N-Triples term. */
  private def writeNode(sink: NTriplesSink, node: Node): Unit = node match {
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
      sink.append("\"^^<")
      NTriplesEscape.escapeIri(sink, datatype.value)
      sink.append('>')
  }
}

/** N-Triples escaping, following the RDF Test Cases grammar. Output is always US-ASCII: characters
  * outside the printable ASCII range (and the grammar's disallowed characters) are written as
  * `\\uXXXX` (BMP) or `\\UXXXXXXXX` (supplementary/astral, from surrogate pairs).
  *
  * This mirrors Jena's `EscapeStr` (a per-character scan writing runs of safe characters straight
  * to the sink), specialised to the always-ASCII case.
  */
private object NTriplesEscape {

  private val Hex: Array[Char] = "0123456789ABCDEF".toCharArray

  /** Escape the lexical form of a string literal (the text between the quotes). */
  def escapeString(sink: NTriplesSink, s: String): Unit = {
    val len = s.length
    var i = 0
    while (i < len) {
      val c = s.charAt(i)
      if (c == '\\') sink.append("\\\\")
      else if (c == '"') sink.append("\\\"")
      else if (c == '\n') sink.append("\\n")
      else if (c == '\r') sink.append("\\r")
      else if (c == '\t') sink.append("\\t")
      else if (c >= ' ' && c <= '~') sink.append(c) // printable ASCII (0x20..0x7E)
      else i = escapeHex(sink, s, i, c)
      i += 1
    }
  }

  /** Escape the content of an IRI (the text between the angle brackets). */
  def escapeIri(sink: NTriplesSink, s: String): Unit = {
    val len = s.length
    var i = 0
    while (i < len) {
      val c = s.charAt(i)
      if (isIriDisallowed(c)) i = escapeHex(sink, s, i, c)
      else sink.append(c)
      i += 1
    }
  }

  /** Characters not permitted in an N-Triples IRIREF: the delimiters plus anything at or below 0x20
    * or above 0x7E (which must be numerically escaped for US-ASCII output).
    */
  private def isIriDisallowed(c: Char): Boolean = c match {
    case '<' | '>' | '"' | '{' | '}' | '|' | '^' | '`' | '\\' => true
    case _ => c <= ' ' || c > '~'
  }

  /** Write a `\\uXXXX` or `\\UXXXXXXXX` escape for the character at index [[i]]. If it is a high
    * surrogate forming a valid pair, the pair is encoded as one `\\U` escape and the index of the
    * consumed low surrogate is returned; otherwise [[i]] is returned unchanged.
    */
  private def escapeHex(sink: NTriplesSink, s: String, i: Int, c: Char): Int =
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

  private def appendHex(sink: NTriplesSink, value: Int, digits: Int): Unit = {
    var shift = (digits - 1) * 4
    while (shift >= 0) {
      sink.append(Hex((value >>> shift) & 0xf))
      shift -= 4
    }
  }
}
