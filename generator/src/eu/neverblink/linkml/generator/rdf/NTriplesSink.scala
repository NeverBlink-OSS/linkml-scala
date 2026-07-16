package eu.neverblink.linkml.generator.rdf

/** Character sink for N-Triples serialization. This is the cross-platform analogue of Jena's
  * `AWriter`: [[NTriplesWriter]] drives it a character (or short string) at a time.
  *
  * Everything handed to a sink is guaranteed to be US-ASCII, because [[NTriplesEscape]] escapes any
  * other character to `\\uXXXX`/`\\UXXXXXXXX`. The only exception is blank-node labels, which are
  * emitted verbatim via [[append(String)]]. This ASCII guarantee lets the JVM sink take a fast byte
  * path (see `BufferedByteSink` in the JVM source set).
  */
trait NTriplesSink {
  def append(c: Char): Unit
  def append(s: String): Unit
}

/** A [[NTriplesSink]] backed by a growable string buffer. Cross-platform, used for
  * [[NTriplesWriter.writeToString]] and [[NTriplesWriter.format]].
  */
final class StringNTriplesSink extends NTriplesSink {
  private val sb = new java.lang.StringBuilder

  def append(c: Char): Unit = sb.append(c)
  def append(s: String): Unit = sb.append(s)
  def result: String = sb.toString
}
