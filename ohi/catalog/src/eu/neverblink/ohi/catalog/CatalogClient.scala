package eu.neverblink.ohi.catalog

import eu.neverblink.linkml.runtime.{LinkmlAny, Uri}
import eu.neverblink.linkml.yaml.{LinkmlYamlCodec, NodeJson}
import eu.neverblink.ohi.model.{ModelJson, PublicationJson}
import eu.neverblink.ohi.model.generated.Publication
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import eu.neverblink.ohi.model.generated.error.*
import eu.neverblink.ohi.model.generated.search.{SearchHit, SearchResult}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest
import java.io.{EOFException, IOException, InputStream}
import java.net.{HttpURLConnection, SocketTimeoutException, URI, URISyntaxException, URLEncoder}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, StandardCharsets}
import org.virtuslab.yaml.Node
import scala.util.{Try, Using}

/** Synchronous HTTP(S) operations. Omitting both search settings selects ohi.dev. */
final class CatalogClient(config: CatalogConfig = CatalogConfig()) {
  private[catalog] val settings =
    input(CatalogOperation.Configure, config.searchUrl.map(_.original)) {
      require(
        config.searchUrl.isDefined == config.queryBy.isDefined,
        "search_url and query_by must be supplied together",
      )
      config.searchUrl.foreach { value =>
        val uri = transportUri(value.original)
        require(
          uri.getRawQuery == null && uri.getRawFragment == null,
          "search_url must not contain a query or fragment",
        )
        require(
          value.original.endsWith(CatalogClient.SearchPath),
          s"search_url must end with ${CatalogClient.SearchPath}",
        )
      }
      config.copy(
        searchUrl = config.searchUrl.orElse(
          Some(Uri("https://api.ohi.dev/search/collections/packages/documents/search")),
        ),
        queryBy = config.queryBy.orElse(Some("title,name,description,namespace")),
        timeoutMs = Some(config.timeoutMs.getOrElse(30000)),
      )
    }
  private val searchUrl = settings.searchUrl.get.original
  private val base = searchUrl.stripSuffix(CatalogClient.SearchPath)
  private val latestUrl = s"$base/search/collections/packages_latest/documents/search"
  private val timeoutMs = settings.timeoutMs.get

  def search(request: SearchRequest): SearchResult = {
    val effective = request.copy(
      page = Some(request.page.getOrElse(1)),
      perPage = Some(request.perPage.getOrElse(10)),
    )
    val endpoint = if (request.allReleases) searchUrl else latestUrl
    val page = effective.page.get
    val perPage = effective.perPage.get
    val parameters = Seq(
      "q" -> effective.q,
      "query_by" -> settings.queryBy.get,
      "page" -> page.toString,
      "per_page" -> perPage.toString,
    ) ++ effective.filterBy.map("filter_by" -> _) ++ effective.sortBy.map("sort_by" -> _) ++
      effective.facetBy.map("facet_by" -> _) ++
      effective.maxFacetValues.map(value => "max_facet_values" -> value.toString)
    val query = parameters.map { case (key, value) =>
      s"$key=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
    }.mkString("&")
    val bytes = retrieve(s"$endpoint?$query", CatalogOperation.Search, endpoint)
    decode(CatalogOperation.Search, endpoint, CatalogErrorCode.InvalidResponse) {
      CatalogClient.decodeSearch(utf8(bytes), page, perPage)
    }
  }

  /** Returns the search hit for the latest version of `namespace/name`, or None when the catalog
    * has no such ontology.
    */
  def latest(reference: String): Option[SearchHit] = {
    val (namespace, name) = input(CatalogOperation.Search, Some(reference)) {
      Reference.parse(reference) match {
        case Some((namespace, name, None)) => (namespace, name)
        case _ =>
          throw new IllegalArgumentException("use a reference namespace/name without a version")
      }
    }
    val result = search(SearchRequest(q = "*", filterBy = Some(Reference.filter(namespace, name))))
    // Filter matching ignores case and separators, so look-alikes such as w3c/DCAT can come back.
    result.hits.find(hit => hit.namespace == namespace && hit.name == name)
  }

  /** Fetches the publication at an absolute HTTP(S) URL, of the release `namespace/name@version`,
    * or of the latest version of `namespace/name`.
    */
  def inspect(baseUrl: String): Publication = {
    val url = Reference.parse(baseUrl) match {
      // References contain no whitespace, so form encoding is a valid path encoding here.
      case Some((namespace, name, Some(version))) =>
        Seq(namespace, name, version)
          .map(URLEncoder.encode(_, StandardCharsets.UTF_8))
          .mkString(s"$base/files/packages/", "/", "/")
      case Some(_) =>
        latest(baseUrl).getOrElse(
          throw failure(
            CatalogErrorCode.HttpError,
            CatalogOperation.Inspect,
            Some(baseUrl),
            s"no ontology matches $baseUrl",
            Some(404),
          ),
        ).baseUrl.original
      case None => baseUrl
    }
    val bytes = retrieve(url, CatalogOperation.Inspect, url)
    decode(CatalogOperation.Inspect, url, CatalogErrorCode.InvalidResponse) {
      PublicationJson.decode(utf8(bytes))
    }
  }

  def get(url: String): Array[Byte] = retrieve(url, CatalogOperation.Get, url)

