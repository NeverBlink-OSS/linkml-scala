package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.CharSink

import java.io.OutputStream

/** JVM-optimized N-Triples output, similar to Apache Jena: characters are written into an
  * unsynchronized byte buffer that is flushed to the underlying [[OutputStream]] in bulk.
  *
  * Because [[NTriplesWriter]] escapes everything to US-ASCII, the common path writes one byte per
  * character with no charset encoding. The only characters that can reach the sink non-ASCII are
  * blank-node labels, which fall back to a plain UTF-8 encoding.
  */
object NTriplesOutput {

  /** Serialize [[triples]] to [[out]] in N-Triples, using a buffered byte sink. Flushes at the end
    * but does not close [[out]].
    */
  def writeTo(
      out: OutputStream,
      triples: IterableOnce[Triple],
      bufferSize: Int = 8 * 1024,
  ): Unit = {
    val sink = new BufferedByteSink(out, bufferSize)
    NTriplesWriter.writeAll(sink, triples)
    sink.flush()
  }
}

/** Unsynchronized, buffering [[CharSink]] writing US-ASCII (with UTF-8 fallback) bytes to an
  * [[OutputStream]].
  *
  * Assumes that supplementary characters are escaped to ASCII upstream by the caller.
  */
final class BufferedByteSink(out: OutputStream, bufferSize: Int = 8 * 1024) extends CharSink {
  private val buffer = new Array[Byte](math.max(bufferSize, 16))
  private var idx = 0

  def append(c: Char): Unit =
    if (c < 0x80) putByte(c.toByte) else appendNonAscii(c)

  def append(s: String): Unit = {
    val len = s.length
    var i = 0
    while (i < len) {
      val c = s.charAt(i)
      if (c < 0x80) putByte(c.toByte) else appendNonAscii(c)
      i += 1
    }
  }

  /** UTF-8 encode a non-ASCII BMP code unit. Supplementary characters are escaped to ASCII upstream
    * by [[NTriplesEscape]], so only single code units reach here.
    */
  private def appendNonAscii(c: Char): Unit = {
    val cp = c.toInt
    if (cp < 0x800) {
      putByte((0xc0 | (cp >> 6)).toByte)
      putByte((0x80 | (cp & 0x3f)).toByte)
    } else {
      putByte((0xe0 | (cp >> 12)).toByte)
      putByte((0x80 | ((cp >> 6) & 0x3f)).toByte)
      putByte((0x80 | (cp & 0x3f)).toByte)
    }
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

  /** Flush buffered bytes to and then flush the underlying stream. */
  def flush(): Unit = {
    flushBuffer()
    out.flush()
  }
}
