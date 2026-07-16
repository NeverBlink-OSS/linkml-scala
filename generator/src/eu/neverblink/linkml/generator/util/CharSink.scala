package eu.neverblink.linkml.generator.util

/** A minimal append-only sink for characters and short strings.
  */
trait CharSink {
  def append(c: Char): Unit
  def append(s: String): Unit
}

/** A [[CharSink]] backed by a growable string buffer. */
final class StringSink extends CharSink {
  private val sb = new java.lang.StringBuilder

  def append(c: Char): Unit = sb.append(c)
  def append(s: String): Unit = sb.append(s)
  def result: String = sb.toString
}
