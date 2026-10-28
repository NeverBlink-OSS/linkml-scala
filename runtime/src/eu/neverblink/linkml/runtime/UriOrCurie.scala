package eu.neverblink.linkml.runtime

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

sealed trait UriOrCurie {
  def original: String

  /** Validate the value of this UriOrCurie. Returns true if valid, false if invalid.
    */
  def isValid: Boolean
}

object UriOrCurie {

  /** Create a [[Uri]] with the expansion of [[s]], if [[prefixes]] can expand it.
    *
    * Otherwise, [[s]] is a [[Uri]] if it is a valid absolute URI, such as `xsd:string` when `xsd`
    * isn't a known prefix, or if it starts with `urn:` or has `://`. Anything else is a [[Curie]].
    */
  def expanded(s: String, prefixes: PrefixMap): UriOrCurie = {
    val uri = prefixes.expandOrNull(s)
    if (uri ne null) new Uri(uri)
    // The quick checks go first, as most values that aren't expanded have `://`
    else if (s.contains("://") || s.startsWith("urn:") || UriCurieValidator.isValidUri(s))
      new Uri(s)
    else new Curie(s)
  }

  /** Get the text to write for [[x]]: a CURIE if [[x]] is a [[Uri]] that [[prefixes]] can compact,
    * or else the original text.
    *
    * @throws SchemaConversionException
    *   if [[x]] is a [[Uri]] that can't be compacted, but whose text [[prefixes]] would expand when
    *   it is read back, such as the URI `ex:Person` when `ex` is a declared prefix
    */
  def compacted(x: UriOrCurie, prefixes: PrefixMap): String = x match {
    case u: Uri =>
      val uri = u.original
      val curie = prefixes.compactOrNull(uri)
      if (curie ne null) curie
      else {
        if (readsBackAsCurie(uri, prefixes)) throw readBackAsCurieError(uri)
        uri
      }
    case _ => x.original
  }

  /** [[x]] as a [[Uri]] with the same text, which is [[x]] itself if it is a [[Uri]]. In a schema
    * that passes validation, a [[Curie]] here is a URI whose prefix isn't declared, such as
    * `mailto:a@b.org`.
    */
  def asUri(x: UriOrCurie): Uri = x match {
    case u: Uri => u
    case _ => new Uri(x.original)
  }

  /** Check if [[uri]], written in full, would be read back as a CURIE that [[prefixes]] expand, as
    * the text before its first colon is a declared prefix. This is the URI `ex:Person` when `ex` is
    * a declared prefix, but also `http://example.org/a` when `http` is.
    */
  def readsBackAsCurie(uri: String, prefixes: PrefixMap): Boolean =
    uri.indexOf(':') > 0 && (prefixes.expandOrNull(uri) ne null)

  /** The error for a URI that can't be written, as its text would be read back as a CURIE with a
    * declared prefix, such as the URI `ex:Person` when `ex` is a declared prefix.
    */
  def readBackAsCurieError(uri: String): SchemaConversionException = {
    val prefix = uri.substring(0, uri.indexOf(':'))
    new SchemaConversionException(
      s"The URI '$uri' can't be written, as '$prefix' is a declared prefix, so it would be " +
        "read back as a CURIE with another meaning. If it is meant as a CURIE, declare the " +
        s"prefix '$prefix' where it is used. Otherwise, use a URI whose scheme isn't a " +
        "declared prefix",
    )
  }

  /** Check that [[s]] has the shape of a CURIE, not of a URI or a file path.
    *
    * It is a CURIE when the text before the first colon is a valid prefix name, the text after the
    * colon doesn't start with `/` or `\`, as in `http://example.org` or `C:\schemas`, and the
    * prefix is not one of the URI schemes that are not followed by `/`, such as `urn` or `mailto`.
    */
  def isCurie(s: String): Boolean = {
    val index = s.indexOf(':')
    index > 0 && {
      var i = 0
      var valid = true
      while (valid && i < index) {
        val c = s.charAt(i)
        valid = c < 128 && (Character.isLetter(c) || c == '_' ||
          (i > 0 && (Character.isDigit(c) || c == '-' || c == '.')))
        i += 1
      }
      valid && {
        val next = if (index + 1 < s.length) s.charAt(index + 1) else ' '
        next != '/' && next != '\\' && !isNonHierarchicalScheme(s, index)
      }
    }
  }

