package eu.neverblink.linkml.rdf

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** A syntax error in an RDF document.
  *
  * @param line
  *   1-based line number
  * @param column
  *   1-based column, in code points
  */
final class RdfParseException(val reason: String, val line: Int, val column: Int)
    extends RuntimeException(s"$reason (line $line, column $column)")

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

  /** ASCII bytes allowed as themselves in an IRIREF: 0x21..0x7E except `<>"{}|^`\\`. */
  private val IriSafe: Array[Boolean] = {
    val a = new Array[Boolean](256)
    var c = 0x21
    while (c <= 0x7e) { a(c) = true; c += 1 }
    "<>\"{}|^`\\".foreach(ch => a(ch) = false)
    a
  }

  /** ASCII bytes allowed as themselves in a string literal: anything but `"`, `\\`, LF and CR. */
  private val StringSafe: Array[Boolean] = {
    val a = new Array[Boolean](256)
    var c = 0
    while (c < 0x80) { a(c) = true; c += 1 }
    "\"\\\n\r".foreach(ch => a(ch) = false)
    a
  }

  /** ASCII bytes in PN_CHARS, plus `.`, which a blank node label may contain but not end with. */
  private val LabelAscii: Array[Boolean] = {
    val a = new Array[Boolean](256)
    ('a' to 'z').foreach(a(_) = true)
    ('A' to 'Z').foreach(a(_) = true)
    ('0' to '9').foreach(a(_) = true)
    "_-.".foreach(a(_) = true)
    a
  }
}

final class NTriplesParser private (in: InputStream, sink: RdfSink, bufferSize: Int) {
  import NTriplesParser.*

  private var buf = new Array[Byte](math.max(bufferSize, 16))

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

  /** Scratch space for the terms that need decoding (escapes or non-ASCII). */
  private var chars = new Array[Char](256)
  private var nChars = 0

