package eu.neverblink.linkml.generator.util

/** A minimal append-only sink for characters and short strings.
  */
trait CharSink {
  def append(c: Char): Unit
  def append(s: String): Unit

  /** Append a character the caller has already established is ASCII, i.e. `c < 0x80`. This allows
    * us to take a fast path and append the byte directly.
    *
    * Using this with a non-ASCII character will result in corrupted output.
    */
  def appendAscii(c: Char): Unit = append(c)
}

/** A [[CharSink]] backed by a growable string buffer. */
final class StringSink extends CharSink {
  private val sb = new java.lang.StringBuilder

  def append(c: Char): Unit = sb.append(c)
  def append(s: String): Unit = sb.append(s)
  def result: String = sb.toString
}