  /** Check that [[s]] is a full URI, not text that looks like a CURIE, such as `ex:draft`.
    *
    * It is a valid URI that has `://`, as in `https://example.org/a`, or whose scheme is one of the
    * URI schemes that are not followed by `/`, such as `urn`, `mailto`, `did`, `info` or `tag`.
    */
  def isFullUri(s: String): Boolean = {
    val index = s.indexOf(':')
    index > 0 && (s.contains("://") || isNonHierarchicalScheme(s, index)) &&
    UriCurieValidator.isValidUri(s)
  }

  /** Check that the first [[length]] chars of [[s]] are one of [[nonHierarchicalSchemes]], in any
    * case. Unlike `toLowerCase`, `regionMatches` doesn't depend on the default locale. In Turkish,
    * `toLowerCase` turns `INFO` into `ınfo`.
    */
  private def isNonHierarchicalScheme(s: String, length: Int): Boolean = {
    val schemes = nonHierarchicalSchemes
    var i = 0
    while (i < schemes.length) {
      val scheme = schemes(i)
      if (scheme.length == length && s.regionMatches(true, 0, scheme, 0, length)) return true
      i += 1
    }
    false
  }

  /** URI schemes whose URIs don't have `/` after the colon, so they look like CURIEs. */
  private val nonHierarchicalSchemes: Array[String] = Array(
    "about",
    "cid",
    "data",
    "did",
    "geo",
    "info",
    "mailto",
    "mid",
    "news",
    "sip",
    "sips",
    "tag",
    "tel",
    "urn",
  )
}

@flatten final case class Uri(original: String) extends UriOrCurie {
  def isValid: Boolean = UriCurieValidator.isValidUri(original)
}

object Uri {

  /** Create a synthetic URI from a LinkML name, ensuring the name is properly escaped. Assumes
    * [[base]] is a valid URI base and does not need escaping.
    */
  def synthetic(base: String, name: String): Uri =
    // The charset is named rather than passed as a `Charset`: that overload is Java 10 and
    // Scala Native's javalib only has the older one.
    new Uri(base.concat(URLEncoder.encode(name, StandardCharsets.UTF_8.name)))
}

@flatten final case class Curie(original: String) extends UriOrCurie {
  def isValid: Boolean = UriCurieValidator.isValidCurie(original)
}

type NcName = String

/** URI and CURIE validation functions.
  *
  * Each function checks the whole input in a single left-to-right pass, without regular
  * expressions, backtracking or allocations. The grammars are taken from RFC 3987 (IRI and relative
  * reference) and W3C CURIE Syntax 1.0 (CURIE).
  */
object UriCurieValidator {
  // Bit flags of character classes, looked up in the `charClasses` table for ASCII characters
  private final val Alpha = 1 // ALPHA
  private final val Digit = 2 // DIGIT
  private final val HexDig = 4 // HEXDIG
  private final val SchemeChar = 8 // ALPHA / DIGIT / "+" / "-" / "."
  private final val NcNameChar = 16 // ALPHA / DIGIT / "." / "-" / "_"
  private final val RegNameChar = 32 // unreserved / sub-delims

