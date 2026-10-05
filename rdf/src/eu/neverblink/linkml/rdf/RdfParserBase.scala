package eu.neverblink.linkml.rdf

import java.nio.charset.StandardCharsets.ISO_8859_1

/** Shared base for NT, TTL and similar parsers: the input buffer, the scratch space for decoded
  * terms, decoding UTF-8, and escapes.
  *
  * Every position here is an index into [[buf]], and every routine reads only `buf[i, end)`, never
  * refilling it. A parser that refills mid-token makes sure the bytes are there first.
  */
private[rdf] abstract class RdfParserBase(bufferSize: Int) {

  protected var buf = new Array[Byte](math.max(bufferSize, 16))

  /** Scratch space for the terms that need decoding (escapes or non-ASCII). */
  protected var chars = new Array[Char](256)
  protected var nChars = 0

  private var lastLanguage = ""

  /** Throw an [[RdfParseException]] for the problem at `buf(at)`. */
  protected def failAt(reason: String, at: Int): Nothing

  /** Whether `buf[start, stop)` is the ASCII string `s`. */
  protected final def sameAscii(s: String, start: Int, stop: Int): Boolean = {
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

  /** The language tag in `buf[start, stop)`, reusing the last one's string if it is the same. */
  protected final def language(start: Int, stop: Int): String = {
    if (!sameAscii(lastLanguage, start, stop))
      lastLanguage = new String(buf, start, stop - start, ISO_8859_1)
    lastLanguage
  }

  /** A `\\uXXXX` or `\\UXXXXXXXX` escape at `i`, appended to [[chars]]. Returns the index after it.
    */
  protected final def readUchar(i: Int, end: Int): Int = {
    val digits = if (buf(i + 1) == 'u') 4 else 8
    if (i + 2 + digits > end) failAt("Truncated \\u escape", i)
    var cp = 0
    var k = i + 2
    while (k < i + 2 + digits) {
      val d = Character.digit(buf(k).toChar, 16)
      if (d < 0) failAt("Invalid hex digit in \\u escape", k)
      cp = (cp << 4) | d
      k += 1
    }
    if (cp < 0 || cp > Character.MAX_CODE_POINT) failAt("Escape is not a Unicode code point", i)
    appendCodePoint(cp)
    k
  }

  /** The ECHAR escape `\\c` at `i`, appended to [[chars]]. Returns the index after it. */
  protected final def readEchar(i: Int): Int = {
    buf(i + 1).toChar match {
      case 't' => appendChar('\t')
      case 'b' => appendChar('\b')
      case 'n' => appendChar('\n')
      case 'r' => appendChar('\r')
      case 'f' => appendChar('\f')
      case '"' => appendChar('"')
      case '\'' => appendChar('\'')
      case '\\' => appendChar('\\')
      case _ => failAt("Invalid escape sequence", i)
    }
    i + 2
  }

  /** The multi-byte UTF-8 sequence at `i`, appended to [[chars]]. Returns the index after it. */
  protected final def readUtf8(i: Int, end: Int): Int = {
    appendCodePoint(decodeUtf8(i, end))
    i + utf8Length(buf(i) & 0xff)
  }

  /** The length of the UTF-8 sequence that starts with `lead`, if it is a valid one. */
  protected final def utf8Length(lead: Int): Int =
    if (lead < 0xe0) 2 else if (lead < 0xf0) 3 else 4

  /** Decode the multi-byte UTF-8 sequence at `i`, rejecting anything malformed. */
  protected final def decodeUtf8(i: Int, end: Int): Int = {
    val b = buf
    val lead = b(i) & 0xff
    val len =
      if (lead >= 0xc2 && lead <= 0xdf) 2
      else if (lead >= 0xe0 && lead <= 0xef) 3
      else if (lead >= 0xf0 && lead <= 0xf4) 4
      else failAt("Invalid UTF-8", i)
    if (i + len > end) failAt("Invalid UTF-8", i)
    var cp = lead & (0x7f >> len)
    var k = 1
    while (k < len) {
      val cont = b(i + k) & 0xff
      if ((cont & 0xc0) != 0x80) failAt("Invalid UTF-8", i)
      cp = (cp << 6) | (cont & 0x3f)
      k += 1
    }
    val overlong = (len == 3 && cp < 0x800) || (len == 4 && cp < 0x10000)
    if (overlong || cp > Character.MAX_CODE_POINT || (cp >= 0xd800 && cp <= 0xdfff))
      failAt("Invalid UTF-8", i)
    cp
  }

  /** Append the ASCII bytes `buf[from, to)`. */
  protected final def appendAscii(from: Int, to: Int): Unit = {
    val n = to - from
    if (nChars + n > chars.length) chars = java.util.Arrays.copyOf(chars, (nChars + n) * 2)
    val b = buf
    var i = from
    var j = nChars
    while (i < to) {
      chars(j) = (b(i) & 0xff).toChar
      i += 1
      j += 1
    }
    nChars += n
  }

  protected final def appendCodePoint(cp: Int): Unit =
    if (cp < 0x10000) appendChar(cp.toChar)
    else {
      appendChar(Character.highSurrogate(cp))
      appendChar(Character.lowSurrogate(cp))
    }

  protected final def appendChar(c: Char): Unit = {
    if (nChars == chars.length) chars = java.util.Arrays.copyOf(chars, nChars * 2)
    chars(nChars) = c
    nChars += 1
  }

  /** The number of code points in `buf[from, to)`: the bytes that are not UTF-8 continuations. */
  protected final def codePoints(from: Int, to: Int): Int = {
    var n = 0
    var i = from
    while (i < to) {
      if ((buf(i) & 0xc0) != 0x80) n += 1
      i += 1
    }
    n
  }
}
