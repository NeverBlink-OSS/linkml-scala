package eu.neverblink.linkml.rdf

import java.io.{ByteArrayInputStream, InputStream}
import java.nio.charset.StandardCharsets.{ISO_8859_1, UTF_8}

/** Streaming RDF 1.1 Turtle parser. Each triple is pushed into the sink as soon as it has been
  * read, in document order. The parser never calls [[RdfSink.finish]], that is left to the caller.
  *
  * Strict about the grammar (it passes the W3C negative syntax tests), with the exceptions of
  * [[NTriplesParser]]: a leading UTF-8 BOM is skipped, `\\u` escapes may produce lone surrogates,
  * and a `\\u` escape in an IRI may produce a character that IRIs cannot contain, such as a space.
  *
  * Relative IRIs are resolved against the base IRI (RFC 3986 section 5.2).
  *
  * Blank nodes:
  *   - Labels are kept as they are in the document, except ones that start with `_`, which get a
  *     second `_` in front.
  *   - `[ ... ]` and collections get labels `_1`, `_2` and so on.
  *   - A `[ ... ]` object is an [[InlineBlankNode]], and the triple that references it is pushed
  *     before its own triples, so a [[TurtleWriter]] writes it back nested.
  *   - A collection is pushed as `rdf:first` / `rdf:rest` triples, in the order that
  *     [[RdfSink.list]] uses.
  */
object TurtleParser {
  import RdfSyntax.*

  final val DefaultBufferSize = 64 * 1024

  /** Far deeper than real documents nest, and far shallower than any platform's stack allows. */
  final val DefaultMaxNesting = 80

  /** Parse UTF-8 Turtle from `in`. Does not close the input stream.
    *
    * @param baseIri
    *   the absolute IRI to resolve relative IRIs against, until the document sets its own
    * @param maxNesting
    *   how deep `[ ... ]` and collections may nest
    */
  def parse(
      in: InputStream,
      sink: RdfSink,
      baseIri: Option[String] = None,
      bufferSize: Int = DefaultBufferSize,
      maxNesting: Int = DefaultMaxNesting,
  ): Unit =
    new TurtleParser(in, sink, baseIri.fold[BaseIri](null)(BaseIri(_)), bufferSize, maxNesting)
      .run()

  def parse(document: String, sink: RdfSink): Unit = parse(document, sink, None)

  def parse(document: String, sink: RdfSink, baseIri: Option[String]): Unit =
    parse(new ByteArrayInputStream(document.getBytes(UTF_8)), sink, baseIri)

  private val True = Literal("true", XmlSchema.boolean)
  private val False = Literal("false", XmlSchema.boolean)

  /** ASCII bytes allowed as themselves in a short string quoted with `'`. */
  private val SingleQuoteSafe = table((0 until 0x80).filterNot("'\\\n\r".contains(_)))

  /** ASCII PN_CHARS, the characters of names after the first. */
  private val PnChars = table(Letters ++ Digits ++ "_-".map(_.toInt))

  /** ASCII bytes that may start a PN_LOCAL without an escape. */
  private val LocalStart = table(Letters ++ Digits ++ "_:".map(_.toInt))

  /** ASCII bytes that may continue a PN_LOCAL without an escape. A trailing `.` is not part of it.
    */
  private val LocalChars = table(Letters ++ Digits ++ "_-:.".map(_.toInt))

  /** The characters that PN_LOCAL_ESC lets a backslash escape. */
  private val LocalEscapes = table("_~.-!$&'()*+,;=/?#@%".map(_.toInt))
}

