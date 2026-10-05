package eu.neverblink.linkml.rdf

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** Streaming RDF 1.1 N-Triples parser. Each triple is pushed into the sink as soon as its line has
  * been read. The parser never calls [[RdfSink.finish]], that is left to the caller.
  *
  * Strict about the grammar (it passes the W3C negative syntax tests), with two exceptions: a
  * leading UTF-8 BOM is skipped, and `\\u` escapes may produce lone surrogates, which
  * [[NTriplesWriter]] writes for strings that contain them.
  *
  * Blank node labels are kept as they are in the document.
  */
object NTriplesParser {
  final val DefaultBufferSize = 64 * 1024

  /** Parse UTF-8 N-Triples from `in`, which is read to the end but not closed. */
  def parse(in: InputStream, sink: RdfSink, bufferSize: Int = DefaultBufferSize): Unit =
    new NTriplesParser(in, sink, bufferSize).run()

  def parse(document: String, sink: RdfSink): Unit =
    parse(new ByteArrayInputStream(document.getBytes(UTF_8)), sink)
}

final class NTriplesParser private (in: InputStream, sink: RdfSink, bufferSize: Int)
    extends RdfParserBase(bufferSize) {
  import RdfSyntax.*

  /** The unparsed data is `buf[pos, limit)`. */
  private var pos = 0
  private var limit = 0
  private var eof = false

  /** Where the current line starts, and its number, for error positions. */
  private var lineStart = 0
  private var line = 0

  /** The parse position within the current line, and where that line ends. */
  private var cur = 0
  private var end = 0

  def run(): Unit = {
    var afterCr = false
    var first = true
    var eol = findEol()
    while (eol >= 0) {
      // The LF of a CRLF shows up as an empty line, which must not count as one.
      if (!(afterCr && eol == pos && buf(pos) == '\n')) line += 1
      if (first) {
        skipBom(eol)
        first = false
      }
      lineStart = pos
      parseLine(pos, eol)
      if (eol < limit) {
        afterCr = buf(eol) == '\r'
        pos = eol + 1
      } else pos = eol
      eol = findEol()
    }
  }

  /** The index of the end of the line starting at [[pos]], reading more input as needed. -1 when
    * the input is exhausted.
    */
  private def findEol(): Int = {
    var i = pos
    while (true) {
      val b = buf
      val lim = limit
      while (i < lim) {
        val c = b(i)
        if (c == '\n' || c == '\r') return i
        i += 1
      }
      if (eof) return if (pos < limit) limit else -1
      i -= pos
      fill()
      i += pos
    }
    -1
  }

  /** Move the unparsed data to the front of the buffer, growing it if it is full, and read more. */
  private def fill(): Unit = {
    val remaining = limit - pos
    if (pos > 0) {
      System.arraycopy(buf, pos, buf, 0, remaining)
      pos = 0
      limit = remaining
    }
    if (limit == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2)
    val n = in.read(buf, limit, buf.length - limit)
    if (n < 0) eof = true else limit += n
  }

  private def skipBom(eol: Int): Unit =
    if (
      eol - pos >= 3 && buf(pos) == 0xef.toByte && buf(pos + 1) == 0xbb.toByte &&
      buf(pos + 2) == 0xbf.toByte
    ) pos += 3

  private def parseLine(start: Int, lineEnd: Int): Unit = {
    end = lineEnd
    cur = start
    skipWs()
    if (cur == end || buf(cur) == '#') return

    val subj: Resource = buf(cur).toChar match {
      case '<' => readIri()
      case '_' => readBlankNode()
      case _ => fail("Expected an IRI or a blank node as the subject")
    }
    skipWs()
    if (cur == end || buf(cur) != '<') fail("Expected an IRI as the predicate")
    val pred = readIri()
    skipWs()
    if (cur == end) fail("Expected an object")
    val obj: Node = buf(cur).toChar match {
      case '<' => readIri()
      case '_' => readBlankNode()
      case '"' => readLiteral()
      case _ => fail("Expected an IRI, a blank node or a literal as the object")
    }
    skipWs()
    if (cur == end || buf(cur) != '.') fail("Expected '.' at the end of the triple")
    cur += 1
    skipWs()
    if (cur != end && buf(cur) != '#') fail("Expected the end of the line after '.'")

    sink.triple(subj, pred, obj)
  }

  private def skipWs(): Unit = {
    val b = buf
    var i = cur
    while (i < end && (b(i) == ' ' || b(i) == '\t')) i += 1
    cur = i
  }

  /** An IRIREF, starting at its `<`. */
  private def readIri(): Iri = {
    val b = buf
    val e = end
    val start = cur + 1
    var i = start
    while (i < e && IriSafe(b(i) & 0xff)) i += 1
    if (i < e && b(i) == '>') {
      val value = new String(b, start, i - start, ISO_8859_1)
      if (!hasScheme(value)) fail("Relative IRIs are not allowed", start)
      cur = i + 1
      new Iri(value)
    } else readIriSlow(start)
  }

  /** An IRI with escapes or non-ASCII characters in it, starting just after its `<`. */
  private def readIriSlow(start: Int): Iri = {
    val b = buf
    val e = end
    nChars = 0
    var i = start
    var done = false
    while (!done) {
      if (i >= e) fail("Unterminated IRI", i)
      val c = b(i) & 0xff
      if (IriSafe(c)) {
        appendChar(c.toChar)
        i += 1
      } else if (c == '>') {
        done = true
        i += 1
      } else if (c == '\\') {
        if (i + 1 < e && (b(i + 1) == 'u' || b(i + 1) == 'U')) i = readUchar(i, e)
        else fail("Only \\u and \\U escapes are allowed in an IRI", i)
      } else if (c >= 0x80) i = readUtf8(i, e)
      else fail(f"Character U+$c%04X is not allowed in an IRI", i)
    }
    val value = new String(chars, 0, nChars)
    if (!hasScheme(value)) fail("Relative IRIs are not allowed", start)
    cur = i
    new Iri(value)
  }

  /** A string literal with its datatype or language tag, starting at its opening quote. */
  private def readLiteral(): Node = {
    val b = buf
    val e = end
    val start = cur + 1
    var i = start
    while (i < e && DoubleQuoteSafe(b(i) & 0xff)) i += 1
    val value =
      if (i < e && b(i) == '"') {
        cur = i + 1
        new String(b, start, i - start, ISO_8859_1)
      } else readStringSlow(start)

    if (cur < e && b(cur) == '@') LanguageLiteral(value, readLanguage())
    else if (cur < e && b(cur) == '^') {
      if (cur + 2 >= e || b(cur + 1) != '^' || b(cur + 2) != '<')
        fail("Expected '^^<' before the datatype")
      cur += 2
      val datatype = readIri()
      Literal(value, if (datatype == XmlSchema.string) XmlSchema.string else datatype)
    } else Literal(value)
  }

  /** The lexical form of a literal with escapes or non-ASCII characters, starting just after its
    * opening quote.
    */
  private def readStringSlow(start: Int): String = {
    val b = buf
    val e = end
    nChars = 0
    var i = start
    var done = false
    while (!done) {
      if (i >= e) fail("Unterminated string literal", i)
      val c = b(i) & 0xff
      if (DoubleQuoteSafe(c)) {
        appendChar(c.toChar)
        i += 1
      } else if (c == '"') {
        done = true
        i += 1
      } else if (c == '\\') {
        if (i + 1 >= e) fail("Unterminated string literal", i + 1)
        i = if (b(i + 1) == 'u' || b(i + 1) == 'U') readUchar(i, e) else readEchar(i)
      } else i = readUtf8(i, e)
    }
    cur = i
    new String(chars, 0, nChars)
  }

  /** `@` followed by `[a-zA-Z]+ ('-' [a-zA-Z0-9]+)*`. */
  private def readLanguage(): String = {
    val b = buf
    val e = end
    val start = cur + 1
    var i = start
    while (i < e && isAsciiLetter(b(i).toChar)) i += 1
    if (i == start) fail("Invalid language tag", start)
    while (i < e && b(i) == '-') {
      val subtag = i + 1
      i = subtag
      while (i < e && (isAsciiLetter(b(i).toChar) || (b(i) >= '0' && b(i) <= '9'))) i += 1
      if (i == subtag) fail("Invalid language tag", subtag)
    }
    cur = i
    language(start, i)
  }

  /** `_:` followed by a label, per BLANK_NODE_LABEL. */
  private def readBlankNode(): BlankNode = {
    val b = buf
    val e = end
    if (cur + 1 >= e || b(cur + 1) != ':') fail("Expected '_:'")
    val start = cur + 2
    if (start >= e || b(start) == '.' || b(start) == '-') fail("Invalid blank node label", start)
    var i = start
    var ascii = true
    var done = false
    while (!done && i < e) {
      val c = b(i) & 0xff
      if (LabelChars(c)) i += 1
      else if (c >= 0x80) {
        val cp = decodeUtf8(i, e)
        val first = i == start
        if (!(if (first) isPnCharsBase(cp) else isPnChars(cp))) done = true
        else {
          ascii = false
          i += utf8Length(c)
        }
      } else done = true
    }
    // The label cannot end with '.', which belongs to the end of the triple instead.
    while (i > start && b(i - 1) == '.') i -= 1
    if (i == start) fail("Invalid blank node label", start)
    cur = i
    new BlankNode(new String(b, start, i - start, if (ascii) ISO_8859_1 else UTF_8))
  }

  private def fail(reason: String, at: Int = cur): Nothing = failAt(reason, at)

  protected def failAt(reason: String, at: Int): Nothing =
    throw new RdfParseException(reason, line, codePoints(lineStart, at) + 1)
}
