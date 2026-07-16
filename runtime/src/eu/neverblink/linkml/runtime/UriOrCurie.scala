package eu.neverblink.linkml.runtime

import scala.util.matching.Regex

sealed trait UriOrCurie {
  def original: String

  def +(s: String): UriOrCurie = UriOrCurie(original + s)

  def uri(implicit resolver: PrefixResolver): String

  def curie(implicit resolver: PrefixResolver): String
}

object UriOrCurie {
  def apply(s: String): UriOrCurie =
    if (s.startsWith("urn:") || s.contains("://")) new Uri(s)
    else new Curie(s)
}

case class Uri(original: String) extends UriOrCurie {
  require(UriCurieValidator.validateUri(original).isDefined, s"Illegal uri value: $original")

  def uri(implicit resolver: PrefixResolver): String = original

  def curie(implicit resolver: PrefixResolver): String = resolver.compact(original)
}

case class Curie(original: String) extends UriOrCurie {
  require(UriCurieValidator.validateCurie(original).isDefined, s"Illegal curie value: $original")

  def uri(implicit resolver: PrefixResolver): String = resolver.expand(original)

  def curie(implicit resolver: PrefixResolver): String = original
}

type NcName = String

trait PrefixResolver {

  /** Expand a CURIE into a URI using prefixes defined in this resolver.
    * @throws java.lang.RuntimeException
    *   If this prefix resolver can't expand the CURIE
    */
  def expand(curie: String): String

  /** Compact a URI into a CURIE using prefixes defined in this resolver.
    * @throws java.lang.RuntimeException
    *   If this prefix resolver can't compact the URI
    */
  def compact(uri: String): String

  /** Get the expansion of a prefix from this resolver.
    * @return
    *   The URI [[prefix]] maps to, or None if the prefix isn't found.
    */
  def resolvePrefix(prefix: String): Option[String]
}

class BasicPrefixResolver(schemaId: String) extends PrefixResolver {
  private val prefixToUri = new java.util.HashMap[String, String]
  private val uriToPrefix = new java.util.HashMap[String, String]

  def add(prefix: String, uri: String): Unit = {
    val u = new java.net.URI(uri)
    var normalizedUri = u.normalize().toString
    if (
      !normalizedUri.endsWith("/") && !normalizedUri.endsWith("?") && !normalizedUri.endsWith("#")
    ) normalizedUri += "/"
    prefixToUri.put(prefix, normalizedUri)
    uriToPrefix.put(normalizedUri, prefix)
  }

  override def resolvePrefix(prefix: String): Option[String] =
    if prefixToUri.containsKey(prefix) then Option(prefixToUri.get(prefix))
    else None

  override def expand(curie: String): String = {
    val parts = curie.split(':')
    if (parts.length == 2) {
      val prefix = parts(0)
      val baseUri = prefixToUri.get(prefix)
      if (baseUri != null) baseUri + parts(1)
      else sys.error(s"Unknown prefix '$prefix' in schema $schemaId")
    } else curie // relative reference
  }

  override def compact(uri: String): String = {
    val normalizedUri = new java.net.URI(uri).normalize()
    var curie = normalizedUri.getFragment
    val baseUri =
      if (curie ne null) {
        normalizedUri.toString.stripSuffix(curie)
      } else if ({
        curie = normalizedUri.getQuery
        curie ne null
      }) {
        normalizedUri.toString.stripSuffix(curie)
      } else if ({
        curie = getLastPathSegment(normalizedUri)
        curie.nonEmpty
      }) {
        new java.net.URI(
          normalizedUri.getScheme,
          normalizedUri.getAuthority,
          normalizedUri.getPath.stripSuffix(curie),
          null,
          null,
        ).toString
      } else normalizedUri.toString
    val prefix = uriToPrefix.get(baseUri)
    if (prefix eq null) sys.error(s"Unknown uri: $baseUri")
    if ((curie eq null) || curie.isEmpty) {
      sys.error(s"Cannot compact uri without fragment, query, or last path segment: $uri")
    }
    s"$prefix:$curie"
  }