final class TurtleParser private (
    in: InputStream,
    sink: RdfSink,
    private var base: BaseIri,
    bufferSize: Int,
    maxNesting: Int,
) extends RdfParserBase(bufferSize) {
  import RdfSyntax.*
  import TurtleParser.*

  /** The unread data is `buf[pos, limit)`. A token is scanned at offsets relative to [[pos]], which
    * stays at its start until it is done, so that the token is never moved out of the buffer.
    */
  private var pos = 0
  private var limit = 0
  private var eof = false

  /** For error positions: the current line, where it starts in [[buf]], and the number of code
    * points it had before that, if its start has been moved out of the buffer.
    */
  private var line = 1
  private var lineStart = 0
  private var lineColumns = 0

  /** Declared prefixes, as UTF-8, with their namespaces at the same index. */
  private var prefixNames = new Array[Array[Byte]](16)
  private var prefixIris = new Array[String](16)
  private var nPrefixes = 0
  private var lastPrefix = -1

  private var blankNodes = 0
  private var sawTriple = false

  /** How many `[ ... ]` and collections the parser is inside of. */
  private var nesting = 0

  def run(): Unit = {
    if (avail(2) && at(0) == 0xef && at(1) == 0xbb && at(2) == 0xbf) {
      pos = 3
      lineStart = 3
    }
    skipWs()
    while (avail(0)) {
      statement()
      skipWs()
    }
  }

  // Input

  /** Whether the byte at offset `k` from [[pos]] is available, reading more input as needed. */
  private def avail(k: Int): Boolean = pos + k < limit || more(k)

  private def more(k: Int): Boolean = {
    while (pos + k >= limit) {
      if (eof) return false
      fill()
    }
    true
  }

  /** The byte at offset `k` from [[pos]], which must be available. */
  private def at(k: Int): Int = buf(pos + k) & 0xff

  /** Move the unread data to the front of the buffer, growing it if it is full, and read more. */
  private def fill(): Unit = {
    if (pos > 0) {
      if (lineStart < pos) {
        lineColumns += codePoints(lineStart, pos)
        lineStart = pos
      }
      val remaining = limit - pos
      System.arraycopy(buf, pos, buf, 0, remaining)
      lineStart -= pos
      limit = remaining
      pos = 0
    }
    if (limit == buf.length) buf = java.util.Arrays.copyOf(buf, buf.length * 2)
    val n = in.read(buf, limit, buf.length - limit)
    if (n < 0) eof = true else limit += n
  }

  /** The offset of the first byte from offset `k` on that `table` does not allow. */
  private def scan(table: Array[Boolean], k: Int): Int = {
    var i = k
    while (true) {
      val b = buf
      val p = pos
      val n = limit - p
      while (i < n && table(b(p + i) & 0xff)) i += 1
      if (i < n || !more(i)) return i
    }
    i
  }

  /** Skip whitespace and comments. */
  private def skipWs(): Unit =
    while (avail(0)) {
      val c = buf(pos)
      if (c == ' ' || c == '\t') pos += 1
      else if (c == '\n') {
        pos += 1
        newLine(pos)
      } else if (c == '\r') {
        pos += 1
        // The LF of a CRLF counts the line instead.
        if (!(avail(0) && buf(pos) == '\n')) newLine(pos)
      } else if (c == '#') {
        var k = 1
        while (avail(k) && buf(pos + k) != '\n' && buf(pos + k) != '\r') k += 1
        pos += k
      } else return
    }

  private def newLine(start: Int): Unit = {
    line += 1
    lineStart = start
    lineColumns = 0
  }

  private def expect(c: Char, reason: String): Unit =
    if (avail(0) && at(0) == c) pos += 1 else fail(reason)

  // Statements

  private def statement(): Unit = {
    val c = at(0)
    if (c == '@') {
      val k = scan(PnChars, 1)
      if (isWord(1, k, "prefix")) {
        pos += k
        prefixDirective()
      } else if (isWord(1, k, "base")) {
        pos += k
        baseDirective()
      } else fail("Expected @prefix or @base")
      skipWs()
      expect('.', "Expected '.' at the end of the directive")
    } else if ((c | 0x20) == 'p' && isKeyword("prefix")) {
      pos += 6
      prefixDirective()
    } else if ((c | 0x20) == 'b' && isKeyword("base")) {
      pos += 4
      baseDirective()
    } else {
      triples()
      skipWs()
      expect('.', "Expected '.' at the end of the statement")
    }
  }

  /** Whether the bytes at offsets `[from, to)` are the ASCII `word`. */
  private def isWord(from: Int, to: Int, word: String): Boolean =
    sameAscii(word, pos + from, pos + to)

  /** Whether the input starts with the SPARQL-style keyword `word`, in any case, as a whole token.
    */
  private def isKeyword(word: String): Boolean = {
    val len = word.length
    var i = 0
    while (i < len) {
      if (!avail(i) || (at(i) | 0x20) != word.charAt(i)) return false
      i += 1
    }
    !avail(len) || !isNameChar(at(len))
  }

  /** Whether `c` continues a name, so that a keyword followed by it is a prefixed name instead. */
  private def isNameChar(c: Int): Boolean = PnChars(c) || c == '.' || c == ':' || c >= 0x80

  private def prefixDirective(): Unit = {
    skipWs()
    if (!avail(0)) fail("Expected a prefix name")
    val k = prefixLength()
    if (!(avail(k) && at(k) == ':')) fail("Expected a prefix name ending with ':'", k)
    val name = java.util.Arrays.copyOfRange(buf, pos, pos + k)
    pos += k + 1
    skipWs()
    if (!(avail(0) && at(0) == '<')) fail("Expected an IRI")
    val iri = readIriRef().value
    var i = 0
    while (i < nPrefixes && !java.util.Arrays.equals(prefixNames(i), name)) i += 1
    if (i == nPrefixes) {
      if (i == prefixNames.length) {
        prefixNames = java.util.Arrays.copyOf(prefixNames, i * 2)
        prefixIris = java.util.Arrays.copyOf(prefixIris, i * 2)
      }
      prefixNames(i) = name
      nPrefixes += 1
    }
    prefixIris(i) = iri
    if (!sawTriple) sink.namespace(new String(name, UTF_8), iri)
  }

  private def baseDirective(): Unit = {
    skipWs()
    if (!(avail(0) && at(0) == '<')) fail("Expected an IRI")
    base = BaseIri(readIriRef().value)
    if (!sawTriple) sink.base(base.iri)
  }

  private def triples(): Unit =
    if (at(0) == '[') {
      enter()
      pos += 1
      skipWs()
      val subj = newBlankNode()
      if (avail(0) && at(0) == ']') {
        pos += 1
        skipWs()
        predicateObjectList(subj)
      } else {
        predicateObjectList(subj)
        skipWs()
        expect(']', "Expected ']' at the end of the blank node")
        skipWs()
        // The predicate-object list after a `[ ... ]` subject is optional.
        if (avail(0) && at(0) != '.') predicateObjectList(subj)
      }
      nesting -= 1
    } else {
      val subj = subject()
      skipWs()
      predicateObjectList(subj)
    }

  private def subject(): Resource = at(0) match {
    case '<' => readIriRef()
    case '_' => readBlankNodeLabel()
    case '(' => collection(null, null)
    case c if c == ':' || isNameStart(c) =>
      val k = prefixLength()
      if (avail(k) && at(k) == ':') readPrefixedName(k)
      else fail("Expected a subject")
    case _ => fail("Expected a subject")
  }

  private def predicateObjectList(subj: Resource): Unit = {
    verbObjectList(subj)
    while (true) {
      skipWs()
      if (!(avail(0) && at(0) == ';')) return
      while (avail(0) && at(0) == ';') {
        pos += 1
        skipWs()
      }
      if (!avail(0) || at(0) == '.' || at(0) == ']') return
      verbObjectList(subj)
    }
  }

  private def verbObjectList(subj: Resource): Unit = {
    val pred = verb()
    skipWs()
    readObject(subj, pred)
    while (true) {
      skipWs()
      if (!(avail(0) && at(0) == ',')) return
      pos += 1
      skipWs()
      readObject(subj, pred)
    }
  }

  private def verb(): Iri = {
    if (!avail(0)) fail("Expected a predicate")
    val c = at(0)
    if (c == '<') readIriRef()
    else if (c == 'a' && (!avail(1) || !isNameChar(at(1)))) {
      pos += 1
      Rdf.`type`
    } else if (c == ':' || isNameStart(c)) {
      val k = prefixLength()
      if (avail(k) && at(k) == ':') readPrefixedName(k)
      else fail("Expected a predicate")
    } else fail("Expected a predicate")
  }

  /** Read an object and push the triple, before the triples of the object itself, if it has any. */
  private def readObject(subj: Resource, pred: Iri): Unit = {
    if (!avail(0)) fail("Expected an object")
    at(0) match {
      case '<' => emit(subj, pred, readIriRef())
      case '_' => emit(subj, pred, readBlankNodeLabel())
      case '"' | '\'' => emit(subj, pred, readLiteral())
      case '[' =>
        enter()
        pos += 1
        skipWs()
        val obj = newInlineBlankNode()
        emit(subj, pred, obj)
        if (avail(0) && at(0) == ']') pos += 1
        else {
          predicateObjectList(obj)
          skipWs()
          expect(']', "Expected ']' at the end of the blank node")
        }
        nesting -= 1
      case '(' => collection(subj, pred)
      case '.' if !(avail(1) && isAsciiDigit(at(1))) => fail("Expected an object")
      case c if isAsciiDigit(c) || c == '+' || c == '-' || c == '.' =>
        emit(subj, pred, readNumber())
      case c if c == ':' || isNameStart(c) =>
        val k = prefixLength()
        if (avail(k) && at(k) == ':') emit(subj, pred, readPrefixedName(k))
        else {
          // A bare word. A '.' after it ends the statement.
          var end = k
          while (end > 0 && at(end - 1) == '.') end -= 1
          if (isWord(0, end, "true")) emit(subj, pred, True)
          else if (isWord(0, end, "false")) emit(subj, pred, False)
          else fail("Expected an object")
          pos += end
        }
      case _ => fail("Expected an object")
    }
  }

  /** A collection, starting at its `(`. When `subj` is not null, `subj pred list` is pushed before
    * the list's own triples. Returns the head of the list.
    */
  private def collection(subj: Resource, pred: Iri): Resource = {
    enter()
    pos += 1
    skipWs()
    if (!avail(0)) fail("Expected ')' at the end of the collection")
    if (at(0) == ')') {
      pos += 1
      nesting -= 1
      if (subj != null) emit(subj, pred, Rdf.nil)
      return Rdf.nil
    }
    val head = newBlankNode()
    if (subj != null) emit(subj, pred, head)
    var cell = head
    while (true) {
      readObject(cell, Rdf.first)
      skipWs()
      if (!avail(0)) fail("Expected ')' at the end of the collection")
      if (at(0) == ')') {
        pos += 1
        nesting -= 1
        emit(cell, Rdf.rest, Rdf.nil)
        return head
      }
      val next = newBlankNode()
      emit(cell, Rdf.rest, next)
      cell = next
    }
    head
  }

  /** Go one `[` or `(` deeper, which the input is at. */
  private def enter(): Unit = {
    nesting += 1
    if (nesting > maxNesting)
      fail(s"Nesting deeper than $maxNesting levels of '[ ... ]' and '( ... )'")
  }

  private def emit(subj: Resource, pred: Iri, obj: Node): Unit = {
    sawTriple = true
    sink.triple(subj, pred, obj)
  }

  private def newBlankNode(): BlankNode = {
    blankNodes += 1
    new BlankNode("_".concat(blankNodes.toString))
  }

  private def newInlineBlankNode(): InlineBlankNode = {
    blankNodes += 1
    new InlineBlankNode("_".concat(blankNodes.toString))
  }

  // IRIs

  /** An IRIREF, starting at its `<`, resolved against the base IRI. */
  private def readIriRef(): Iri = {
    val k = scan(IriSafe, 1)
    val value =
      if (avail(k) && at(k) == '>') {
        iriEnd = k + 1
        new String(buf, pos + 1, k - 1, ISO_8859_1)
      } else readIriRefSlow(k)
    val iri =
      if (hasScheme(value)) value
      else if (base == null) fail("Relative IRI with no base IRI to resolve it against")
      else base.resolve(value)
    pos += iriEnd
    new Iri(iri)
  }

  /** Where the IRIREF that [[readIriRef]] is reading ends, as an offset from [[pos]]. */
  private var iriEnd = 0

  /** An IRIREF with escapes or non-ASCII characters in it, continuing from offset `start`, where
    * the plain ASCII run starting at offset 1 ends.
    */
  private def readIriRefSlow(start: Int): String = {
    nChars = 0
    appendAscii(pos + 1, pos + start)
    var k = start
    var done = false
    while (!done) {
      if (!avail(k)) fail("Unterminated IRI", k)
      val c = at(k)
      if (IriSafe(c)) {
        val end = scan(IriSafe, k)
        appendAscii(pos + k, pos + end)
        k = end
      } else if (c == '>') {
        done = true
        k += 1
      } else if (c == '\\') {
        if (avail(k + 1) && (at(k + 1) == 'u' || at(k + 1) == 'U')) k = ucharAt(k)
        else fail("Only \\u and \\U escapes are allowed in an IRI", k)
      } else if (c >= 0x80) k = utf8At(k)
      else fail(f"Character U+$c%04X is not allowed in an IRI", k)
    }
    iriEnd = k
    new String(chars, 0, nChars)
  }

  /** Whether `c` can start a PN_PREFIX. */
  private def isNameStart(c: Int): Boolean = isAsciiLetter(c) || c >= 0x80

  /** The length of the PN_PREFIX at [[pos]], which is followed by `:` if it is one. 0 when the
    * input starts with `:`.
    */
  private def prefixLength(): Int = {
    if (at(0) == ':') return 0
    if (at(0) < 0x80) {
      if (!isAsciiLetter(at(0))) fail("Invalid prefix name")
    } else if (!isPnCharsBase(decodeUtf8At(0))) fail("Invalid prefix name")
    var k = 0
    var done = false
    while (!done) {
      k = scan(LabelChars, k)
      if (avail(k) && at(k) >= 0x80 && isPnChars(decodeUtf8At(k))) k += utf8Length(at(k))
      else done = true
    }
    // PN_PREFIX cannot end with '.', which then belongs to what follows it.
    if (avail(k) && at(k) == ':' && at(k - 1) == '.') fail("A prefix name cannot end with '.'", k)
    k
  }

  /** A prefixed name whose prefix is the `k` bytes at [[pos]], followed by its `:`. */
  private def readPrefixedName(k: Int): Iri = {
    val namespace = lookupPrefix(k)
    pos += k + 1
    var end = if (avail(0) && LocalStart(at(0))) scan(LocalChars, 1) else 0
    if (avail(end) && (at(end) == '\\' || at(end) == '%' || at(end) >= 0x80))
      return readLocalSlow(namespace)
    while (end > 0 && at(end - 1) == '.') end -= 1
    nChars = 0
    appendString(namespace)
    appendAscii(pos, pos + end)
    pos += end
    new Iri(new String(chars, 0, nChars))
  }

  private def lookupPrefix(k: Int): String = {
    if (lastPrefix >= 0 && samePrefix(lastPrefix, k)) return prefixIris(lastPrefix)
    var i = 0
    while (i < nPrefixes) {
      if (samePrefix(i, k)) {
        lastPrefix = i
        return prefixIris(i)
      }
      i += 1
    }
    fail(s"Undefined prefix '${new String(buf, pos, k, UTF_8)}:'")
  }

  private def samePrefix(i: Int, k: Int): Boolean = {
    val name = prefixNames(i)
    if (name.length != k) return false
    var j = 0
    while (j < k) {
      if (name(j) != buf(pos + j)) return false
      j += 1
    }
    true
  }

  /** A PN_LOCAL with escapes or non-ASCII characters in it, appended to `namespace`. */
  private def readLocalSlow(namespace: String): Iri = {
    nChars = 0
    appendString(namespace)
    // The name ends after the last character that is not an unescaped '.'.
    var end = 0
    var endChars = nChars
    var k = 0
    var done = false
    while (!done && avail(k)) {
      val c = at(k)
      if (c == '\\') {
        if (!(avail(k + 1) && LocalEscapes(at(k + 1))))
          fail("Invalid escape sequence in a local name", k)
        appendChar(at(k + 1).toChar)
        k += 2
      } else if (c == '%') {
        if (!(avail(k + 2) && isHexDigit(at(k + 1)) && isHexDigit(at(k + 2))))
          fail("Expected two hex digits after '%'", k)
        appendAscii(pos + k, pos + k + 3)
        k += 3
      } else if (c < 0x80) {
        if (if (k == 0) LocalStart(c) else LocalChars(c)) {
          appendChar(c.toChar)
          k += 1
        } else done = true
      } else {
        val cp = decodeUtf8At(k)
        if (isPnCharsBase(cp) || (k > 0 && isPnChars(cp))) {
          appendCodePoint(cp)
          k += utf8Length(c)
        } else done = true
      }
      if (!done && c != '.') {
        end = k
        endChars = nChars
      }
    }
    pos += end
    new Iri(new String(chars, 0, endChars))
  }

  private def isHexDigit(c: Int): Boolean =
    isAsciiDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')

  // Blank nodes

  /** `_:` followed by a label, per BLANK_NODE_LABEL. */
  private def readBlankNodeLabel(): BlankNode = {
    if (!(avail(1) && at(1) == ':')) fail("Expected '_:'")
    if (!avail(2) || at(2) == '.' || at(2) == '-') fail("Invalid blank node label", 2)
    var k = 2
    var ascii = true
    var done = false
    while (!done) {
      k = scan(LabelChars, k)
      if (avail(k) && at(k) >= 0x80) {
        val cp = decodeUtf8At(k)
        if (if (k == 2) isPnCharsBase(cp) else isPnChars(cp)) {
          ascii = false
          k += utf8Length(at(k))
        } else done = true
      } else done = true
    }
    // The label cannot end with '.', which belongs to the end of the statement instead.
    while (k > 2 && at(k - 1) == '.') k -= 1
    if (k == 2) fail("Invalid blank node label", 2)
    val label = new String(buf, pos + 2, k - 2, if (ascii) ISO_8859_1 else UTF_8)
    pos += k
    // Keep clear of the `_1`, `_2`, ... labels made up for `[ ... ]` and collections.
    new BlankNode(if (label.charAt(0) == '_') "_".concat(label) else label)
  }

  // Literals

  /** A string literal with its datatype or language tag, starting at its opening quote. */
  private def readLiteral(): Node = {
    val q = at(0)
    val long = avail(2) && at(1) == q && at(2) == q
    val value = readString(q, long)
    skipWs()
    if (avail(0) && at(0) == '@') LanguageLiteral(value, readLanguage())
    else if (avail(0) && at(0) == '^') {
      if (!(avail(1) && at(1) == '^')) fail("Expected '^^' before the datatype")
      pos += 2
      skipWs()
      if (!avail(0)) fail("Expected a datatype IRI")
      val datatype = at(0) match {
        case '<' => readIriRef()
        case c if c == ':' || isNameStart(c) =>
          val k = prefixLength()
          if (avail(k) && at(k) == ':') readPrefixedName(k) else fail("Expected a datatype IRI")
        case _ => fail("Expected a datatype IRI")
      }
      Literal(value, if (datatype == XmlSchema.string) XmlSchema.string else datatype)
    } else Literal(value)
  }

  /** The lexical form of a string quoted with `q`, three times over if `long`. */
  private def readString(q: Int, long: Boolean): String = {
    val open = if (long) 3 else 1
    val safe = if (q == '"') DoubleQuoteSafe else SingleQuoteSafe
    val k = scan(safe, open)
    if (!long && avail(k) && at(k) == q) {
      val s = new String(buf, pos + 1, k - 1, ISO_8859_1)
      pos += k + 1
      return s
    }
    if (long && avail(k + 2) && at(k) == q && at(k + 1) == q && at(k + 2) == q) {
      val s = new String(buf, pos + 3, k - 3, ISO_8859_1)
      pos += k + 3
      return s
    }
    readStringSlow(q, long, safe, open, k)
  }

  /** The rest of a string with escapes, non-ASCII characters or line breaks in it, from offset `k`,
    * where the plain ASCII run starting at offset `open` ends.
    */
  private def readStringSlow(
      q: Int,
      long: Boolean,
      safe: Array[Boolean],
      open: Int,
      start: Int,
  ): String = {
    nChars = 0
    appendAscii(pos + open, pos + start)
    var k = start
    var done = false
    while (!done) {
      if (!avail(k)) fail("Unterminated string literal", k)
      val c = at(k)
      if (safe(c)) {
        val end = scan(safe, k)
        appendAscii(pos + k, pos + end)
        k = end
      } else if (c == q) {
        if (!long) {
          done = true
          k += 1
        } else if (avail(k + 2) && at(k + 1) == q && at(k + 2) == q) {
          done = true
          k += 3
        } else {
          appendChar(c.toChar)
          k += 1
        }
      } else if (c == '\\') {
        if (!avail(k + 1)) fail("Unterminated string literal", k + 1)
        k = if (at(k + 1) == 'u' || at(k + 1) == 'U') ucharAt(k) else readEchar(pos + k) - pos
      } else if (c == '\n' || c == '\r') {
        if (!long)
          fail("Line breaks are not allowed in a string literal unless it is \"\"\"-quoted", k)
        appendChar(c.toChar)
        k += 1
        if (c == '\n' || !(avail(k) && at(k) == '\n')) newLine(pos + k)
      } else k = utf8At(k)
    }
    pos += k
    new String(chars, 0, nChars)
  }

  /** `@` followed by `[a-zA-Z]+ ('-' [a-zA-Z0-9]+)*`. */
  private def readLanguage(): String = {
    var k = 1
    while (avail(k) && isAsciiLetter(at(k))) k += 1
    if (k == 1) fail("Invalid language tag", 1)
    while (avail(k) && at(k) == '-') {
      val subtag = k + 1
      k = subtag
      while (avail(k) && (isAsciiLetter(at(k)) || isAsciiDigit(at(k)))) k += 1
      if (k == subtag) fail("Invalid language tag", subtag)
    }
    val tag = language(pos + 1, pos + k)
    pos += k
    tag
  }

  /** An INTEGER, DECIMAL or DOUBLE. */
  private def readNumber(): Literal = {
    var k = if (at(0) == '+' || at(0) == '-') 1 else 0
    val intStart = k
    while (avail(k) && isAsciiDigit(at(k))) k += 1
    val intDigits = k - intStart
    var datatype = XmlSchema.integer
    // A '.' is part of the number only if a digit or an exponent follows; otherwise it ends the
    // statement.
    if (avail(k + 1) && at(k) == '.' && isAsciiDigit(at(k + 1))) {
      k += 1
      while (avail(k) && isAsciiDigit(at(k))) k += 1
      datatype = XmlSchema.decimal
    } else if (intDigits > 0 && avail(k + 1) && at(k) == '.' && exponentLength(k + 1) > 0) {
      k += 1
    } else if (intDigits == 0) fail("Invalid number")
    val exponent = exponentLength(k)
    if (exponent > 0) {
      k += exponent
      datatype = XmlSchema.double
    }
    val value = new String(buf, pos, k, ISO_8859_1)
    pos += k
    Literal(value, datatype)
  }

  /** The length of the EXPONENT at offset `k`, or 0 if there is none. */
  private def exponentLength(k: Int): Int = {
    if (!(avail(k) && (at(k) == 'e' || at(k) == 'E'))) return 0
    var i = k + 1
    if (avail(i) && (at(i) == '+' || at(i) == '-')) i += 1
    val digits = i
    while (avail(i) && isAsciiDigit(at(i))) i += 1
    if (i == digits) 0 else i - k
  }

  // Characters

  /** [[readUchar]] at offset `k`. Returns the offset after it. */
  private def ucharAt(k: Int): Int = {
    avail(k + (if (at(k + 1) == 'u') 5 else 9))
    readUchar(pos + k, limit) - pos
  }

  /** [[readUtf8]] at offset `k`. Returns the offset after it. */
  private def utf8At(k: Int): Int = {
    avail(k + utf8Length(at(k)) - 1)
    readUtf8(pos + k, limit) - pos
  }

  private def decodeUtf8At(k: Int): Int = {
    avail(k + utf8Length(at(k)) - 1)
    decodeUtf8(pos + k, limit)
  }

  private def appendString(s: String): Unit = {
    val n = s.length
    if (nChars + n > chars.length) chars = java.util.Arrays.copyOf(chars, (nChars + n) * 2)
    s.getChars(0, n, chars, nChars)
    nChars += n
  }

  private def fail(reason: String, k: Int = 0): Nothing = failAt(reason, pos + k)

  protected def failAt(reason: String, at: Int): Nothing = {
    val i = math.min(at, limit)
    val column = if (i < lineStart) 1 else lineColumns + codePoints(lineStart, i) + 1
    throw new RdfParseException(reason, line, column)
  }
}