  private val charClasses: Array[Byte] = {
    val cs = new Array[Byte](128)
    def add(from: Char, to: Char, flags: Int): Unit = {
      var c = from.toInt
      while (c <= to) {
        cs(c) = (cs(c) | flags).toByte
        c += 1
      }
    }
    def addAll(chars: String, flags: Int): Unit = chars.foreach(c => add(c, c, flags))
    add('A', 'Z', Alpha | SchemeChar | NcNameChar | RegNameChar)
    add('a', 'z', Alpha | SchemeChar | NcNameChar | RegNameChar)
    add('0', '9', Digit | HexDig | SchemeChar | NcNameChar | RegNameChar)
    add('A', 'F', HexDig)
    add('a', 'f', HexDig)
    addAll("+-.", SchemeChar)
    addAll("-._", NcNameChar)
    addAll("-._~!$&'()*+,;=", RegNameChar) // unreserved non-alphanumerics and sub-delims
    cs
  }

  private inline def is(c: Char, flags: Int): Boolean = c < 128 && (charClasses(c) & flags) != 0

  /** True for a code point in RFC 3987's `ucschar`, which an IRI may carry unencoded. */
  private def isUcsChar(cp: Int): Boolean =
    (cp >= 0xa0 && cp <= 0xd7ff) || (cp >= 0xf900 && cp <= 0xfdcf) ||
      (cp >= 0xfdf0 && cp <= 0xffef) ||
      // Planes 1 to 13, each without its last two code points, then most of plane 14.
      (cp >= 0x10000 && cp <= 0xdfffd && (cp & 0xffff) <= 0xfffd) ||
      (cp >= 0xe1000 && cp <= 0xefffd)

  /** How many chars the `ucschar` at [[i]] takes: 2 for a surrogate pair, 1 for one in the basic
    * plane, 0 if there is no `ucschar` there.
    */
  private def ucsCharLength(s: String, len: Int, i: Int): Int = {
    val c = s.charAt(i)
    if (c < 128) 0
    else if (
      Character.isHighSurrogate(c) && i + 1 < len && Character.isLowSurrogate(s.charAt(i + 1))
    )
      if (isUcsChar(Character.toCodePoint(c, s.charAt(i + 1)))) 2 else 0
    else if (isUcsChar(c.toInt)) 1
    else 0
  }

  /** Check that [[s]] is a URI: `scheme ":" hier-part [ "?" query ] [ "#" fragment ]` */
  def isValidUri(s: String): Boolean = {
    val len = s.length
    len > 0 && is(s.charAt(0), Alpha) && {
      var i = 1
      while (i < len && is(s.charAt(i), SchemeChar)) i += 1
      i < len && s.charAt(i) == ':' && isValidReference(s, len, i + 1, noColon = false)
    }
  }

  /** Check that [[s]] is a CURIE: `[ [ prefix ] ":" ] relative-ref`, where `prefix` is an NCName
    * without colons.
    */
  def isValidCurie(s: String): Boolean = {
    val len = s.length
    var i = 0
    if (len > 0 && (is(s.charAt(0), Alpha) || s.charAt(0) == '_')) {
      i = 1
      while (i < len && is(s.charAt(i), NcNameChar)) i += 1
    }
    if (i < len && s.charAt(i) == ':') isValidReference(s, len, i + 1, noColon = true)
    else if (i == 0) isValidReference(s, len, 0, noColon = true)
    // No colon after the prefix-like start, so it is the start of the first path segment
    else isValidPathQueryFragment(s, len, i, noColon = true)
  }

  /** Check the tail of [[s]] starting at [[from]] as `"//" authority path-abempty` or a path,
    * followed by optional query and fragment.
    *
    * @param noColon
    *   Whether the first path segment can't contain colons, like `path-noscheme` in a relative
    *   reference
    */
  private def isValidReference(s: String, len: Int, from: Int, noColon: Boolean): Boolean =
    if (from + 1 < len && s.charAt(from) == '/' && s.charAt(from + 1) == '/') {
      val i = authorityEnd(s, len, from + 2)
      i >= 0 && isValidPathQueryFragment(s, len, i, noColon = false)
    } else isValidPathQueryFragment(s, len, from, noColon)