  private def getLastPathSegment(uri: java.net.URI): String =
    Option(uri.getPath) match {
      case Some(path) if path.nonEmpty && path != "/" =>
        path.stripSuffix("/").split("/").lastOption.getOrElse("")
      case _ => ""
    }
}

/** Regular-expression-based URI and CURIE validation functions
  *
  * These regex are directly derived from the official sources mentioned in each section. They use
  * the (?x) flag for verbose parsing (ignoring whitespace).
  */
object UriCurieValidator {
  // Define DIGIT according RFC2234 section 3.4
  private val digit = "[0-9]"

  // Define ALPHA according RFC2234 section 6.1
  private val alpha = "[A-Za-z]"

  // Define HEXDIG according RFC2234 section 6.1
  private val hexdig = "[0-9A-Fa-f]"

  // pct-encoded = "%" HEXDIG HEXDIG
  private val pctEncoded = raw"%$hexdig$hexdig"

  // unreserved = ALPHA / DIGIT / "-" / "." / "_" / "~"
  private val unreserved = raw"(?:$alpha|$digit|\-|\.|_|~)"

  // gen-delims = ":" / "/" / "?" / "#" / "[" / "]" / "@"
  // private val genDelims = raw"(?::|/|\?|\#|\[|\]|@)"

  // sub-delims = "!" / "$" / "&" / "'" / "(" ...
  // Note: Escaping $ as $$ is required in Scala interpolation
  private val subDelims = raw"(?:!|\$$|&|'|\(|\)|\*|\+|,|;|=)"

  // pchar = unreserved / pct-encoded / sub-delims / ":" / "@"
  private val pchar = raw"(?:$unreserved|$pctEncoded|$subDelims|:|@)"

  // reserved = gen-delims / sub-delims
  // private val reserved = raw"(?:$genDelims|$subDelims)"

  // dec-octet = DIGIT / %x31-39 DIGIT / "1" 2DIGIT / "2" %x30-34 DIGIT / "25" %x30-35
  private val decOctet = raw"(?:$digit|[1-9]$digit|1$digit{2}|2[0-4]$digit|25[0-5])"

  // IPv4address = dec-octet "." dec-octet "." dec-octet "." dec-octet
  private val ipV4Address = raw"$decOctet\.$decOctet\.$decOctet\.$decOctet"

  // h16 = 1*4HEXDIG
  private val h16 = raw"(?:$hexdig){1,4}"

  // ls32 = ( h16 ":" h16 ) / IPv4address
  private val ls32 = raw"(?:(?:$h16:$h16)|$ipV4Address)"

  // IPv6address
  private val ipV6Address =
    raw"(?:(?:$h16:){6}$ls32|::(?:$h16:){5}$ls32|(?:$h16)?::(?:$h16:){4}$ls32|(?:(?:$h16:)$h16)?::(?:$h16:){3}$ls32|(?:(?:$h16:){1,2}$h16)?::(?:$h16:){2}$ls32|(?:(?:$h16:){1,3}$h16)?::$h16:$ls32|(?:(?:$h16:){1,4}$h16)?::$ls32|(?:(?:$h16:){1,5}$h16)?::$h16|(?:(?:$h16:){1,6}$h16)?::)"

  // IPvFuture = "v" 1*HEXDIG "." 1*( unreserved / sub-delims / ":" )
  private val ipVFuture = raw"v$hexdig+\.(?:$unreserved|$subDelims|:)+"

  // IP-literal = "[" ( IPv6address / IPvFuture  ) "]"
  private val ipLiteral = raw"\[(?:$ipV6Address|$ipVFuture)\]"

  // reg-name = *( unreserved / pct-encoded / sub-delims )
  private val regName = raw"(?:$unreserved|$pctEncoded|$subDelims)*"

