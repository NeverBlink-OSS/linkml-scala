package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.LinkmlAny
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import org.virtuslab.yaml.parseYaml
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode

private[cli] object ErrorOutput {
  private val mapper = JsonMapper.builder().build()

  private def heading(message: String, colored: Boolean): String =
    s"${CliFormatting.styled("Error:", "1;31", colored)} ${CliFormatting.clean(message)}"

  private def field(label: String, value: String, colored: Boolean): String =
    s"$label: ${CliFormatting.styled(value, "36", colored)}"

  /** Returns the human diagnostic and recovery separately so callers can insert completed effects.
    */
  def catalog(
      code: String,
      operation: String,
      message: String,
      url: Option[String],
      status: Option[Int],
      responseJson: Option[LinkmlAny],
      timeoutOption: Boolean,
      colored: Boolean = false,
      summaryOverride: Option[String] = None,
  ): (String, String) = {
    val service = operation match {
      case "SEARCH" => "The search service"
      case "GET" => "The download service"
      case _ => "The service"
    }
    val (summary, recovery) = code match {
      case "HTTP_ERROR" =>
        status match {
          case Some(404) if operation == "INSPECT" =>
            "Release not found." -> "Check the URL or find the release with 'ohi search'."
          case Some(404) if operation == "GET" =>
            "File not found." -> "Check the file URL or inspect the release to choose an available file."
          case Some(404) =>
            "Search endpoint not found." -> "Check --search-url or omit it and --query-by to use ohi.dev."
          case Some(401 | 403) =>
            "Access denied." -> "Check that you can access this URL, or contact the catalog operator."
          case Some(429) =>
            s"$service received too many requests." -> "Try the same command again later."
          case Some(value) if value >= 500 =>
            s"$service is unavailable." -> "Try the same command again later."
          case Some(value) if value >= 300 && value < 400 =>
            "The service redirected the request." -> "Use the final URL supplied by the catalog operator."
          case _ =>
            "The service rejected the request." -> "Correct the reported request problem and try again."
        }
      case "TIMEOUT" =>
        val summary = operation match {
          case "SEARCH" => "Search timed out."
          case "GET" => "Download timed out."
          case _ => "Release inspection timed out."
        }
        summary -> ("Check connectivity and try again." +
          (if timeoutOption then " If the service needs more time, increase --timeout-ms." else ""))
      case "CONNECTION_ERROR" =>
        "Could not complete the connection." -> "Check connectivity and try again."
      case "INVALID_RESPONSE" =>
        (if operation == "INSPECT" then "Cannot read the release metadata."
         else "Cannot read the service response.") ->
          "Try again, or report the response problem to the catalog operator."
      case _ => "Invalid request." -> "Correct the supplied value and try again."
    }
    val detail = if code == "HTTP_ERROR" then
      responseJson.flatMap { response =>
        val data = mapper.readTree(response.value)
        Seq(
          data.get("message"),
          data.get("error"),
          data.path("error").get("message"),
          data.path("error").get("reason"),
        )
          .find(value => value != null && value.isString && value.stringValue().nonEmpty)
          .map(_.stringValue())
      }
    else if code == "TIMEOUT" then None
    else Some(message)
    val lines = Seq(heading(summaryOverride.getOrElse(summary), colored)) ++
      url.map(field("URL", _, colored)) ++ summaryOverride.map(_ => s"Cause: $summary") ++
      detail.map(value => s"Details: ${CliFormatting.clean(value)}")
    lines.mkString("\n") -> recovery
  }

  /** Retains storage diagnostics and distinguishes existing entries from owned incomplete files. */
  def file(
      code: String,
      message: String,
      url: String,
      directory: String,
      path: Option[String],
      partialPath: Option[String],
      colored: Boolean = false,
  ): (String, String) = {
    val (summary, recovery) = code match {
      case "DESTINATION_EXISTS" =>
        "Destination already exists." ->
          "Choose another --to directory or move the existing entry, then retry."
      case "UNSUPPORTED_FILENAME" =>
        "The URL does not name a usable file." -> "Use a URL ending in one usable filename."
      case "INVALID_DESTINATION" =>
        "Cannot use the destination directory." -> "Correct the directory path or its permissions, then retry."
      case "FILE_WRITE_FAILED" =>
        "Could not finish writing the downloaded file." ->
          partialPath.fold("Check write permissions and available space, then retry.")(_ =>
            "Correct the storage problem and remove the incomplete file before retrying.",
          )
      case "FILE_READ_FAILED" =>
        "Could not verify the existing file." -> "Check its read permissions and retry."
      case "HASH_MISMATCH" =>
        "File checksum does not match the lock." -> "Follow the correction above, then repeat the same command."
      case _ =>
        "The download filenames conflict." -> "Follow the correction above, then repeat the same command."
    }
    val location = if code == "UNSUPPORTED_FILENAME" then Seq(field("URL", url, colored))
    else
      path.map(field("Path", _, colored)).toSeq ++
        (if code == "INVALID_DESTINATION" then Seq(field("Directory", directory, colored)) else Nil)
    val details = if code == "DESTINATION_EXISTS" || code == "UNSUPPORTED_FILENAME" then Nil
    else Seq(s"Details: ${CliFormatting.clean(message)}")
    (Seq(heading(summary, colored)) ++ location ++ details ++
      partialPath.map(field("Incomplete file retained", _, colored))).mkString("\n") -> recovery
  }

  def lock(
      code: String,
      operation: String,
      message: String,
      path: Option[String],
      colored: Boolean = false,
  ): (String, String) = {
    val summary = if code == "UNSUPPORTED_VERSION" then "The lock format version is unsupported."
    else if operation == "WRITE" then "Could not write the project lock."
    else "Cannot read the lock."
    val recovery = if code == "UNSUPPORTED_VERSION" then
      "For a project lock, run 'ohi lock --project <project-directory>'.\n" +
        "For a standalone lock, regenerate it with the application that created it.\n" +
        "Then repeat the download command."
    else "Correct the reported lock problem and try again."
    (Seq(heading(summary, colored)) ++ path.map(field("Path", _, colored)) :+
      s"Details: ${CliFormatting.clean(message)}").mkString("\n") -> recovery
  }

  // Imported records have distinct generated Scala types.
  def adapt[T: LinkmlYamlCodec](json: String): T =
    summon[LinkmlYamlCodec[T]].decode(parseYaml(json).fold(throw _, identity))

  /** Restores raw JSON to preserve nulls and numeric values. */
  def withCatalogResponse(json: String, response: Option[LinkmlAny]): String = {
    val data = mapper.readTree(json)
    response.foreach(value =>
      data.get("catalog_error").asInstanceOf[ObjectNode]
        .set("response_json", mapper.readTree(value.value)),
    )
    mapper.writeValueAsString(data)
  }
}