  private def transportUri(value: String): URI = {
    val uri = new URI(value)
    require(
      (Option(uri.getScheme).exists(s =>
        s.equalsIgnoreCase("http") || s.equalsIgnoreCase("https"),
      )) &&
        uri.getHost != null && uri.getRawUserInfo == null && uri.getPort <= 65535,
      "use an absolute HTTP(S) URL with a host and without credentials",
    )
    uri
  }

  private def input[A](operation: CatalogOperation, url: Option[String])(body: => A): A =
    try body
    catch {
      case cause: (IllegalArgumentException | URISyntaxException) =>
        throw failure(
          CatalogErrorCode.InvalidInput,
          operation,
          url,
          cause.getMessage,
          cause = Some(cause),
        )
    }

  private def decode[A](operation: CatalogOperation, url: String, code: CatalogErrorCode)(
      body: => A,
  ): A =
    try body
    catch {
      case cause: IllegalArgumentException =>
        throw failure(code, operation, Some(url), cause.getMessage, Some(200), Some(cause))
      case cause: CharacterCodingException =>
        throw failure(
          code,
          operation,
          Some(url),
          "response is not valid UTF-8",
          Some(200),
          Some(cause),
        )
    }

  private def utf8(bytes: Array[Byte]): String =
    StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString

  private def retrieve(
      target: String,
      operation: CatalogOperation,
      errorUrl: String,
  ): Array[Byte] = {
    val uri = input(operation, Some(errorUrl))(transportUri(target))
    var status = Option.empty[Int]
    var connection = Option.empty[HttpURLConnection]
    try {
      val http = uri.toURL.openConnection().asInstanceOf[HttpURLConnection]
      connection = Some(http)
      http.setInstanceFollowRedirects(false)
      http.setConnectTimeout(timeoutMs)
      http.setReadTimeout(timeoutMs)
      http.setRequestProperty("Accept-Encoding", "identity")
      val responseCode = http.getResponseCode
      if (responseCode < 0) throw new IOException("server returned no valid HTTP status")
      status = Some(responseCode)
      if (status.get != 200) {
        // Once received, an HTTP failure takes precedence over an unreadable error body.
        val response = try {
          val stream = if (status.get >= 400) http.getErrorStream else http.getInputStream
          Option(stream).flatMap(value =>
            CatalogClient.responseObject(utf8(Using.resource(value)(readBody(http, _)))),
          )
        } catch {
          case _: IOException => None
        }
        throw new CatalogException(
          CatalogError(
            code = CatalogErrorCode.HttpError,
            message = s"HTTP ${status.get} for $errorUrl",
            operation = operation,
            url = Some(errorUrl),
            status = status,
            responseJson = response,
          ),
        )
      }
      Using.resource(http.getInputStream) { stream =>
        val coding = Option(http.getContentEncoding)
        if (coding.exists(value => !value.equalsIgnoreCase("identity"))) {
          throw failure(
            CatalogErrorCode.InvalidResponse,
            operation,
            Some(errorUrl),
            "server returned unsupported content encoding",
            status,
          )
        }
        readBody(http, stream)
      }
    } catch {
      case cause: SocketTimeoutException =>
        throw failure(
          CatalogErrorCode.Timeout,
          operation,
          Some(errorUrl),
          "request timed out",
          status,
          Some(cause),
        )
      case cause: IOException =>
        throw failure(
          CatalogErrorCode.ConnectionError,
          operation,
          Some(errorUrl),
          cause.getMessage,
          status,
          Some(cause),
        )
    } finally connection.foreach(_.disconnect())
  }

  private def readBody(http: HttpURLConnection, stream: InputStream): Array[Byte] = {
    val bytes = stream.readAllBytes()
    // HttpURLConnection can return early EOF without detecting an incomplete fixed-length body.
    if (bytes.length.toLong < http.getContentLengthLong) {
      throw new EOFException("response ended before its declared Content-Length")
    }
    bytes
  }

  private def failure(
      code: CatalogErrorCode,
      operation: CatalogOperation,
      url: Option[String],
      message: String,
      status: Option[Int] = None,
      cause: Option[Throwable] = None,
  ): CatalogException = new CatalogException(
    CatalogError(
      code = code,
      message = message,
      operation = operation,
      url = url,
      status = status,
    ),
    cause,
  )
}

private[catalog] object CatalogClient {
  private val searchCodec: LinkmlYamlCodec[TypesenseResponse] = LinkmlYamlCodec.derived
  private val SearchPath = "/search/collections/packages/documents/search"

  def decodeSearch(text: String, page: Int, perPage: Int): SearchResult = {
    val response = ModelJson.decode(searchCodec, text)
    require(response.groupedHits.isEmpty, "grouped search responses are unsupported")
    val result = response.result
    require(result.page == page, "response page differs from requested page")
    require(result.perPage == perPage, "response per_page differs from requested per_page")
    require(result.hits.size <= perPage, "response contains more hits than requested")
    result
  }

  /** The JSON object in an HTTP error body, if it has one. */
  def responseObject(text: String): Option[LinkmlAny] =
    Try(ModelJson.parse(text)).toOption.collect { case node: Node.MappingNode =>
      LinkmlAny(NodeJson.write(node))
    }
}