  // required for Path
  private val segment = raw"$pchar*"
  private val segmentNz = raw"$pchar+"
  private val segmentNzNc = raw"(?:$unreserved|$pctEncoded|$subDelims|@)+"

  // Define SCHEME according RFC3986 section 3.1
  private val scheme = raw"(?<scheme>$alpha(?:$alpha|$digit|\+|\-|\.)*)"

  // Define AUTHORITY according RFC3986 section 3.2
  private val userinfo = raw"(?<userinfo>(?:$unreserved|$pctEncoded|$subDelims|:)*)"
  private val host = raw"(?<host>$ipLiteral|$ipV4Address|$regName)"
  private val port = raw"(?<port>($digit)*)"
  private val authority = raw"(?<authority>(?:$userinfo@)?$host(?::$port)?)"

  // Define different PATHs according RFC3986 section 3.3
  private val pathAbempty = raw"(/$segment)*"
  private val pathAbsolute = raw"(/(?:$segmentNz(?:/$segment)*)?)"
  private val pathNoscheme = raw"($segmentNzNc(?:/$segment)*)"
  private val pathRootless = raw"($segmentNz(?:/$segment)*)"
  private val pathEmpty = ""
  // private val path = raw"(?:$pathAbempty|$pathAbsolute|$pathNoscheme|$pathRootless|$pathEmpty)"

  // Define QUERY according RFC3986 section 3.4
  private val query = raw"(?<query>(?:$pchar|/|\?)*)"

  // Define FRAGMENT according RFC3986 section 3.5
  private val fragment = raw"(?<fragment>(?:$pchar|/|\?)*)"

  // Define URI and HIERARCHICAL PATH according RFC3986 section 3
  private val hierPart =
    raw"(?<hierPart>(?://$authority$pathAbempty)|$pathAbsolute|$pathRootless|$pathEmpty)"

  private val uri = raw"(?<uri>$scheme:$hierPart(?:\?$query)?(?:\#$fragment)?)"

  // Define RELATIVE REFERENCE according RFC3986 section 4.2
  private val relativeRef =
    raw"(?<relativeRef>(?:(?://$authority(?<pathAbempty>$pathAbempty))|(?<pathAbsolute>$pathAbsolute)|(?<pathNoscheme>$pathNoscheme)|(?<pathEmpty>$pathEmpty))(?:\?$query)?(?:\#$fragment)?)"

  // Define ABSOLUTE URI according RFC3986 section 4.3
  // private val absoluteUri = raw"(?<absoluteUri>$scheme:$hierPart(?:\?$query)?)"

  // Define CURIE according W3C CURIE Syntax 1.0
  private val ncNameChar = raw"(?:$alpha|$digit|\.|\-|_)"
  private val prefix = raw"(?:$alpha|_)(?:$ncNameChar)*"
  private val curie = raw"(?<curie>(?:(?<prefix>$prefix)?:)?$relativeRef)"
  // private val safeCurie = raw"(?<safeCurie>\[$curie\])"

  // Compile the regular expressions for better performance
  // Note: $$ escapes the string interpolator, resulting in an exact end-of-string '$' token
  private val uriValidator: Regex = raw"^$uri$$".r
  // private val uriRelativeRefValidator: Regex  = raw"^$relativeRef$$".r
  // private val absUriValidator: Regex          = raw"^$absoluteUri$$".r
  private val curieValidator: Regex = raw"^$curie$$".r
  // private val safeCurieValidator: Regex       = raw"^$safeCurie$$".r

  def validateUri(input: String): Option[Regex.Match] = uriValidator.findFirstMatchIn(input)

  // URI-reference = URI / relative-ref
  // def validateUriReference(input: String): Option[Regex.Match] =
  //  uriValidator.findFirstMatchIn(input).orElse(uriRelativeRefValidator.findFirstMatchIn(input))

  def validateCurie(input: String): Option[Regex.Match] = curieValidator.findFirstMatchIn(input)

  // def validateSafeCurie(input: String): Option[Regex.Match] = safeCurieValidator.findFirstMatchIn(input)
}
