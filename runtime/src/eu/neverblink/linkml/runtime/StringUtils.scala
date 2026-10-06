package eu.neverblink.linkml.runtime

import scala.collection.mutable

/** String helpers that go over the chars with `charAt` loops instead of the Scala collection
  * methods, which box every `Char`.
  */
object StringUtils {

  /** Split `text` into lines with trailing whitespace removed, breaking at `\n`, `\r` and `\r\n`
    * like `linesIterator` does.
    */
  def splitLines(text: String): List[String] = {
    val lines = new mutable.ListBuffer[String]
    val len = text.length
    var start = 0
    var i = 0
    while (i < len) {
      val c = text.charAt(i)
      i += 1
      if (c == '\n' || c == '\r') {
        lines.addOne(text.substring(start, i - 1).stripTrailing)
        if (c == '\r' && i < len && text.charAt(i) == '\n') i += 1
        start = i
      }
    }
    if (start < len) lines.addOne(text.substring(start).stripTrailing)
    lines.toList
  }

  /** Lowercase hex digit of the lowest 4 bits of `x`, as in `%04x`. */
  def hexDigit(x: Int): Char = "0123456789abcdef".charAt(x & 0xf)
}
