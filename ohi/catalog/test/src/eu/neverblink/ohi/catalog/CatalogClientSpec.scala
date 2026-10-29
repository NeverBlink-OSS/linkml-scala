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

  private def searchResponse(page: Int = 1, perPage: Int = 10): Array[Byte] =
    s"""{"hits":[],"found":0,"page":$page,"request_params":{"per_page":$perPage},"search_cutoff":false}""".getBytes(
      UTF_8,
    )

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
      s"send default page and accepted page size $size" in {
        val expectedSize = size.getOrElse(10)
        withServer(exchange => reply(exchange, 200, searchResponse(perPage = expectedSize))) {
          (url, requests) =>
            val result = configured(url).search(SearchRequest(q = "*", perPage = size))
            result.page shouldBe 1
            result.perPage shouldBe expectedSize
            result.hits shouldBe empty
            requests.asScala.toSeq shouldBe Seq(
              s"/?q=*&query_by=name%2Cdescription&page=1&per_page=$expectedSize" -> "identity",
            )
        }
      }
    }

    "encode each original parameter once and preserve duplicate backend order with one request" in {
      val raw = ujson.write(
        ujson.Obj(
          "hits" -> ujson.Arr.from(
            Seq("z", "a", "z").map(name =>
              ujson.Obj(
                "document" -> ujson.Obj(
                  "base_url" -> s"urn:ohi:$name",
                  "namespace" -> "example",
                  "name" -> name,
                  "version" -> "1.0",
                  "title" -> "Sensors",
                  "description" -> "Sensor measurements",
                  "formats" -> ujson.Arr("owl-turtle"),
                  "licenses" -> ujson.Arr(),
                  "published_at" -> "2026-10-13T12:30:00Z",
                ),
              ),
            ),
          ),
          "found" -> 8,
          "page" -> 2,
          "request_params" -> ujson.Obj("per_page" -> 3),
          "search_cutoff" -> false,
        ),
      )
      withServer(exchange => reply(exchange, 200, raw.getBytes(UTF_8))) { (url, requests) =>
        val result = configured(url + "/exact/search").search(
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
        target.takeWhile(_ != '?') shouldBe "/exact/search"
        val parameters = target.split("\\?", 2)(1).split("&").map { pair =>
          val parts = pair.split("=", 2)
          parts(0) -> URLDecoder.decode(parts(1), UTF_8)
        }.toMap
        parameters shouldBe Map(
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
      val publication = Files.readAllBytes(
        Path.of(sys.env("MILL_WORKSPACE_ROOT"), "ohi", "examples", "sensors-package.json"),
      )
      withServer(exchange => reply(exchange, 200, publication)) { (url, requests) =>
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

    "retain structured 503 details and recovery arguments with the configured search URL" in {
      val body =
        """{"code":"UNKNOWN","details":{"items":[null,2]},"recovery":{"args":{"token":null}}}"""
      withServer(exchange => reply(exchange, 503, body.getBytes(UTF_8))) { (url, _) =>
        val failure =
          error(CatalogErrorCode.HttpError, CatalogOperation.Search, Some(url), Some(503))(
            configured(url).search(SearchRequest(q = "*")),
          )
        ujson.read(failure.responseJson.get.value) shouldBe ujson.read(body)
      }
    }

    "map malformed search success to INVALID_RESPONSE" in {
      withServer(exchange => reply(exchange, 200, "{}".getBytes(UTF_8))) { (url, _) =>
        error(CatalogErrorCode.InvalidResponse, CatalogOperation.Search, Some(url), Some(200))(
          configured(url).search(SearchRequest(q = "*")),
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
      val publication = Files.readAllBytes(
        Path.of(sys.env("MILL_WORKSPACE_ROOT"), "ohi", "examples", "sensors-package.json"),
      )
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
