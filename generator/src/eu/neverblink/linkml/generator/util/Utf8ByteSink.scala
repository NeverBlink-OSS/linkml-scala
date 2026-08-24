package eu.neverblink.linkml.generator.util

import java.io.OutputStream

/** Unsynchronized buffering [[CharSink]] that encodes chars with UTF-8 into an [[OutputStream]].
  *
  * ASCII characters take a fast path of one byte each with no charset machinery. N-Triples writer
  * always uses this fast path (all Unicode is escaped).
  *
  * The bytes are the same as `String.getBytes(UTF_8)` would produce, including for characters
  * outside the BMP and for unpaired surrogates, which both encoders replace with `'?'`. Because
  * [[append(c:Char)*]] can be handed the two halves of a surrogate pair in separate calls, a high
  * surrogate is held back until its partner arrives. [[flush]] therefore doubles as
  * end-of-document.
  */
final class Utf8ByteSink(out: OutputStream, bufferSize: Int = 8 * 1024) extends CharSink {
  import Utf8ByteSink.Replacement

  private val buffer = new Array[Byte](math.max(bufferSize, 16))
  private var idx = 0

  /** A high surrogate waiting for the low half that completes it, or 0 when there is none.
    *
    * Always set through [[holdHigh]] / [[releaseHigh]], which keep [[directMax]] in step.
    */
  private var pendingHigh = 0

  /** The exclusive upper bound for characters that go straight out as one byte.
    *
    * 0x80 normally, 0 while a high surrogate is pending, which forces every character down the slow
    * path so the pair gets resolved first. Rolling the pending-surrogate check this way keeps the
    * fast path at one field load and one comparison.
    */
  private var directMax = 0x80

  /** Kept deliberately tiny, with everything else in [[appendOther]].
    *
    * [[eu.neverblink.linkml.generator.rdf.NTriplesWriter]] calls this once per character of every
    * IRI and literal it escapes, so it is only fast if the JIT inlines it into those loops. Folding
    * the wide-character and surrogate handling back in here grows the method past HotSpot's
    * inlining threshold.
    */
  def append(c: Char): Unit =
    if (c < directMax) putByte(c.toByte) else appendOther(c)

  override def appendAscii(c: Char): Unit = putByte(c.toByte)

  private def appendOther(c: Char): Unit =
    if (pendingHigh != 0) completePair(c)
    else if (Character.isHighSurrogate(c)) holdHigh(c)
    else if (Character.isLowSurrogate(c)) putByte(Replacement) // a low half with nothing before it
    else putCodePoint(c.toInt)

  private def holdHigh(c: Char): Unit = {
    pendingHigh = c.toInt
    directMax = 0
  }

  private def releaseHigh(): Char = {
    val high = pendingHigh.toChar
    pendingHigh = 0
    directMax = 0x80
    high
  }

  def append(s: String): Unit = {
    val len = s.length
    var i = 0
    // A surrogate held from a previous call can only pair with the start of this one, so resolve it
    // up front and keep the loop below free of any reference to it. That loop is the hot path for
    // N-Triples, where it runs once per character of the whole document.
    while (pendingHigh != 0 && i < len) {
      append(s.charAt(i))
      i += 1
    }
    while (i < len) {
      val c = s.charAt(i)
      if (c < 0x80) {
        putByte(c.toByte)
        i += 1
      } else i = appendWide(s, i, len)
    }
  }

  /** Encode the non-ASCII character at [[i]], returning the index to continue from. */
  private def appendWide(s: String, i: Int, len: Int): Int = {
    val c = s.charAt(i)
    if (!Character.isSurrogate(c)) {
      putCodePoint(c.toInt)
      i + 1
    } else if (!Character.isHighSurrogate(c)) {
      putByte(Replacement) // a low half with nothing before it
      i + 1
    } else if (i + 1 == len) {
      // Might still pair with the first character of the next call.
      holdHigh(c)
      i + 1
    } else if (Character.isLowSurrogate(s.charAt(i + 1))) {
      putCodePoint(Character.toCodePoint(c, s.charAt(i + 1)))
      i + 2
    } else {
      putByte(Replacement) // a high half followed by something that cannot complete it
      i + 1
    }
  }

  /** Resolve a held-back high surrogate against the character that followed it. */
  private def completePair(c: Char): Unit = {
    val high = releaseHigh()
    if (Character.isLowSurrogate(c)) putCodePoint(Character.toCodePoint(high, c))
    else {
      putByte(Replacement) // the high half was never completed
      append(c)
    }
  }

  private def putCodePoint(cp: Int): Unit =
    if (cp < 0x80) putByte(cp.toByte)
    else if (cp < 0x800) {
      putByte((0xc0 | (cp >> 6)).toByte)
      putByte((0x80 | (cp & 0x3f)).toByte)
    } else if (cp < 0x10000) {
      putByte((0xe0 | (cp >> 12)).toByte)
      putByte((0x80 | ((cp >> 6) & 0x3f)).toByte)
      putByte((0x80 | (cp & 0x3f)).toByte)
    } else {
      putByte((0xf0 | (cp >> 18)).toByte)
      putByte((0x80 | ((cp >> 12) & 0x3f)).toByte)
      putByte((0x80 | ((cp >> 6) & 0x3f)).toByte)
      putByte((0x80 | (cp & 0x3f)).toByte)
    }

  private def putByte(b: Byte): Unit = {
    if (idx == buffer.length) flushBuffer()
    buffer(idx) = b
    idx += 1
  }

  private def flushBuffer(): Unit =
    if (idx > 0) {
      out.write(buffer, 0, idx)
      idx = 0
    }

  /** Flush buffered bytes to and then flush the underlying stream.
    *
    * This marks the end of the document: a high surrogate still waiting for its partner will never
    * get one, so it is replaced here.
    */
  def flush(): Unit = {
    if (pendingHigh != 0) {
      releaseHigh()
      putByte(Replacement)
    }
    flushBuffer()
    out.flush()
  }
}

object Utf8ByteSink {

  /** What both this sink and `String.getBytes(UTF_8)` substitute for an unpaired surrogate. */
  private[util] final val Replacement: Byte = '?'.toByte
}