  /** Check the tail of [[s]] starting at [[from]] as `path [ "?" query ] [ "#" fragment ]`, where
    * path is any sequence of `pchar` and "/" (the authority, if any, is already consumed).
    */
  private def isValidPathQueryFragment(
      s: String,
      len: Int,
      from: Int,
      noColon: Boolean,
  ): Boolean = {
    var i = from
    var colonForbidden = noColon
    var inFragment = false
    while (i < len && i >= 0) {
      val c = s.charAt(i)
      if (is(c, RegNameChar) || c == '@') i += 1
      else if (ucsCharLength(s, len, i) > 0) i += ucsCharLength(s, len, i)
      else if (c == '/' || c == '?') {
        colonForbidden = false
        i += 1
      } else if (c == ':' && !colonForbidden) i += 1
      else if (c == '%' && isPctEncoded(s, len, i)) i += 3
      else if (c == '#' && !inFragment) {
        colonForbidden = false
        inFragment = true
        i += 1
      } else i = -1
    }
    i >= 0
  }

  /** Parse `authority = [ userinfo "@" ] host [ ":" port ]` of [[s]] starting at [[from]].
    *
    * @return
    *   Index after the authority, or -1 if the authority is invalid or isn't followed by "/", "?",
    *   "#" or the end of input
    */
  private def authorityEnd(s: String, len: Int, from: Int): Int = {
    // Scan the longest run of userinfo chars, which also covers `reg-name [ ":" port ]`
    var i = from
    var hasColon = false
    var isPort = true // whether all chars after the first colon are digits
    var loop = true
    while (loop && i < len) {
      val c = s.charAt(i)
      if (is(c, RegNameChar)) {
        if (hasColon && !is(c, Digit)) isPort = false
        i += 1
      } else if (ucsCharLength(s, len, i) > 0) {
        if (hasColon) isPort = false
        i += ucsCharLength(s, len, i)
      } else if (c == ':') {
        if (hasColon) isPort = false
        hasColon = true
        i += 1
      } else if (c == '%' && isPctEncoded(s, len, i)) {
        if (hasColon) isPort = false
        i += 3
      } else loop = false
    }
    if (i < len && s.charAt(i) == '@') i = hostPortEnd(s, len, i + 1) // it was userinfo
    else if (i == from) i = hostPortEnd(s, len, from) // empty or IP-literal host
    else if (!isPort) i = -1
    if (i < 0 || i == len) i
    else {
      val c = s.charAt(i)
      if (c == '/' || c == '?' || c == '#') i
      else -1
    }
  }

  /** Parse `host [ ":" port ]` of [[s]] starting at [[from]]. IPv4 addresses are not checked
    * separately, as they are also valid `reg-name`s.
    *
    * @return
    *   Index after the port (or the host), or -1 if the IP literal is invalid
    */
  private def hostPortEnd(s: String, len: Int, from: Int): Int = {
    var i = from
    if (i < len && s.charAt(i) == '[') i = ipLiteralEnd(s, len, i + 1)
    else {
      var loop = true
      while (loop && i < len) {
        val c = s.charAt(i)
        if (is(c, RegNameChar)) i += 1
        else if (ucsCharLength(s, len, i) > 0) i += ucsCharLength(s, len, i)
        else if (c == '%' && isPctEncoded(s, len, i)) i += 3
        else loop = false
      }
    }
    if (i >= 0 && i < len && s.charAt(i) == ':') {
      i += 1
      while (i < len && is(s.charAt(i), Digit)) i += 1
    }
    i
  }

  /** Parse `( IPv6address / IPvFuture ) "]"` of [[s]] starting at [[from]] (after "[").
    *
    * @return
    *   Index after "]", or -1 if invalid
    */
  private def ipLiteralEnd(s: String, len: Int, from: Int): Int =
    if (from < len && (s.charAt(from) | 0x20) == 'v') ipVFutureEnd(s, len, from + 1)
    else ipV6End(s, len, from)

