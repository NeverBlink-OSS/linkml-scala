package eu.neverblink.ohi.cli

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import eu.neverblink.linkml.cli.BaseCommand
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

import java.net.{InetSocketAddress, URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class CatalogCommandsSpec extends AnyWordSpec, Matchers {
  private val mapper = JsonMapper.builder().build()

  // Synthetic metadata exercises output fields and ordering without claiming real catalog data.
  private val hits = """[
    {"document":{"base_url":"https://example.org/z?revision=2","namespace":"example","name":"z",
      "version":"2.0","title":"Z sensors","description":"Sensor\nmeasurements","formats":["rdfs-turtle","owl-turtle"],
      "licenses":[],"published_at":"2026-10-13T12:30:00Z"}},
    {"document":{"base_url":"https://example.org/a/","namespace":"example","name":"a",
      "version":"1.0","title":"A devices","description":"Device descriptions","formats":["linkml-yaml"],
      "licenses":["MIT"],"published_at":"2026-10-12T12:30:00Z"}}
  ]"""
  private val facets = """[
    {"field_name":"namespace","counts":[{"value":"z","count":2},{"value":"a","count":5}]},
    {"field_name":"formats","counts":[{"value":"rdfs-turtle","count":1}]}
  ]"""

  private def searchResponse(page: Int = 1, perPage: Int = 10): String =
    s"""{"hits":$hits,"found":7,"page":$page,"request_params":{"per_page":$perPage},
       |"search_cutoff":true,"facet_counts":$facets}""".stripMargin

  private val publication = """{
    "schema_version":2,"base_url":"https://example.org/publications/z?revision=2",
    "namespace":"example","name":"z","version":"2.0","title":"Z sensors",
    "description":"Sensor measurements","published_at":"2026-10-13T12:30:00Z",
    "publisher":"Example publisher","licenses":[],"keywords":[],
    "namespace_iri":"https://example.org/terms#","preferred_prefix":"ex",
    "artifacts":[{"format":"owl-turtle","url":"https://example.org/file.ttl?revision=2",
      "origin":"UPSTREAM","roots":["https://example.org/z.ttl","https://example.org/a.ttl"],
      "size_bytes":4,"sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}],
    "dependencies":[{"base_url":"https://example.org/dependency/"}]
  }"""

  private def json(document: String): JsonNode = mapper.readTree(document)

  private def keys(node: JsonNode): Set[String] = node.propertyNames().asScala.toSet

  private def withServer(response: String, status: Int = 200)(
      check: (String, ConcurrentLinkedQueue[String]) => Unit,
  ): Unit = withServerBytes(response.getBytes(UTF_8), status)(check)

  private def withServerBytes(bytes: Array[Byte], status: Int = 200)(
      check: (String, ConcurrentLinkedQueue[String]) => Unit,
  ): Unit = {
    val requests = new ConcurrentLinkedQueue[String]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        requests.add(exchange.getRequestURI.toString)
        exchange.getResponseHeaders.set("Cache-Control", "no-store")
        try {
          exchange.sendResponseHeaders(status, if bytes.isEmpty then -1L else bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        } finally exchange.close()
      },
    )
    server.start()
    try check(s"http://127.0.0.1:${server.getAddress.getPort}", requests)
    finally server.stop(0)
  }

  private def run(command: BaseCommand[?], args: String*): (String, String, Int) =
    command.runTestCommandWithExitCode(args.toList, new OhiApp().main)

  // A fresh process also captures dependency initialization that writes directly to System.err.
  private def runProcess(args: String*): (String, String, Int) = {
    val result = os.proc(
      os.Path(System.getProperty("java.home")) / "bin" / "java",
      "-cp",
      System.getProperty("java.class.path"),
      "eu.neverblink.ohi.cli.Main",
      args,
    ).call(check = false, stderr = os.Pipe, timeout = 10000)
    (result.out.text(), result.err.text(), result.exitCode)
  }

  private def successful(command: BaseCommand[?], args: String*): String = {
    val (out, err, code) = run(command, args*)
    withClue(s"stderr was: $err\n") { code shouldBe 0 }
    err shouldBe empty
    out should endWith("\n")
    out should not include "\u001b"
    out
  }

  private def failed(command: BaseCommand[?], args: String*): String = {
    val (out, err, code) = run(command, args*)
    code shouldBe 1
    out shouldBe empty
    err should not be empty
    err
  }

  private def query(request: String): Map[String, String] =
    new URI(request).getRawQuery.split("&").map { parameter =>
      val pair = parameter.split("=", 2)
      URLDecoder.decode(pair(0), UTF_8) -> URLDecoder.decode(pair(1), UTF_8)
    }.toMap

  "catalog search" should {
    "forward every supplied option once and preserve all JSON fields and backend order" in {
      withServer(searchResponse(page = 2, perPage = 3)) { (url, requests) =>
        val out = successful(
          Search,
          "search",
          "ü + & sensors",
          "--search-url",
          url + "/exact/search",
          "--query-by",
          "title,description",
          "--page",
          "2",
          "--per-page",
          "3",
          "--filter-by",
          "namespace:=[`a+b`, `x&y`]",
          "--sort-by",
          "name:desc",
          "--facet-by",
          "namespace,formats",
          "--max-facet-values",
          "6",
          "--timeout-ms",
          "2000",
          "--json",
          "--color=never",
        )
        requests should have size 1
        new URI(requests.peek()).getPath shouldBe "/exact/search"
        query(requests.peek()) shouldBe Map(
          "q" -> "ü + & sensors",
          "query_by" -> "title,description",
          "page" -> "2",
          "per_page" -> "3",
          "filter_by" -> "namespace:=[`a+b`, `x&y`]",
          "sort_by" -> "name:desc",
          "facet_by" -> "namespace,formats",
          "max_facet_values" -> "6",
        )
        json(out) shouldBe json("""{
          "total":7,"page":2,"per_page":3,"search_cutoff":true,
          "hits":[
            {"base_url":"https://example.org/z?revision=2","namespace":"example","name":"z","version":"2.0",
             "title":"Z sensors","description":"Sensor\nmeasurements","formats":["rdfs-turtle","owl-turtle"],
             "licenses":[],"published_at":"2026-10-13T12:30:00Z"},
            {"base_url":"https://example.org/a/","namespace":"example","name":"a","version":"1.0",
             "title":"A devices","description":"Device descriptions","formats":["linkml-yaml"],
             "licenses":["MIT"],"published_at":"2026-10-12T12:30:00Z"}],
          "facet_counts":[
            {"field_name":"namespace","counts":[{"value":"z","count":2},{"value":"a","count":5}]},
            {"field_name":"formats","counts":[{"value":"rdfs-turtle","count":1}]}]
        }""")
      }
    }

    "use default paging and human output without dropping summaries, facets or cutoff" in {
      withServer(searchResponse()) { (url, requests) =>
        val out = successful(
          Search,
          "search",
          "*",
          "--search-url",
          url,
          "--query-by",
          "title",
          "--color=never",
        )
        requests should have size 1
        query(requests.peek()) shouldBe Map(
          "q" -> "*",
          "query_by" -> "title",
          "page" -> "1",
          "per_page" -> "10",
        )
        val compact = out.filterNot(_.isWhitespace)
        Seq(
          "7 matches · Showing 1–2 · Page 1",
          "Search stopped early. Results and counts may be incomplete.",
          "Ontology: example/z",
          "Version: 2.0",
          "Z sensors",
          "Sensor measurements",
          "https://example.org/z?revision=2",
          "rdfs-turtle, owl-turtle",
          "Ontology: example/a",
          "Version: 1.0",
          "A devices",
          "Device descriptions",
          "https://example.org/a/",
          "linkml-yaml",
          "License: MIT",
          "Facet: namespace",
          "z: 2",
          "a: 5",
          "Facet: formats",
          "rdfs-turtle: 1",
        ).foreach(value => compact should include(value.filterNot(_.isWhitespace)))
        out.linesIterator.count(_.startsWith("  License:")) shouldBe 1
        out should not include "Tags:"
        out should not include "Published:"
        out should not include "Not supplied"
        compact.indexOf("example/z") should be < compact.indexOf("example/a")
        compact.indexOf("Facet:namespace") should be < compact.indexOf("Facet:formats")
        compact.indexOf("z:2") should be < compact.indexOf("a:5")
      }
    }

    "report an empty page as successful human output with effective paging" in {
      withServer(
        """{"hits":[],"found":7,"page":2,"request_params":{"per_page":10},"search_cutoff":false}""",
      ) { (url, requests) =>
        val out = successful(
          Search,
          "search",
          "*",
          "--search-url",
          url,
          "--query-by",
          "title",
          "--page",
          "2",
        )
        out.replaceAll("\\s+", " ").trim shouldBe "No results on page 2. 7 matches in total."
        out should not include "may be incomplete"
        requests should have size 1
      }
    }

    "retain the required empty hit array in JSON instead of omitting it" in {
      withServer(
        """{"hits":[],"found":0,"page":1,"request_params":{"per_page":10},"search_cutoff":false}""",
      ) { (url, _) =>
        val out =
          successful(Search, "search", "*", "--search-url", url, "--query-by", "title", "--json")
        json(out) shouldBe json(
          """{"hits":[],"total":0,"page":1,"per_page":10,"search_cutoff":false}""",
        )
      }
    }

    "reject an unsupported color before making a request even with JSON output" in {
      withServer(searchResponse()) { (url, requests) =>
        val err = failed(
          Search,
          "search",
          "*",
          "--search-url",
          url,
          "--query-by",
          "title",
          "--color=always",
          "--json",
        )
        err should include("expected auto or never")
        requests shouldBe empty
      }
    }
  }

  "search color policy" should {
    "enable auto color for an attached terminal unless TERM is dumb" in {
      SearchColor.Auto.enabled(true, Some("xterm-256color")) shouldBe true
      SearchColor.Auto.enabled(true, None) shouldBe true
    }

    "disable color for never, detached output or a dumb terminal" in {
      val cases = Seq(
        ("never", SearchColor.Never, true, None),
        ("detached", SearchColor.Auto, false, None),
        ("dumb", SearchColor.Auto, true, Some("dumb")),
      )
      cases.foreach { case (label, mode, terminal, term) =>
        withClue(label) { mode.enabled(terminal, term) shouldBe false }
      }
    }
  }

  "catalog inspection" should {
    "GET the exact publication URL and serialize every declared field with ordered nested records" in {
      withServer(publication) { (url, requests) =>
        val (out, err, code) = runProcess("inspect", url + "/publication?revision=2", "--json")
        code shouldBe 0
        err shouldBe empty
        out should endWith("\n")
        // The independent wire document also specifies the expected public JSON, including types.
        json(out) shouldBe json(publication)
        requests.asScala.toSeq shouldBe Seq("/publication?revision=2")
      }
    }

    "omit absent optional scalars and retain empty required publication lists" in {
      val minimal = json(publication).asInstanceOf[tools.jackson.databind.node.ObjectNode]
      minimal.remove("namespace_iri")
      minimal.remove("preferred_prefix")
      minimal.set("dependencies", mapper.createArrayNode())
      withServer(mapper.writeValueAsString(minimal)) { (url, _) =>
        val result = json(successful(Inspect, "inspect", url, "--json"))
        keys(result) shouldBe Set(
          "schema_version",
          "base_url",
          "namespace",
          "name",
          "version",
          "title",
          "description",
          "published_at",
          "publisher",
          "licenses",
          "keywords",
          "artifacts",
          "dependencies",
        )
        Seq("licenses", "keywords", "dependencies").foreach { field =>
          result.get(field).isArray shouldBe true
          result.get(field).isEmpty shouldBe true
        }
      }
    }

    "show publication metadata, artifact provenance and dependencies in human output" in {
      withServer(publication) { (url, _) =>
        val out = successful(Inspect, "inspect", url)
        Seq(
          "example/z 2.0",
          "https://example.org/publications/z?revision=2",
          "Z sensors",
          "Sensor measurements",
          "2026-10-13T12:30:00Z",
          "Example publisher",
          "Licenses: Not supplied",
          "Keywords: Not supplied",
          "https://example.org/terms#",
          "Preferred prefix: ex",
          "https://example.org/file.ttl?revision=2",
          "owl-turtle",
          "UPSTREAM",
          "https://example.org/z.ttl, https://example.org/a.ttl",
          "Bytes: 4",
          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "https://example.org/dependency/",
        ).foreach(out should include(_))
      }
    }
  }

  "catalog file delivery" should {
    "bind the directory flag, save exact bytes and emit a receipt without changing project records" in {
      val directory = os.temp.dir(prefix = "ohi cli get ")
      try {
        os.write(directory / "ohi.yaml", "unchanged declaration\n")
        os.write(directory / "ohi.lock.json", "unchanged lock\n")
        val bytes = Array[Byte](0, -1, 65, -128)
        withServerBytes(bytes) { (url, requests) =>
          val targetUrl = url + "/energy%20model+v2.ttl?revision=2"
          val out = successful(Get, "get", targetUrl, "--to", directory.toString, "--json")
          val receipt = json(out)
          keys(receipt) shouldBe Set("url", "path")
          receipt.get("url").stringValue() shouldBe targetUrl
          receipt.get("path").stringValue() shouldBe (directory / "energy model+v2.ttl").toString
          os.read.bytes(directory / "energy model+v2.ttl").toSeq shouldBe bytes.toSeq
          requests.asScala.toSeq shouldBe Seq("/energy%20model+v2.ttl?revision=2")
          os.read(directory / "ohi.yaml") shouldBe "unchanged declaration\n"
          os.read(directory / "ohi.lock.json") shouldBe "unchanged lock\n"
          os.list(directory).map(_.last).toSet shouldBe Set(
            "ohi.yaml",
            "ohi.lock.json",
            "energy model+v2.ttl",
          )
        }
      } finally os.remove.all(directory)
    }

    "print the completed absolute path for a human delivery" in {
      val directory = os.temp.dir(prefix = "ohi cli get")
      try
        withServerBytes(Array.emptyByteArray) { (url, _) =>
          val out = successful(Get, "get", url + "/empty.ttl", "--to", directory.toString)
          out shouldBe s"Saved ${directory / "empty.ttl"}\n"
          os.read.bytes(directory / "empty.ttl") shouldBe empty
        }
      finally os.remove.all(directory)
    }

    "report local collisions only on stderr and leave the selected destination unchanged" in {
      val directory = os.temp.dir(prefix = "ohi cli collision")
      try {
        os.write(directory / "file.ttl", "sentinel")
        withServer("must not be retrieved") { (url, requests) =>
          val error =
            json(failed(Get, "get", url + "/file.ttl", "--to", directory.toString, "--json"))
          keys(error) shouldBe Set("code", "message", "url", "directory", "path")
          error.get("code").stringValue() shouldBe "DESTINATION_EXISTS"
          error.get("url").stringValue() shouldBe url + "/file.ttl"
          error.get("directory").stringValue() shouldBe directory.toString
          error.get("path").stringValue() shouldBe (directory / "file.ttl").toString
          error.get("message").stringValue() should not be empty
          requests shouldBe empty
          os.read(directory / "file.ttl") shouldBe "sentinel"
          os.list(directory).map(_.last) shouldBe Seq("file.ttl")
        }
      } finally os.remove.all(directory)
    }
  }

  "catalog command errors" should {
    "preserve SDK status, operation and nested server JSON on stderr without a success result" in {
      withServer("""{"error":{"reason":"missing","retry":false},"status":404}""", status = 404) {
        (url, requests) =>
          val (out, err, code) = runProcess("inspect", url + "/absent?revision=2", "--json")
          code shouldBe 1
          out shouldBe empty
          val error = json(err)
          keys(error) shouldBe Set("code", "message", "operation", "url", "status", "response_json")
          error.get("code").stringValue() shouldBe "HTTP_ERROR"
          error.get("operation").stringValue() shouldBe "INSPECT"
          error.get("url").stringValue() shouldBe url + "/absent?revision=2"
          error.get("status").intValue() shouldBe 404
          error.get("response_json") shouldBe json(
            """{"error":{"reason":"missing","retry":false},"status":404}""",
          )
          requests.asScala.toSeq shouldBe Seq("/absent?revision=2")
      }
    }

    for (label, value) <- Seq("malformed integer" -> "1.5", "32-bit overflow" -> "2147483648") do
      s"reject $label through the parser before making a request even with JSON requested" in {
        withServer(publication) { (url, requests) =>
          val err = failed(Inspect, "inspect", url, "--timeout-ms", value, "--json")
          err should include("timeout-ms")
          err should include("ohi inspect --help")
          err.trim should not startWith "{"
          requests shouldBe empty
        }
      }

    "preserve representable zero timeout as an SDK configuration error before retrieval" in {
      withServer(publication) { (url, requests) =>
        val error = json(failed(Inspect, "inspect", url, "--timeout-ms", "0", "--json"))
        keys(error) shouldBe Set("code", "message", "operation")
        error.get("code").stringValue() shouldBe "INVALID_INPUT"
        error.get("operation").stringValue() shouldBe "CONFIGURE"
        error.get("message").stringValue() should include("timeout_ms")
        requests shouldBe empty
      }
    }

    "accept omitted search flags and validate the query against the default catalog before retrieval" in {
      val error = json(failed(Search, "search", " ", "--json"))
      error.get("code").stringValue() shouldBe "INVALID_INPUT"
      error.get("operation").stringValue() shouldBe "SEARCH"
      error.get("url").stringValue() shouldBe
        "https://api.ohi.dev/search/collections/packages/documents/search"
    }

    for (label, command, args, diagnostic) <- Seq(
        (
          "removed format flag",
          Inspect,
          List("inspect", "https://example.org/p", "--format", "json", "--json"),
          "--format",
        ),
        ("missing inspect URL", Inspect, List("inspect", "--json"), "exactly one publication URL"),
        (
          "extra inspect URL",
          Inspect,
          List("inspect", "https://example.org/a", "--", "https://example.org/b"),
          "exactly one publication URL",
        ),
        (
          "missing search query",
          Search,
          List("search", "--search-url", "https://example.org/search", "--query-by", "title"),
          "exactly one query",
        ),
        (
          "missing search endpoint",
          Search,
          List("search", "*", "--query-by", "title"),
          "search_url and query_by must be supplied together",
        ),
        (
          "missing search fields",
          Search,
          List("search", "*", "--search-url", "https://example.org/search"),
          "search_url and query_by must be supplied together",
        ),
        ("missing destination", Get, List("get", "https://example.org/file.ttl", "--json"), "--to"),
        ("missing file URL", Get, List("get", "--to", "."), "exactly one file URL"),
        (
          "search setting on inspect",
          Inspect,
          List("inspect", "https://example.org/p", "--query-by", "title"),
          "--query-by",
        ),
      )
    do
      s"report $label with command help instead of accepting an invalid invocation" in {
        val err = failed(command, args*)
        err should include(diagnostic)
        err should include(s"ohi ${args.head} --help")
        err.trim should not startWith "{"
      }
  }
}
