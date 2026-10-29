package eu.neverblink.ohi.cli

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import eu.neverblink.linkml.cli.BaseCommand
import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.cli.generated.fileerror.{CliFileError, CliFileErrorCode}
import eu.neverblink.ohi.cli.generated.lockdownload.{
  LockDownloadResult,
  LockFileResult,
  LockFileStatus,
}
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

import java.net.{InetSocketAddress, URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
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
  ): Unit = withServerResponses(_ => _ => status -> bytes)(check)

  private def withServerResponses(responses: String => String => (Int, Array[Byte]))(
      check: (String, ConcurrentLinkedQueue[String]) => Unit,
  ): Unit = {
    val requests = new ConcurrentLinkedQueue[String]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val base = s"http://127.0.0.1:${server.getAddress.getPort}"
    val respond = responses(base)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        val request = exchange.getRequestURI.toString
        requests.add(request)
        val (status, bytes) = respond(request)
        exchange.getResponseHeaders.set("Cache-Control", "no-store")
        try {
          exchange.sendResponseHeaders(status, if bytes.isEmpty then -1L else bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        } finally exchange.close()
      },
    )
    server.start()
    try check(base, requests)
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

  // Fork to test actual cwd lookup while overriding only the catalog endpoint.
  private def runReference(cwd: os.Path, base: String, args: String*): (String, String, Int) = {
    val result = os.proc(
      os.Path(System.getProperty("java.home")) / "bin" / "java",
      "-cp",
      System.getProperty("java.class.path"),
      "eu.neverblink.ohi.cli.ConfiguredGet",
      base + "/search",
      args,
    ).call(cwd = cwd, check = false, stderr = os.Pipe, timeout = 10000)
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

  private def withDirectory(check: os.Path => Unit): Unit = {
    val directory = os.temp.dir(prefix = "ohi lock download ")
    try check(directory)
    finally os.remove.all(directory)
  }

  private def writeLock(
      directory: os.Path,
      entries: Seq[(String, String)] = Seq.empty,
      version: Int = 2,
  ): os.Path = {
    val files = entries.zipWithIndex.map { case ((url, digest), index) =>
      s"""{"base_url":"https://example.org/publication/$index","url":"$url",
         |"format":"owl-turtle","sha256":"$digest"}""".stripMargin
    }.mkString(",")
    val selection = entries.headOption.map { case (url, _) =>
      s"""{"base_url":"https://example.org/publication/0","url":"$url"}"""
    }.getOrElse("")
    val path = directory / "ohi.lock.json"
    os.write(path, s"""{"format_version":$version,"selections":[$selection],"files":[$files]}""")
    path
  }

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
      CliColor.Auto.enabled(true, Some("xterm-256color")) shouldBe true
      CliColor.Auto.enabled(true, None) shouldBe true
    }

    "disable color for never, detached output or a dumb terminal" in {
      val cases = Seq(
        ("never", CliColor.Never, true, None),
        ("detached", CliColor.Auto, false, None),
        ("dumb", CliColor.Auto, true, Some("dumb")),
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
          "Ontology: example/z",
          "Version: 2.0",
          "https://example.org/publications/z?revision=2",
          "Z sensors",
          "Sensor measurements",
          "2026-10-13T12:30:00Z",
          "Example publisher",
          "License: Not supplied",
          "Tags: Not supplied",
          "https://example.org/terms#",
          "Preferred prefix: ex",
          "https://example.org/file.ttl?revision=2",
          "owl-turtle",
          "Source: Upstream",
          "Entry points\n      https://example.org/z.ttl\n      https://example.org/a.ttl",
          "Size: 4 bytes",
          "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
          "https://example.org/dependency/",
        ).foreach(out should include(_))
      }
    }
  }

  "catalog file delivery" should {
    for name <- Seq("get", "add") do
      s"offer actual file choices for $name without writes or selecting one implicitly" in {
        withDirectory { directory =>
          val destination = directory / "files here"
          withServerResponses { base =>
            val selectedHits =
              hits.replace("https://example.org/z?revision=2", base + "/publication")
            val search = s"""{"hits":$selectedHits,"found":2,"page":1,
              "request_params":{"per_page":250},"search_cutoff":false}"""
            val metadata = json(
              publication.replace(
                "https://example.org/publications/z?revision=2",
                base + "/publication",
              ),
            )
            val files =
              metadata.get("artifacts").asInstanceOf[tools.jackson.databind.node.ArrayNode]
            val first = files.get(0).asInstanceOf[tools.jackson.databind.node.ObjectNode]
            first.put("url", base + "/first.ttl")
            files.add(first.deepCopy().put("url", base + "/second.ttl"))
            (request: String) =>
              if new URI(request).getPath == "/search" then 200 -> search.getBytes(UTF_8)
              else 200 -> metadata.toString.getBytes(UTF_8)
          } { (base, requests) =>
            val config =
              CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name"))
            val command = if name == "get" then
              new GetCommand {
                override protected def config(options: GetOptions): CatalogConfig =
                  CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name"))
              }
            else
              new AddCommand {
                override protected def client() =
                  new eu.neverblink.ohi.catalog.CatalogClient(config)
              }
            val extra = if name == "add" then List("--project", directory.toString) else Nil
            val (out, err, code) = command.runTestCommandWithExitCode(
              List(
                "example/z@2.0",
                "--format",
                "owl-turtle",
                "--to",
                destination.toString,
              ) ++ extra,
              args => command.main(s"ohi $name", args),
            )
            code shouldBe 1
            out shouldBe empty
            err should startWith("example/z@2.0 has more than one file in owl-turtle format.")
            err should include(s"Files\n  $base/first.ttl\n  $base/second.ttl")
            val selection = if name == "get" then s"$base/first.ttl"
            else s"$base/publication --artifact $base/first.ttl"
            err should include(s"ohi $name $selection --to '$destination'")
            if name == "add" then err should include(s"--project '$directory'")
            err should not include "Error:"
            requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe Seq(
              "/search",
              "/publication",
            )
            os.exists(destination) shouldBe false
            os.list(directory) shouldBe empty
          }
        }
      }

    "default a versioned reference to LinkML without project records" in {
      val directory = os.temp.dir(prefix = "ohi reference get ")
      val bytes = Array[Byte](0, -1, 65, -128)
      try
        withServerResponses { base =>
          val selectedHits = hits.replace("https://example.org/z?revision=2", base + "/publication")
          val search = s"""{"hits":$selectedHits,"found":2,"page":1,
            "request_params":{"per_page":250},"search_cutoff":false}"""
          val metadata = publication
            .replace("https://example.org/publications/z?revision=2", base + "/publication")
            .replace("https://example.org/file.ttl?revision=2", base + "/file.yaml")
            .replace("\"format\":\"owl-turtle\"", "\"format\":\"linkml-yaml\"")
          (request: String) =>
            new URI(request).getPath match {
              case "/search" => 200 -> search.getBytes(UTF_8)
              case "/publication" => 200 -> metadata.getBytes(UTF_8)
              case "/file.yaml" => 200 -> bytes
              case _ => 404 -> Array.emptyByteArray
            }
        } { (base, requests) =>
          val (out, err, code) = runReference(
            directory,
            base,
            "example/z@2.0",
            "--to",
            directory.toString,
            "--json",
          )
          code shouldBe 0
          err shouldBe empty
          json(out) shouldBe json(s"""{"url":"$base/file.yaml","path":"$directory/file.yaml"}""")
          os.read.bytes(directory / "file.yaml").toSeq shouldBe bytes.toSeq
          os.list(directory).map(_.last) shouldBe Seq("file.yaml")
          val calls = requests.asScala.toSeq
          calls.map(value => new URI(value).getPath) shouldBe
            Seq("/search", "/publication", "/file.yaml")
          query(calls.head)("filter_by") shouldBe "namespace:=example && name:=z && version:=2.0"
        }
      finally os.remove.all(directory)
    }

    for (explicit, behavior) <- Seq(
        false -> "infer the current directory's format independently of the download directory",
        true -> "bypass a malformed declaration when the format is explicit",
      )
    do
      s"$behavior and preserve both project records" in {
        withDirectory { directory =>
          val destination = directory / "downloads"
          os.makeDir(destination)
          os.write(destination / "ohi.yaml", "unrelated declaration")
          val staleLock = "stale lock bytes\n"
          os.write(directory / "ohi.lock.json", staleLock)
          withServerResponses { base =>
            val selectedHits =
              hits.replace("https://example.org/z?revision=2", base + "/publication")
            val search = s"""{"hits":$selectedHits,"found":2,"page":1,
              "request_params":{"per_page":250},"search_cutoff":false}"""
            val selected = publication
              .replace("https://example.org/publications/z?revision=2", base + "/publication")
              .replace("https://example.org/file.ttl?revision=2", base + "/file.ttl")
            val existing = publication
              .replace("https://example.org/publications/z?revision=2", base + "/existing")
              .replace("https://example.org/file.ttl?revision=2", base + "/selected.yaml")
            (request: String) =>
              new URI(request).getPath match {
                case "/existing" => 200 -> existing.getBytes(UTF_8)
                case "/search" => 200 -> search.getBytes(UTF_8)
                case "/publication" => 200 -> selected.getBytes(UTF_8)
                case "/file.ttl" => 200 -> "selected bytes\n".getBytes(UTF_8)
                case _ => 404 -> Array.emptyByteArray
              }
          } { (base, requests) =>
            val declaration = if explicit then "invalid declaration\n"
            else s"""format_version: 1
              |selections:
              |  - base_url: $base/existing
              |    url: $base/selected.yaml
              |""".stripMargin
            os.write(directory / "ohi.yaml", declaration)
            val format = if explicit then Seq("--format", "owl-turtle") else Seq.empty
            val (out, err, code) = runReference(
              directory,
              base,
              (Seq("example/z", "--to", destination.toString, "--json") ++ format)*,
            )
            code shouldBe 0
            err shouldBe empty
            json(out) shouldBe json(s"""{"url":"$base/file.ttl","path":"$destination/file.ttl"}""")
            os.read(destination / "file.ttl") shouldBe "selected bytes\n"
            os.read(directory / "ohi.yaml") shouldBe declaration
            os.read(directory / "ohi.lock.json") shouldBe staleLock
            os.read(destination / "ohi.yaml") shouldBe "unrelated declaration"
            requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe
              (if explicit then Seq("/search", "/publication", "/file.ttl")
               else Seq("/existing", "/search", "/publication", "/file.ttl"))
          }
        }
      }

    "report a malformed cwd declaration before any request even with JSON output" in {
      withDirectory { directory =>
        val invalid = "format_version: 99\nselections: []\n"
        os.write(directory / "ohi.yaml", invalid)
        val destination = directory / "downloads"
        withServer("unexpected") { (base, requests) =>
          val (out, err, code) = runReference(
            directory,
            base,
            "example/z",
            "--to",
            destination.toString,
            "--json",
          )
          code shouldBe 1
          out shouldBe empty
          err should startWith("Error: Invalid declaration '")
          err should include("ohi.yaml")
          err should include("ohi get --help")
          requests shouldBe empty
          os.read(directory / "ohi.yaml") shouldBe invalid
          os.exists(destination) shouldBe false
        }
      }
    }

    for lockMode <- Seq(false, true) do
      s"default a ${if lockMode then "lock" else "URL"} download to the invocation directory" in {
        val directory = os.temp.dir(prefix = "ohi get cwd ")
        try
          withServer("abc") { (url, requests) =>
            os.write(directory / "ohi.yaml", "invalid declaration")
            val arguments = if lockMode then {
              val path = writeLock(
                directory,
                Seq(
                  (url + "/file.ttl") ->
                    "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ),
              )
              Seq("--lock", path.toString)
            } else Seq(url.replace("http://", "HTTP://") + "/file.ttl")
            val result = os.proc(
              os.Path(System.getProperty("java.home")) / "bin" / "java",
              "-cp",
              System.getProperty("java.class.path"),
              "eu.neverblink.ohi.cli.Main",
              "get",
              arguments,
              "--json",
            ).call(cwd = directory, check = false, stderr = os.Pipe, timeout = 10000)
            result.exitCode shouldBe 0
            result.err.text() shouldBe empty
            val output = json(result.out.text())
            val receipt = if lockMode then {
              output.get("files").size() shouldBe 1
              output.get("files").get(0).get("status").stringValue() shouldBe "DOWNLOADED"
              output.get("files").get(0)
            } else output
            receipt.get("path").stringValue() shouldBe
              directory.toNIO.toRealPath().resolve("file.ttl").toString
            os.read(directory / "file.ttl") shouldBe "abc"
            os.read(directory / "ohi.yaml") shouldBe "invalid declaration"
            requests.asScala.toSeq shouldBe Seq("/file.ttl")
          }
        finally os.remove.all(directory)
      }

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

  "locked file delivery" should {
    for (mode, arguments, expected) <- Seq(
        ("URL", Seq("https://example.org/file.ttl"), "a file URL or --lock"),
        ("reference", Seq("test/example"), "an ontology reference"),
        ("format", Seq("--format", "owl-turtle"), "--format cannot be used with --lock"),
      )
    do
      s"reject mixed lock and $mode modes before configuration" in {
        val error = failed(
          Get,
          (Seq("get") ++ arguments ++ Seq(
            "--lock",
            "missing.json",
            "--to",
            ".",
            "--timeout-ms",
            "0",
            "--json",
          ))*,
        )
        error should include(expected)
        error should include("ohi get --help")
        error.trim should not startWith "{"
      }

    "reject a provider-invalid lock argument before timeout configuration" in {
      // The harness accepts NUL arguments that an OS process launcher rejects.
      val error = failed(
        Get,
        "get",
        "--lock",
        "bad\u0000lock",
        "--to",
        ".",
        "--timeout-ms",
        "0",
        "--json",
      )
      error should include("Invalid lock path")
      error should include("ohi get --help")
      error.trim should not startWith "{"
    }

    for emptyLock <- Seq(false, true) do
      s"validate configuration before ${
          if emptyLock then "an empty lock" else "a missing lock"
        }" in {
        withDirectory { directory =>
          val path = if emptyLock then writeLock(directory) else directory / "missing.json"
          val error = json(
            failed(
              Get,
              "get",
              "--lock",
              path.toString,
              "--to",
              "bad\u0000destination",
              "--timeout-ms",
              "0",
              "--json",
            ),
          )
          keys(error) shouldBe Set("files", "catalog_error")
          error.get("files") shouldBe json("[]")
          val cause = error.get("catalog_error")
          keys(cause) shouldBe Set("code", "operation", "message")
          cause.get("code").stringValue() shouldBe "INVALID_INPUT"
          cause.get("operation").stringValue() shouldBe "CONFIGURE"
          cause.get("message").stringValue() should include("timeout_ms")
        }
      }

    "return an empty result without interpreting the destination of an empty version-2 lock" in {
      withDirectory { directory =>
        val path = writeLock(directory)
        val args = Seq("get", "--lock", path.toString, "--to", "bad\u0000destination")
        json(successful(Get, (args :+ "--json")*)) shouldBe json("""{"files":[]}""")
        successful(Get, args*) shouldBe "No files in lock.\n"
        os.list(directory).map(_.last) shouldBe Seq("ohi.lock.json")
      }
    }

    "refuse an empty legacy lock with its supplied path and regeneration guidance" in {
      withDirectory { directory =>
        val path = writeLock(directory, version = 1)
        val error = json(
          failed(
            Get,
            "get",
            "--lock",
            path.toString,
            "--to",
            (directory / "out").toString,
            "--json",
          ),
        )
        keys(error) shouldBe Set("files", "lock_error")
        error.get("files") shouldBe json("[]")
        val cause = error.get("lock_error")
        keys(cause) shouldBe Set("code", "operation", "path", "message")
        cause.get("code").stringValue() shouldBe "UNSUPPORTED_VERSION"
        cause.get("operation").stringValue() shouldBe "READ"
        cause.get("path").stringValue() shouldBe path.toString
        cause.get("message").stringValue() should include("ohi lock --project <directory>")
        cause.get("message").stringValue() should include("originating application")
        os.exists(directory / "out") shouldBe false
      }
    }

    "wrap a lock read failure before accessing the destination" in {
      withDirectory { directory =>
        val path = directory / "missing.json"
        val error = json(
          failed(
            Get,
            "get",
            "--lock",
            path.toString,
            "--to",
            "bad\u0000destination",
            "--json",
          ),
        )
        keys(error) shouldBe Set("files", "lock_error")
        error.get("files") shouldBe json("[]")
        val cause = error.get("lock_error")
        cause.get("code").stringValue() shouldBe "STORAGE_ERROR"
        cause.get("operation").stringValue() shouldBe "READ"
        cause.get("path").stringValue() shouldBe path.toString
        os.list(directory) shouldBe empty
      }
    }

    "serialize a file failure with its target and no inactive causes" in {
      val failure = new CliFileException(
        CliFileError(
          code = CliFileErrorCode.HashMismatch,
          directory = "/out",
          message = "Hash mismatch",
          path = Some("/out/file.ttl"),
          url = "https://example.org/file.ttl",
        ),
      )
      val error = json(
        LockDownloadOutput.error(LockDownloadOutput.failure(Seq.empty, failure), json = true),
      )
      keys(error) shouldBe Set("files", "file_error")
      error.get("files") shouldBe json("[]")
      val cause = error.get("file_error")
      keys(cause) shouldBe Set("code", "message", "directory", "path", "url")
      cause.get("code").stringValue() shouldBe "HASH_MISMATCH"
      cause.get("directory").stringValue() shouldBe "/out"
      cause.get("path").stringValue() shouldBe "/out/file.ttl"
      cause.get("url").stringValue() shouldBe "https://example.org/file.ttl"
    }

    "report only the completed prefix on failure and resume the same command in lock order" in {
      withDirectory { directory =>
        val zBytes = Array[Byte](0, -1, 65, -128)
        val unavailable = new java.util.concurrent.atomic.AtomicBoolean(true)
        val serverError = """{"error":{"reason":null,"retry":true},"attempt":2,"delay":1.25}"""
        withServerResponses(_ => {
          case "/z.ttl?revision=2" => 200 -> zBytes
          case "/a.ttl" if unavailable.get() => 503 -> serverError.getBytes(UTF_8)
          case "/a.ttl" => 200 -> "abc".getBytes(UTF_8)
          case "/b.ttl" => 200 -> Array.emptyByteArray
          case _ => 404 -> "Unexpected request".getBytes(UTF_8)
        }) { (base, requests) =>
          val path = writeLock(
            directory,
            Seq(
              (base + "/z.ttl?revision=2") ->
                "ac7fa209c616517439d0e93add6855a02d7c4340f990084224ea290315ee3bd1",
              (base + "/a.ttl") ->
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
              (base + "/b.ttl") ->
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              (base + "/m.ttl") ->
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ),
          )
          val lockBytes = os.read.bytes(path).toSeq
          os.write(directory / "ohi.yaml", "unchanged declaration\n")
          os.write(directory / "m.ttl", "hello")
          val mTime = Files.getLastModifiedTime((directory / "m.ttl").toNIO)
          val args = Seq("get", "--lock", path.toString, "--to", directory.toString, "--json")
          val error = json(failed(Get, args*))
          keys(error) shouldBe Set("files", "catalog_error")
          error.get("files") shouldBe json(s"""[
            {"url":"$base/z.ttl?revision=2","path":"$directory/z.ttl","status":"DOWNLOADED"}
          ]""")
          val cause = error.get("catalog_error")
          keys(cause) shouldBe Set("code", "message", "operation", "url", "status", "response_json")
          cause.get("code").stringValue() shouldBe "HTTP_ERROR"
          cause.get("operation").stringValue() shouldBe "GET"
          cause.get("url").stringValue() shouldBe base + "/a.ttl"
          cause.get("status").intValue() shouldBe 503
          cause.get("response_json") shouldBe json(serverError)
          requests.asScala.toSeq shouldBe Seq("/z.ttl?revision=2", "/a.ttl")
          os.read.bytes(directory / "z.ttl").toSeq shouldBe zBytes.toSeq
          os.read(directory / "m.ttl") shouldBe "hello"
          Files.getLastModifiedTime((directory / "m.ttl").toNIO) shouldBe mTime
          os.exists(directory / "a.ttl") shouldBe false
          os.exists(directory / "b.ttl") shouldBe false
          os.read.bytes(path).toSeq shouldBe lockBytes
          os.read(directory / "ohi.yaml") shouldBe "unchanged declaration\n"

          val zTime = Files.getLastModifiedTime((directory / "z.ttl").toNIO)
          unavailable.set(false)
          requests.clear()
          json(successful(Get, args*)) shouldBe json(s"""{"files":[
            {"url":"$base/z.ttl?revision=2","path":"$directory/z.ttl","status":"VERIFIED"},
            {"url":"$base/a.ttl","path":"$directory/a.ttl","status":"DOWNLOADED"},
            {"url":"$base/b.ttl","path":"$directory/b.ttl","status":"DOWNLOADED"},
            {"url":"$base/m.ttl","path":"$directory/m.ttl","status":"VERIFIED"}
          ]}""")
          requests.asScala.toSeq shouldBe Seq("/a.ttl", "/b.ttl")
          os.read.bytes(directory / "z.ttl").toSeq shouldBe zBytes.toSeq
          Files.getLastModifiedTime((directory / "z.ttl").toNIO) shouldBe zTime
          os.read(directory / "a.ttl") shouldBe "abc"
          os.read.bytes(directory / "b.ttl") shouldBe empty
          os.read(directory / "m.ttl") shouldBe "hello"
          Files.getLastModifiedTime((directory / "m.ttl").toNIO) shouldBe mTime
          os.read.bytes(path).toSeq shouldBe lockBytes
          os.read(directory / "ohi.yaml") shouldBe "unchanged declaration\n"
        }
      }
    }

    "render downloaded and verified human receipts in supplied order" in {
      val result = LockDownloadResult(
        Seq(
          LockFileResult("/out/z.ttl", LockFileStatus.Downloaded, Uri("https://example.org/z.ttl")),
          LockFileResult("/out/a.ttl", LockFileStatus.Verified, Uri("https://example.org/a.ttl")),
        ),
      )
      LockDownloadOutput.result(
        result,
        json = false,
      ) shouldBe "Saved /out/z.ttl\nVerified /out/a.ttl"
    }

    "render the reached prefix, retained partial file and retry guidance on a human failure" in {
      val failure = new CliFileException(
        CliFileError(
          code = CliFileErrorCode.FileWriteFailed,
          directory = "/out",
          message =
            "Cannot finish writing '/out/a.ttl'. Remove the incomplete file before retrying.",
          partialPath = Some("/out/a.ttl"),
          path = Some("/out/a.ttl"),
          url = "https://example.org/a.ttl",
        ),
      )
      val error = LockDownloadOutput.failure(
        Seq(
          LockFileResult("/out/z.ttl", LockFileStatus.Downloaded, Uri("https://example.org/z.ttl")),
        ),
        failure,
      )
      LockDownloadOutput.error(error, json = false) shouldBe
        "Saved /out/z.ttl\n\n" +
        "Error: Could not finish writing the downloaded file.\nPath: /out/a.ttl\n" +
        "Details: Cannot finish writing '/out/a.ttl'. Remove the incomplete file before retrying.\n" +
        "Incomplete file retained: /out/a.ttl\n\n" +
        "Completed files were kept. Correct the storage problem and remove the incomplete file before retrying."
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
        ("missing inspect URL", Inspect, List("inspect", "--json"), "release URL"),
        (
          "extra inspect URL",
          Inspect,
          List("inspect", "https://example.org/a", "--", "https://example.org/b"),
          "release URL",
        ),
        (
          "missing search query",
          Search,
          List("search", "--search-url", "https://example.org/search", "--query-by", "title"),
          "ohi search <query>",
        ),
        (
          "missing search endpoint",
          Search,
          List("search", "*", "--query-by", "title"),
          "Supply --search-url with --query-by.",
        ),
        (
          "missing search fields",
          Search,
          List("search", "*", "--search-url", "https://example.org/search"),
          "Supply --query-by with --search-url.",
        ),
        (
          "format on file URL",
          Get,
          List("get", "https://example.org/file.ttl", "--format", "owl-turtle"),
          "--format cannot be used",
        ),
        (
          "invalid format",
          Get,
          List("get", "example/z", "--format", "turtle"),
          "format",
        ),
        ("missing file URL", Get, List("get", "--to", "."), "ohi get <namespace>/<name>"),
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

// The child process retains the production get routing with a local test catalog endpoint.
private[cli] object ConfiguredGet {
  def main(args: Array[String]): Unit = {
    val command = new GetCommand {
      override protected def config(options: GetOptions): CatalogConfig =
        CatalogConfig(searchUrl = Some(Uri(args.head)), queryBy = Some("name"))
    }
    command.main("ohi get", args.drop(1))
  }
}