  /** Parse `1*HEXDIG "." 1*( unreserved / sub-delims / ":" ) "]"` of [[s]] starting at [[from]]
    * (after "v").
    */
  private def ipVFutureEnd(s: String, len: Int, from: Int): Int = {
    var i = from
    while (i < len && is(s.charAt(i), HexDig)) i += 1
    if (i == from || i >= len || s.charAt(i) != '.') -1
    else {
      i += 1
      val start = i
      while (i < len && (is(s.charAt(i), RegNameChar) || s.charAt(i) == ':')) i += 1
      if (i == start || i >= len || s.charAt(i) != ']') -1
      else i + 1
    }
  }

  /** Parse `IPv6address "]"` of [[s]] starting at [[from]].
    *
    * Instead of matching the 9 alternatives of the RFC 3986 grammar, it counts 16-bit pieces: 8 are
    * required without "::" and at most 7 with it, where an ending IPv4 address counts as 2.
    */
  private def ipV6End(s: String, len: Int, from: Int): Int = {
    var i = from
    var pieces = 0
    var elided = false // whether "::" was seen
    var canClose = false // whether "]" can follow, i.e. right after "::" or a piece
    if (i + 1 < len && s.charAt(i) == ':' && s.charAt(i + 1) == ':') {
      elided = true
      canClose = true
      i += 2
    }
    var end = 0 // 0 while parsing, then the index after "]" or -1
    while (end == 0) {
      if (i >= len) end = -1
      else if (canClose && s.charAt(i) == ']') end = i + 1
      else {
        // h16 = 1*4HEXDIG, which also may be the first dec-octet of an IPv4 address
        val start = i
        var octet = 0
        var isDecimal = true
        while (i < len && i - start < 4 && is(s.charAt(i), HexDig)) {
          val c = s.charAt(i)
          if (c <= '9') octet = octet * 10 + (c - '0')
          else isDecimal = false
          i += 1
        }
        if (i == start || i >= len) end = -1
        else {
          val c = s.charAt(i)
          if (c == '.') {
            // The last 32 bits as an IPv4address
            if (isDecimal && isDecOctet(s.charAt(start), i - start, octet)) {
              i = decOctetEnd(s, len, i + 1)
              if (i >= 0 && i < len && s.charAt(i) == '.') i = decOctetEnd(s, len, i + 1)
              else i = -1
              if (i >= 0 && i < len && s.charAt(i) == '.') i = decOctetEnd(s, len, i + 1)
              else i = -1
              pieces += 2
              end = if (i >= 0 && i < len && s.charAt(i) == ']') i + 1 else -1
            } else end = -1
          } else {
            pieces += 1
            if (c == ']') end = i + 1
            else if (c != ':') end = -1 // also covers a 5th hex digit
            else if (i + 1 < len && s.charAt(i + 1) == ':') {
              if (elided) end = -1
              else {
                elided = true
                canClose = true
                i += 2
              }
            } else {
              canClose = false
              i += 1
            }
          }
        }
      }
    }
    if (end > 0 && (if (elided) pieces <= 7 else pieces == 8)) end
    else -1
  }

  /** Parse `dec-octet` of [[s]] starting at [[from]]: a decimal number in 0-255 without leading
    * zeros.
    *
    * @return
    *   Index after the octet, or -1 if invalid
    */
  private def decOctetEnd(s: String, len: Int, from: Int): Int = {
    var i = from
    var octet = 0
    while (i < len && i - from < 3 && is(s.charAt(i), Digit)) {
      octet = octet * 10 + (s.charAt(i) - '0')
      i += 1
    }
    if (i > from && isDecOctet(s.charAt(from), i - from, octet)) i
    else -1
  }

  private inline def isDecOctet(first: Char, digits: Int, octet: Int): Boolean =
    digits == 1 || (digits <= 3 && first != '0' && octet <= 255)

  /** Check that [[s]] has `HEXDIG HEXDIG` after the "%" at [[i]] */
  private inline def isPctEncoded(s: String, len: Int, i: Int): Boolean =
    i + 2 < len && is(s.charAt(i + 1), HexDig) && is(s.charAt(i + 2), HexDig)
}