  private var lastLanguage = ""

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
      if (!isAbsolute(value)) fail("Relative IRIs are not allowed", start)
      cur = i + 1
      new Iri(value)
    } else readIriSlow(start)
  }

  private def sameAscii(s: String, start: Int, stop: Int): Boolean = {
    var k = stop - start
    if (s.length != k) return false
    val b = buf
    k -= 1
    while (k >= 0) {
      if (s.charAt(k) != b(start + k)) return false
      k -= 1
    }
    true
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
        if (i + 1 < e && (b(i + 1) == 'u' || b(i + 1) == 'U')) i = readUchar(i)
        else fail("Only \\u and \\U escapes are allowed in an IRI", i)
      } else if (c >= 0x80) i = readUtf8(i)
      else fail(f"Character U+$c%04X is not allowed in an IRI", i)
    }
    val value = new String(chars, 0, nChars)
    if (!isAbsolute(value)) fail("Relative IRIs are not allowed", start)
    cur = i
    new Iri(value)
  }

  /** Whether `iri` starts with a scheme: `[A-Za-z][A-Za-z0-9+.-]*:`. */
  private def isAbsolute(iri: String): Boolean = {
    val len = iri.length
    if (len == 0 || !isAsciiLetter(iri.charAt(0))) return false
    var i = 1
    while (i < len) {
      val c = iri.charAt(i)
      if (c == ':') return true
      if (!(isAsciiLetter(c) || (c >= '0' && c <= '9') || c == '+' || c == '-' || c == '.'))
        return false
      i += 1
    }
    false
  }

  private def isAsciiLetter(c: Char): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  /** A string literal with its datatype or language tag, starting at its opening quote. */
  private def readLiteral(): Node = {
    val b = buf
    val e = end
    val start = cur + 1
    var i = start
    while (i < e && StringSafe(b(i) & 0xff)) i += 1
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
      if (StringSafe(c)) {
        appendChar(c.toChar)
        i += 1
      } else if (c == '"') {
        done = true
        i += 1
      } else if (c == '\\') {
        if (i + 1 >= e) fail("Unterminated string literal", i + 1)
        b(i + 1).toChar match {
          case 'u' | 'U' => i = readUchar(i)
          case 't' => appendChar('\t'); i += 2
          case 'b' => appendChar('\b'); i += 2
          case 'n' => appendChar('\n'); i += 2
          case 'r' => appendChar('\r'); i += 2
          case 'f' => appendChar('\f'); i += 2
          case '"' => appendChar('"'); i += 2
          case '\'' => appendChar('\''); i += 2
          case '\\' => appendChar('\\'); i += 2
          case _ => fail("Invalid escape sequence", i)
        }
      } else i = readUtf8(i)
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
    if (sameAscii(lastLanguage, start, i)) lastLanguage
    else {
      lastLanguage = new String(b, start, i - start, ISO_8859_1)
      lastLanguage
    }
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
      if (LabelAscii(c)) i += 1
      else if (c >= 0x80) {
        val cp = decodeUtf8(i)
        val first = i == start
        if (!(if (first) isLabelStart(cp) else isLabelChar(cp))) done = true
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

  /** PN_CHARS_U or a digit, minus the ASCII handled by [[LabelAscii]]. */
  private def isLabelStart(cp: Int): Boolean =
    (cp >= 0xc0 && cp <= 0xd6) || (cp >= 0xd8 && cp <= 0xf6) || (cp >= 0xf8 && cp <= 0x2ff) ||
      (cp >= 0x370 && cp <= 0x37d) || (cp >= 0x37f && cp <= 0x1fff) ||
      (cp >= 0x200c && cp <= 0x200d) || (cp >= 0x2070 && cp <= 0x218f) ||
      (cp >= 0x2c00 && cp <= 0x2fef) || (cp >= 0x3001 && cp <= 0xd7ff) ||
      (cp >= 0xf900 && cp <= 0xfdcf) || (cp >= 0xfdf0 && cp <= 0xfffd) ||
      (cp >= 0x10000 && cp <= 0xeffff)

  /** PN_CHARS, minus the ASCII handled by [[LabelAscii]]. */
  private def isLabelChar(cp: Int): Boolean =
    isLabelStart(cp) || cp == 0xb7 || (cp >= 0x300 && cp <= 0x36f) ||
      (cp >= 0x203f && cp <= 0x2040)

  /** A `\\uXXXX` or `\\UXXXXXXXX` escape at `i`, appended to [[chars]]. Returns the index after it.
    */
  private def readUchar(i: Int): Int = {
    val digits = if (buf(i + 1) == 'u') 4 else 8
    if (i + 2 + digits > end) fail("Truncated \\u escape", i)
    var cp = 0
    var k = i + 2
    while (k < i + 2 + digits) {
      val d = Character.digit(buf(k).toChar, 16)
      if (d < 0) fail("Invalid hex digit in \\u escape", k)
      cp = (cp << 4) | d
      k += 1
    }
    if (cp < 0 || cp > Character.MAX_CODE_POINT) fail("Escape is not a Unicode code point", i)
    appendCodePoint(cp)
    k
  }

  /** The multi-byte UTF-8 sequence at `i`, appended to [[chars]]. Returns the index after it. */
  private def readUtf8(i: Int): Int = {
    appendCodePoint(decodeUtf8(i))
    i + utf8Length(buf(i) & 0xff)
  }

  private def utf8Length(lead: Int): Int =
    if (lead < 0xe0) 2 else if (lead < 0xf0) 3 else 4

  /** Decode the multi-byte UTF-8 sequence at `i`, rejecting anything malformed. */
  private def decodeUtf8(i: Int): Int = {
    val b = buf
    val lead = b(i) & 0xff
    val len =
      if (lead >= 0xc2 && lead <= 0xdf) 2
      else if (lead >= 0xe0 && lead <= 0xef) 3
      else if (lead >= 0xf0 && lead <= 0xf4) 4
      else fail("Invalid UTF-8", i)
    if (i + len > end) fail("Invalid UTF-8", i)
    var cp = lead & (0x7f >> len)
    var k = 1
    while (k < len) {
      val cont = b(i + k) & 0xff
      if ((cont & 0xc0) != 0x80) fail("Invalid UTF-8", i)
      cp = (cp << 6) | (cont & 0x3f)
      k += 1
    }
    val overlong = (len == 3 && cp < 0x800) || (len == 4 && cp < 0x10000)
    if (overlong || cp > Character.MAX_CODE_POINT || (cp >= 0xd800 && cp <= 0xdfff))
      fail("Invalid UTF-8", i)
    cp
  }

  private def appendCodePoint(cp: Int): Unit =
    if (cp < 0x10000) appendChar(cp.toChar)
    else {
      appendChar(Character.highSurrogate(cp))
      appendChar(Character.lowSurrogate(cp))
    }

  private def appendChar(c: Char): Unit = {
    if (nChars == chars.length) chars = java.util.Arrays.copyOf(chars, nChars * 2)
    chars(nChars) = c
    nChars += 1
  }

  private def fail(reason: String, at: Int = cur): Nothing = {
    // Count code points, not bytes: skip UTF-8 continuation bytes.
    var column = 1
    var i = lineStart
    while (i < at) {
      if ((buf(i) & 0xc0) != 0x80) column += 1
      i += 1
    }
    throw new RdfParseException(reason, line, column)
  }
}
