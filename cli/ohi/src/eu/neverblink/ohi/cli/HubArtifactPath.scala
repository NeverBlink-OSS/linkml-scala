package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.fileerror.CliFileErrorCode
import eu.neverblink.ohi.model.generated.lock.Format

import java.net.URI
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import scala.util.control.NonFatal

private[cli] object HubArtifactPath {
  case class Identity(baseUrl: String, namespace: String, name: String, version: String)

  /** Parses the Hub publication route without decoding identity segments. Invalid URLs throw
    * [[CliFileException]].
    */
  def publication(url: String, directory: String): Identity =
    parse(url, directory, artifact = false)

  /** Returns the owning publication of a Hub artifact URL. Invalid URLs throw [[CliFileException]].
    */
  def artifact(url: String, directory: String): Identity = parse(url, directory, artifact = true)

  /** Requires an artifact beneath the exact publication authority and path. Returns an encoded
    * relative filesystem path without inspecting or writing the destination.
    */
  def relative(baseUrl: String, url: String, format: Format, directory: String): Path = {
    val identity = publication(baseUrl, directory)
    if artifact(url, directory) != identity then
      throw invalid(url, directory, "The artifact URL is outside its publication URL.")
    val name = encode(identity.name)
    Path.of(encode(identity.namespace), name, encode(identity.version), s"$name.${suffix(format)}")
  }

  private def parse(url: String, directory: String, artifact: Boolean): Identity = {
    val uri = try URI.create(url)
    catch {
      case NonFatal(cause) => throw invalid(url, directory, "The URL is malformed.", Some(cause))
    }
    val segments = Option(uri.getRawPath).getOrElse("").split("/", -1).toSeq
    val identity = segments.slice(3, 6)
    val trailing = segments.drop(6)
    if !("http".equalsIgnoreCase(uri.getScheme) || "https".equalsIgnoreCase(
        uri.getScheme,
      )) || uri.getRawAuthority == null ||
      uri.getRawQuery != null || uri.getRawFragment != null ||
      segments.take(3) != Seq("", "files", "packages") || identity.size != 3 ||
      identity.exists(value => value.isEmpty || value == "." || value == "..") ||
      (if artifact then
         trailing.mkString("/").isEmpty || trailing.exists(value => value == "." || value == "..")
       else trailing != Seq(""))
    then
      throw invalid(
        url,
        directory,
        "Expected /files/packages/<namespace>/<name>/<version>/ with an artifact path only for file URLs.",
      )
    val baseUrl = s"${uri.getScheme}://${uri.getRawAuthority}${segments.take(6).mkString("/")}/"
    Identity(baseUrl, identity(0), identity(1), identity(2))
  }

  private def encode(value: String): String = {
    val bytes = value.getBytes(UTF_8)
    val encoded = bytes.zipWithIndex.map { (byte, index) =>
      val unsigned = byte & 0xff
      if (unsigned >= 'a' && unsigned <= 'z') || (unsigned >= '0' && unsigned <= '9') ||
        unsigned == '-' || unsigned == '_' || (unsigned == '.' && index != bytes.length - 1)
      then unsigned.toChar.toString
      else f"%%$unsigned%02X"
    }.mkString
    val basename = encoded.takeWhile(_ != '.')
    if Set("con", "prn", "aux", "nul").contains(basename) ||
      basename.matches("(?:com|lpt)[1-9]")
    then f"%%${bytes.head & 0xff}%02X" + encoded.drop(1)
    else encoded
  }

  private def suffix(format: Format): String = format match {
    case Format.LinkmlYaml => "linkml.yaml"
    case Format.OwlTurtle => "owl.ttl"
    case Format.OwlRdfxml => "owl"
    case Format.RdfsTurtle => "rdfs.ttl"
    case Format.OssieYaml => "ossie.yaml"
  }

  private def invalid(
      url: String,
      directory: String,
      reason: String,
      cause: Option[Throwable] = None,
  ): CliFileException = FileDelivery.failure(
    CliFileErrorCode.UnsupportedFilename,
    s"Cannot derive a Hub artifact path from '$url'. $reason Use a canonical Hub publication and advertised file URL.",
    url,
    directory,
    cause = cause,
  )
}
