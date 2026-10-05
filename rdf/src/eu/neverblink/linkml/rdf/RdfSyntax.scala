package eu.neverblink.linkml.rdf

/** Character classes and IRI checks shared by the parsers. */
private[rdf] object RdfSyntax {

  /** A lookup table over byte values, true for `chars`. */
  def table(chars: Iterable[Int]): Array[Boolean] = {
    val a = new Array[Boolean](256)
    chars.foreach(a(_) = true)
    a
  }

  val Letters: Seq[Int] = ('a'.toInt to 'z') ++ ('A'.toInt to 'Z')
  val Digits: Seq[Int] = '0'.toInt to '9'

  /** ASCII bytes allowed as themselves in an IRIREF: 0x21..0x7E except `<>"{}|^`\\`. */
  val IriSafe: Array[Boolean] = table((0x21 to 0x7e).filterNot("<>\"{}|^`\\".contains(_)))

  /** ASCII bytes allowed as themselves in a `"`-quoted string: anything but `"`, `\\`, LF and CR.
    */
  val DoubleQuoteSafe: Array[Boolean] = table((0 until 0x80).filterNot("\"\\\n\r".contains(_)))

  /** ASCII PN_CHARS and `.`, which a blank node label may contain but not end with. */
  val LabelChars: Array[Boolean] = table(Letters ++ Digits ++ "_-.".map(_.toInt))

  def isAsciiLetter(c: Int): Boolean = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')

  def isAsciiDigit(c: Int): Boolean = c >= '0' && c <= '9'

  /** Whether `iri` starts with a scheme: `[A-Za-z][A-Za-z0-9+.-]*:`. */
  def hasScheme(iri: String): Boolean = {
    val len = iri.length
    if (len == 0 || !isAsciiLetter(iri.charAt(0))) return false
    var i = 1
    while (i < len) {
      val c = iri.charAt(i)
      if (c == ':') return true
      if (!(isAsciiLetter(c) || isAsciiDigit(c) || c == '+' || c == '-' || c == '.')) return false
      i += 1
    }
    false
  }

  /** PN_CHARS_BASE above ASCII. */
  def isPnCharsBase(cp: Int): Boolean =
    (cp >= 0xc0 && cp <= 0xd6) || (cp >= 0xd8 && cp <= 0xf6) || (cp >= 0xf8 && cp <= 0x2ff) ||
      (cp >= 0x370 && cp <= 0x37d) || (cp >= 0x37f && cp <= 0x1fff) ||
      (cp >= 0x200c && cp <= 0x200d) || (cp >= 0x2070 && cp <= 0x218f) ||
      (cp >= 0x2c00 && cp <= 0x2fef) || (cp >= 0x3001 && cp <= 0xd7ff) ||
      (cp >= 0xf900 && cp <= 0xfdcf) || (cp >= 0xfdf0 && cp <= 0xfffd) ||
      (cp >= 0x10000 && cp <= 0xeffff)

  /** PN_CHARS above ASCII. */
  def isPnChars(cp: Int): Boolean =
    isPnCharsBase(cp) || cp == 0xb7 || (cp >= 0x300 && cp <= 0x36f) ||
      (cp >= 0x203f && cp <= 0x2040)
}
