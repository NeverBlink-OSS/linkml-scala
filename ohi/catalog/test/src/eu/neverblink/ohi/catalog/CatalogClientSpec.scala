package eu.neverblink.ohi.catalog

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import eu.neverblink.ohi.model.generated.error.{CatalogError, CatalogErrorCode, CatalogOperation}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.{InetSocketAddress, ServerSocket, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.concurrent.{Callable, ConcurrentLinkedQueue, Executors, TimeUnit}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*

class CatalogClientSpec extends AnyWordSpec, Matchers {
  private val binary = Array[Byte](0, -1, 65, -128)
  private val searchPath = "/search/collections/packages/documents/search"
  private val latestPath = "/search/collections/packages_latest/documents/search"

  private def withServer(
      respond: HttpExchange => Unit,
  )(check: (String, ConcurrentLinkedQueue[(String, String)]) => Unit): Unit = {
    val requests = new ConcurrentLinkedQueue[(String, String)]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange => {
        requests.add(
          exchange.getRequestURI.toString -> exchange.getRequestHeaders.getFirst("Accept-Encoding"),
        )
        exchange.getResponseHeaders.set("Cache-Control", "no-store")
        try respond(exchange)
        finally exchange.close()
      },
    )
    server.start()
    try check(s"http://127.0.0.1:${server.getAddress.getPort}", requests)
    finally server.stop(0)
  }

  private def reply(exchange: HttpExchange, status: Int, bytes: Array[Byte]): Unit = {
    exchange.sendResponseHeaders(status, if (bytes.isEmpty) -1L else bytes.length.toLong)
    exchange.getResponseBody.write(bytes)
  }

  private def configured(url: String, timeout: Int = 30000): CatalogClient = new CatalogClient(
    CatalogConfig(
      searchUrl = Some(Uri(url)),
      queryBy = Some("name,description"),
      timeoutMs = Some(timeout),
    ),
  )

  private def error(
      code: CatalogErrorCode,
      operation: CatalogOperation,
      url: Option[String],
      status: Option[Int],
  )(
      action: => Any,
  ): CatalogError = {
    val exception = intercept[CatalogException](action)
    exception.getMessage shouldBe exception.error.message
    exception.error.code shouldBe code
    exception.error.operation shouldBe operation
    exception.error.url shouldBe url
    exception.error.status shouldBe status
    exception.error
  }

  private def searchResponse(perPage: Int, hits: Seq[ujson.Obj] = Seq()): Array[Byte] =
    ujson.write(
      ujson.Obj(
        "hits" -> ujson.Arr.from(hits),
        "found" -> hits.size,
        "page" -> 1,
        "request_params" -> ujson.Obj("per_page" -> perPage),
        "search_cutoff" -> false,
      ),
    ).getBytes(UTF_8)

  private def hit(baseUrl: String, namespace: String, name: String, version: String): ujson.Obj =
    ujson.Obj(
      "document" -> ujson.Obj(
        "base_url" -> baseUrl,
        "namespace" -> namespace,
        "name" -> name,
        "version" -> version,
        "title" -> "Sensors",
        "description" -> "Sensor measurements",
        "formats" -> ujson.Arr("owl-turtle"),
        "licenses" -> ujson.Arr(),
        "published_at" -> "2026-10-13T12:30:00Z",
      ),
    )

  private def packageJson: Array[Byte] = Files.readAllBytes(
    Path.of(sys.env("MILL_WORKSPACE_ROOT"), "ohi", "examples", "sensors-package.json"),
  )

  private def parameters(target: String): Map[String, String] =
    target.split("\\?", 2)(1).split("&").map { pair =>
      val parts = pair.split("=", 2)
      parts(0) -> URLDecoder.decode(parts(1), UTF_8)
    }.toMap

  private def withSocketResponse(response: String, endBody: Boolean = false)(
      check: String => Unit,
  ): Unit = {
    val listener = new ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
    val executor = Executors.newSingleThreadExecutor()
    val closed = executor.submit(new Callable[Int] {
      override def call(): Int = {
        val socket = listener.accept()
        try {
          socket.setSoTimeout(5000)
          val reader = new BufferedReader(new InputStreamReader(socket.getInputStream, UTF_8))
          while (reader.readLine() != "") {}
          // Disable server keep-alive so EOF observes cleanup rather than host connection pooling.
          socket.getOutputStream.write(
            response.replace(
              "Cache-Control: no-store",
              "Cache-Control: no-store\r\nConnection: close",
            ).getBytes(UTF_8),
          )
          socket.getOutputStream.flush()
          if (endBody) socket.shutdownOutput()
          socket.getInputStream.read()
        } finally socket.close()
      }
    })
    try {
      check(s"http://127.0.0.1:${listener.getLocalPort}/file")
      closed.get(5, TimeUnit.SECONDS) shouldBe -1
    } finally {
      listener.close()
      executor.shutdownNow()
    }
  }

  "CatalogClient configuration and input" should {
    for (
      (label, configuration) <- Seq[(String, String => CatalogConfig)](
        "missing query_by" -> (url => CatalogConfig(searchUrl = Some(Uri(url)))),
        "missing search_url" -> (_ => CatalogConfig(queryBy = Some("name"))),
        "search query" -> (url =>
          CatalogConfig(searchUrl = Some(Uri(url + "?q=x")), queryBy = Some("name"))
        ),
        "search fragment" -> (url =>
          CatalogConfig(searchUrl = Some(Uri(url + "#fragment")), queryBy = Some("name"))
        ),
        "search_url without the search path" -> (url =>
          CatalogConfig(searchUrl = Some(Uri(url + "/search")), queryBy = Some("name"))
        ),
      )
    ) {
      s"reject $label before sending" in {
        withServer(exchange => reply(exchange, 200, binary)) { (url, requests) =>
          val config = configuration(url)
          error(
            CatalogErrorCode.InvalidInput,
            CatalogOperation.Configure,
            config.searchUrl.map(_.original),
            None,
          )(
            new CatalogClient(config),
          )
          requests shouldBe empty
        }
      }
    }

    "use the production catalog and preserve a supplied timeout" in {
      val client = new CatalogClient(CatalogConfig(timeoutMs = Some(275)))
      client.settings.searchUrl.map(_.original) shouldBe
        Some("https://api.ohi.dev/search/collections/packages/documents/search")
      client.settings.queryBy shouldBe Some("title,name,description,namespace")
      client.settings.timeoutMs shouldBe Some(275)
    }

    for (
      target <- Seq(
        "urn:ohi:file",
        "/relative",
        "http:///missing-host",
        "https://user:secret@example.org/file",
        "http://127.0.0.1:65536/file",
      )
    ) {
      s"reject unsupported transport target $target" in {
        error(CatalogErrorCode.InvalidInput, CatalogOperation.Get, Some(target), None)(
          new CatalogClient().get(target),
        )
      }
    }
  }

  "CatalogClient.search" should {
    for (size <- Seq(None, Some(1), Some(250))) {
      s"send default page and accepted page size $size to the latest endpoint" in {
        val expectedSize = size.getOrElse(10)
        withServer(exchange => reply(exchange, 200, searchResponse(expectedSize))) {
          (url, requests) =>
            val result = configured(url + searchPath).search(SearchRequest(q = "*", perPage = size))
            result.page shouldBe 1
            result.perPage shouldBe expectedSize
            result.hits shouldBe empty
            requests.asScala.toSeq shouldBe Seq(
              "/search/collections/packages_latest/documents/search" +
                s"?q=*&query_by=name%2Cdescription&page=1&per_page=$expectedSize" -> "identity",
            )
        }
      }
    }

    "send an all_releases search to search_url without the option" in {
      withServer(exchange => reply(exchange, 200, searchResponse(10))) { (url, requests) =>
        configured(url + searchPath).search(SearchRequest(q = "*", allReleases = true))
        requests.asScala.toSeq shouldBe Seq(
          "/search/collections/packages/documents/search" +
            "?q=*&query_by=name%2Cdescription&page=1&per_page=10" -> "identity",
        )
      }
    }

    "encode each original parameter once and preserve duplicate backend order with one request" in {
      val raw = ujson.write(
        ujson.Obj(
          "hits" -> ujson.Arr.from(
            Seq("z", "a", "z").map(name => hit(s"urn:ohi:$name", "example", name, "1.0")),
          ),
          "found" -> 8,
          "page" -> 2,
          "request_params" -> ujson.Obj("per_page" -> 3),
          "search_cutoff" -> false,
        ),
      )
      withServer(exchange => reply(exchange, 200, raw.getBytes(UTF_8))) { (url, requests) =>
        val result = configured(url + searchPath).search(
          SearchRequest(
            q = "ü + & sensors",
            page = Some(2),
            perPage = Some(3),
            filterBy = Some("namespace:=[`a+b`, `x&y`]"),
            sortBy = Some("name:asc"),
            facetBy = Some("namespace,formats"),
            maxFacetValues = Some(30),
          ),
        )
        result.hits.map(_.baseUrl.original) shouldBe Seq("urn:ohi:z", "urn:ohi:a", "urn:ohi:z")
        result.total shouldBe 8
        requests.size() shouldBe 1
        val target = requests.peek()._1
        target.takeWhile(_ != '?') shouldBe "/search/collections/packages_latest/documents/search"
        parameters(target) shouldBe Map(
          "q" -> "ü + & sensors",
          "query_by" -> "name,description",
          "page" -> "2",
          "per_page" -> "3",
          "filter_by" -> "namespace:=[`a+b`, `x&y`]",
          "sort_by" -> "name:asc",
          "facet_by" -> "namespace,formats",
          "max_facet_values" -> "30",
        )
      }
    }
  }

  "CatalogClient.inspect and get" should {
    "inspect the exact opaque path and query without following dependencies" in {
      withServer(exchange => reply(exchange, 200, packageJson)) { (url, requests) =>
        val result = new CatalogClient().inspect(url + "/opaque?signature=a%2Bb#fragment")
        result.title shouldBe "Sensors"
        result.artifacts.head.sizeBytes shouldBe 42
        result.dependencies.map(_.baseUrl.original) shouldBe Seq(
          "https://example.org/packages/units/2.0.0/",
        )
        requests.asScala.toSeq shouldBe Seq("/opaque?signature=a%2Bb" -> "identity")
      }
    }

    "return binary bytes unchanged and invoke transport on repeated retrieval" in {
      withServer(exchange => reply(exchange, 200, binary)) { (url, requests) =>
        val client = new CatalogClient()
        client.get(url + "/file").toSeq shouldBe Seq[Byte](0, -1, 65, -128)
        requests.size() shouldBe 1
        client.get(url + "/file").toSeq shouldBe Seq[Byte](0, -1, 65, -128)
        requests.asScala.toSeq shouldBe Seq.fill(2)("/file" -> "identity")
      }
    }

    "return an empty file" in {
      withServer(exchange => reply(exchange, 200, Array.emptyByteArray)) { (url, _) =>
        new CatalogClient().get(url) shouldBe empty
      }
    }
  }

  "CatalogClient.latest and inspect with references" should {
    "derive the latest endpoint and release URLs from a search_url below a path" in {
      withServer { exchange =>
        val body =
          if (exchange.getRequestURI.getRawPath.startsWith("/hub/search/")) searchResponse(10)
          else packageJson
        reply(exchange, 200, body)
      } { (url, requests) =>
        val client = configured(url + "/hub/search/collections/packages/documents/search")
        client.search(SearchRequest(q = "*"))
        client.inspect("w3c/dcat@2")
        requests.asScala.toSeq.map(_._1.takeWhile(_ != '?')) shouldBe Seq(
          "/hub/search/collections/packages_latest/documents/search",
          "/hub/files/packages/w3c/dcat/2/",
        )
      }
    }

    "inspect a versioned reference with one GET of its percent-encoded release path" in {
      withServer(exchange => reply(exchange, 200, packageJson)) { (url, requests) =>
        configured(url + searchPath).inspect("team:a/model%x@v1.0é")
        requests.asScala.toSeq shouldBe Seq(
          "/files/packages/team%3Aa/model%25x/v1.0%C3%A9/" -> "identity",
        )
      }
    }

    "inspect the base_url of the latest-version hit with the exact namespace and name, skipping a look-alike" in {
      withServer { exchange =>
        val origin = s"http://127.0.0.1:${exchange.getLocalAddress.getPort}"
        val body =
          if (exchange.getRequestURI.getRawPath == latestPath)
            searchResponse(
              10,
              Seq(
                hit(s"$origin/files/packages/w3c/DCAT/9/", "w3c", "DCAT", "9"),
                hit(s"$origin/files/packages/w3c/dcat/3/", "w3c", "dcat", "3"),
              ),
            )
          else packageJson
        reply(exchange, 200, body)
      } { (url, requests) =>
        configured(url + searchPath).inspect("w3c/dcat")
        val targets = requests.asScala.toSeq.map(_._1)
        targets.map(_.takeWhile(_ != '?')) shouldBe Seq(
          "/search/collections/packages_latest/documents/search",
          "/files/packages/w3c/dcat/3/",
        )
        parameters(targets.head) shouldBe Map(
          "q" -> "*",
          "query_by" -> "name,description",
          "page" -> "1",
          "per_page" -> "10",
          "filter_by" -> "namespace:=`w3c` && name:=`dcat`",
        )
      }
    }

    "report an ontology without a latest-version hit of the exact namespace and name as an INSPECT 404 without a GET" in {
      withServer(exchange => reply(exchange, 200, searchResponse(10))) { (url, requests) =>
        error(CatalogErrorCode.HttpError, CatalogOperation.Inspect, Some("w3c/nope"), Some(404))(
          configured(url + searchPath).inspect("w3c/nope"),
        )
        requests.asScala.toSeq.map(_._1.takeWhile(_ != '?')) shouldBe Seq(latestPath)
      }
    }

    "reject a versioned reference for latest without a request" in {
      withServer(exchange => reply(exchange, 200, binary)) { (url, requests) =>
        error(CatalogErrorCode.InvalidInput, CatalogOperation.Search, Some("w3c/dcat@2"), None)(
          configured(url + searchPath).latest("w3c/dcat@2"),
        )
        requests shouldBe empty
      }
    }

    for (
      target <- Seq(
        "w3c",
        "w3c/dcat@",
        "w3c/dcat@1+2",
        "w3c/`dcat",
        "w3c/dcat/x",
        "w3c/dcat@1@2",
      )
    ) {
      s"reject inspect target $target without a request" in {
        withServer(exchange => reply(exchange, 200, binary)) { (url, requests) =>
          error(CatalogErrorCode.InvalidInput, CatalogOperation.Inspect, Some(target), None)(
            configured(url + searchPath).inspect(target),
          )
          requests shouldBe empty
        }
      }
    }
  }

  "CatalogClient errors and lifecycle" should {
    for ((status, body) <- Seq(404 -> "missing", 503 -> "{", 503 -> "[null]")) {
      s"retain HTTP $status without inventing response JSON for $body" in {
        withServer(exchange => reply(exchange, status, body.getBytes(UTF_8))) { (url, _) =>
          error(CatalogErrorCode.HttpError, CatalogOperation.Get, Some(url), Some(status))(
            new CatalogClient().get(url),
          ).responseJson shouldBe None
        }
      }
    }

    "retain structured 503 details and recovery arguments with the search endpoint" in {
      val body =
        """{"code":"UNKNOWN","details":{"items":[null,2]},"recovery":{"args":{"token":null}}}"""
      withServer(exchange => reply(exchange, 503, body.getBytes(UTF_8))) { (url, _) =>
        val failure =
          error(
            CatalogErrorCode.HttpError,
            CatalogOperation.Search,
            Some(url + latestPath),
            Some(503),
          )(
            configured(url + searchPath).search(SearchRequest(q = "*")),
          )
        ujson.read(failure.responseJson.get.value) shouldBe ujson.read(body)
      }
    }

    "map malformed search success to INVALID_RESPONSE" in {
      withServer(exchange => reply(exchange, 200, "{}".getBytes(UTF_8))) { (url, _) =>
        error(
          CatalogErrorCode.InvalidResponse,
          CatalogOperation.Search,
          Some(url + latestPath),
          Some(200),
        )(
          configured(url + searchPath).search(SearchRequest(q = "*")),
        )
      }
    }

    "delegate invalid publication metadata to the existing decoder" in {
      withServer(exchange => reply(exchange, 200, "{}".getBytes(UTF_8))) { (url, _) =>
        error(CatalogErrorCode.InvalidResponse, CatalogOperation.Inspect, Some(url), Some(200))(
          new CatalogClient().inspect(url),
        )
      }
    }

    "reject invalid UTF-8 before publication JSON decoding" in {
      val publication = packageJson
      val title = "\"title\": \""
      publication(publication.indexOfSlice(title.getBytes(UTF_8)) + title.length) = -1
      withServer(exchange => reply(exchange, 200, publication)) { (url, _) =>
        error(CatalogErrorCode.InvalidResponse, CatalogOperation.Inspect, Some(url), Some(200))(
          new CatalogClient().inspect(url),
        )
      }
    }

    "report refused connections without a fabricated status" in {
      val socket = new ServerSocket(0)
      val url = s"http://127.0.0.1:${socket.getLocalPort}/file"
      socket.close()
      error(CatalogErrorCode.ConnectionError, CatalogOperation.Get, Some(url), None)(
        new CatalogClient().get(url),
      )
    }

    "refuse redirects without requesting their target" in {
      withServer { exchange =>
        exchange.getResponseHeaders.set("Location", "/redirected")
        reply(exchange, 302, Array.emptyByteArray)
      } { (url, requests) =>
        error(CatalogErrorCode.HttpError, CatalogOperation.Get, Some(url), Some(302))(
          new CatalogClient().get(url),
        )
        requests.asScala.toSeq shouldBe Seq("/" -> "identity")
      }
    }

    "reject partial-content status" in {
      withServer(exchange => reply(exchange, 206, binary)) { (url, _) =>
        error(CatalogErrorCode.HttpError, CatalogOperation.Get, Some(url), Some(206))(
          new CatalogClient().get(url),
        )
      }
    }

    "reject unexpected content encoding on a successful response" in {
      withServer { exchange =>
        exchange.getResponseHeaders.set("Content-Encoding", "gzip")
        reply(exchange, 200, binary)
      } { (url, _) =>
        error(CatalogErrorCode.InvalidResponse, CatalogOperation.Get, Some(url), Some(200))(
          new CatalogClient().get(url),
        )
      }
    }

    "close the connection after a complete successful body" in {
      withSocketResponse(
        "HTTP/1.1 200 OK\r\nContent-Length: 3\r\nCache-Control: no-store\r\n\r\nabc",
      ) { url =>
        new CatalogClient().get(url).toSeq shouldBe Seq[Byte](97, 98, 99)
      }
    }

    "time out and close a stalled successful body without returning partial bytes" in {
      withSocketResponse(
        "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nCache-Control: no-store\r\n\r\nabc",
      ) { url =>
        error(CatalogErrorCode.Timeout, CatalogOperation.Get, Some(url), Some(200))(
          new CatalogClient(CatalogConfig(timeoutMs = Some(150))).get(url),
        )
      }
    }

    "report an invalid HTTP response without a fabricated status" in {
      withSocketResponse("broken protocol\r\n\r\n", endBody = true) { url =>
        error(CatalogErrorCode.ConnectionError, CatalogOperation.Get, Some(url), None)(
          new CatalogClient().get(url),
        )
      }
    }

    "reject a body ending before its declared HTTP length" in {
      withSocketResponse(
        "HTTP/1.1 200 OK\r\nContent-Length: 100\r\nCache-Control: no-store\r\n\r\nabc",
        endBody = true,
      ) { url =>
        error(CatalogErrorCode.ConnectionError, CatalogOperation.Get, Some(url), Some(200))(
          new CatalogClient().get(url),
        )
      }
    }

    "retain HTTP failure and close when its error body stalls" in {
      withSocketResponse(
        "HTTP/1.1 503 Unavailable\r\nContent-Length: 100\r\nCache-Control: no-store\r\n\r\n{",
      ) { url =>
        error(CatalogErrorCode.HttpError, CatalogOperation.Get, Some(url), Some(503))(
          new CatalogClient(CatalogConfig(timeoutMs = Some(150))).get(url),
        ).responseJson shouldBe None
      }
    }
  }
}
