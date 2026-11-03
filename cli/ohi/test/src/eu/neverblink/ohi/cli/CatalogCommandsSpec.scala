package eu.neverblink.ohi.cli

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import eu.neverblink.linkml.cli.BaseCommand
import eu.neverblink.linkml.runtime.Uri

import eu.neverblink.ohi.model.generated.config.CatalogConfig
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.{InetSocketAddress, URI, URLDecoder}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

class CatalogCommandsSpec extends AnyWordSpec, Matchers {

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

  private def json(document: String): ujson.Value = ujson.read(document)

  private def keys(node: ujson.Value): Set[String] = node.obj.keySet.toSet

  private def withServer(response: String, status: Int = 200)(
      check: (String, ConcurrentLinkedQueue[String]) => Unit,
  ): Unit = withServerBytes(response.getBytes(UTF_8), status)(check)

  private def withServerBytes(bytes: Array[Byte], status: Int)(
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
  private def runReference(
      cwd: os.Path,
      base: String,
      entryPoint: String,
      args: String*,
  ): (String, String, Int) = {
    val result = os.proc(
      os.Path(System.getProperty("java.home")) / "bin" / "java",
      "-cp",
      System.getProperty("java.class.path"),
      s"eu.neverblink.ohi.cli.$entryPoint",
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

  private def inspectionPublication(base: String): ujson.Value = {
    val metadata = json(publication)
    metadata("base_url") = base + "/publication"
    val files = metadata("artifacts").arr
    files(0)("url") = base + "/first.ttl"
    files += ujson.copy(files(0))
    files(1)("url") = base + "/second.ttl"
    metadata
  }

  private def withInspection(check: (String, ConcurrentLinkedQueue[String]) => Unit): Unit =
    withServerResponses { base =>
      val selectedHits = hits.replace("https://example.org/z?revision=2", base + "/publication")
      val search = s"""{"hits":$selectedHits,"found":2,"page":1,
        "request_params":{"per_page":250},"search_cutoff":false}"""
      val metadata = inspectionPublication(base).toString
      request =>
        new URI(request).getPath match {
          case "/search" => 200 -> search.getBytes(UTF_8)
          case "/publication" => 200 -> metadata.getBytes(UTF_8)
          case _ => 404 -> Array.emptyByteArray
        }
    }(check)

  private def inspectReference(base: String, args: String*): (String, String, Int) = {
    val command = ConfiguredInspect.command(base + "/search")
    command.runTestCommandWithExitCode(args.toList, args => command.main("ohi inspect", args))
  }

  private def withDirectory(check: os.Path => Unit): Unit = {
    val directory = os.temp.dir(prefix = "ohi lock download ")
    try check(os.Path(directory.toNIO.toRealPath()))
    finally os.remove.all(directory)
  }

  private def writeLock(
      directory: os.Path,
      entries: Seq[(String, String)],
  ): os.Path = {
    val files = entries.map { case (url, digest) =>
      val baseUrl = url.take(url.lastIndexOf('/') + 1)
      s"""{"base_url":"$baseUrl","url":"$url",
         |"format":"owl-turtle","sha256":"$digest"}""".stripMargin
    }.mkString(",")
    val selection = entries.headOption.map { case (url, _) =>
      val baseUrl = url.take(url.lastIndexOf('/') + 1)
      s"""{"base_url":"$baseUrl","url":"$url"}"""
    }.getOrElse("")
    val path = directory / "ohi.lock.json"
    os.write(path, s"""{"format_version":2,"selections":[$selection],"files":[$files]}""")
    path
  }

  private val hub = "/files/packages/example/z/2.0/"
  private val binary = Array[Byte](0, -1, 65, -128)

  private def acquisitionPublication(base: String, format: String): String = {
    val metadata = json(publication)
    metadata("base_url") = base + hub
    metadata("artifacts")(0)("url") = base + hub + "original"
    metadata("artifacts")(0)("format") = format
    metadata("artifacts")(0)("sha256") =
      "ac7fa209c616517439d0e93add6855a02d7c4340f990084224ea290315ee3bd1"
    metadata("dependencies") = ujson.Arr()
    metadata.toString
  }

  private def withAcquisition(format: String = "owl-turtle")(
      check: (String, ConcurrentLinkedQueue[String]) => Unit,
  ): Unit = withServerResponses { base =>
    val selectedHits = hits.replace("https://example.org/z?revision=2", base + hub)
    val search = s"""{"hits":$selectedHits,"found":2,"page":1,
      "request_params":{"per_page":250},"search_cutoff":false}"""
    request =>
      new URI(request).getPath match {
        case "/search" => 200 -> search.getBytes(UTF_8)
        case `hub` => 200 -> acquisitionPublication(base, format).getBytes(UTF_8)
        case path if path == hub + "original" => 200 -> binary
        case _ => 404 -> "Unexpected request".getBytes(UTF_8)
      }
  }(check)

  private val configuredDeclaration = """format_version: 1
    |default_format: owl-turtle
    |download_dir: ontologies
    |dependencies: []
    |""".stripMargin

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
    "resolve a versioned reference and show every file in human output" in {
      withInspection { (base, requests) =>
        val (out, err, code) = inspectReference(base, "example/z@2.0")
        code shouldBe 0
        err shouldBe empty
        Seq(
          "Ontology: example/z",
          "Version: 2.0",
          base + "/publication",
          base + "/first.ttl",
          base + "/second.ttl",
          "https://example.org/dependency/",
        ).foreach(out should include(_))
        val recorded = requests.asScala.toSeq
        recorded.map(value => new URI(value).getPath) shouldBe Seq("/search", "/publication")
        query(recorded.head)("filter_by") shouldBe
          "namespace:=example && name:=z && version:=2.0"
      }
    }

    "resolve a versioned reference and preserve every publication field in JSON output" in {
      withInspection { (base, requests) =>
        val (out, err, code) = inspectReference(base, "example/z@2.0", "--json")
        code shouldBe 0
        err shouldBe empty
        json(out) shouldBe inspectionPublication(base)
        val recorded = requests.asScala.toSeq
        recorded.map(value => new URI(value).getPath) shouldBe Seq("/search", "/publication")
        query(recorded.head)("filter_by") shouldBe
          "namespace:=example && name:=z && version:=2.0"
      }
    }

    "report a missing reference as text even when JSON is requested" in {
      withServer("""{"hits":[],"found":0,"page":1,
        "request_params":{"per_page":250},"search_cutoff":false}""") { (base, requests) =>
        val (out, err, code) = inspectReference(base, "example/missing", "--json")
        code shouldBe 1
        out shouldBe empty
        err should startWith("Error: No matching release for ontology reference 'example/missing'.")
        err should include("ohi inspect --help")
        requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe Seq("/search")
      }
    }

    "reject a malformed reference before making a request" in {
      withServer(publication) { (base, requests) =>
        val (out, err, code) = inspectReference(base, "example/z@", "--json")
        code shouldBe 1
        out shouldBe empty
        err should include("Invalid ontology reference 'example/z@'.")
        requests shouldBe empty
      }
    }

    "inspect a reference without reading or changing project records in the working directory" in {
      withDirectory { directory =>
        val declaration = "not a valid declaration: ["
        val lock = "sentinel lock bytes"
        os.write(directory / "ohi.yaml", declaration)
        os.write(directory / "ohi.lock.json", lock)
        withInspection { (base, requests) =>
          val (out, err, code) =
            runReference(directory, base, "ConfiguredInspect", "example/z", "--json")
          code shouldBe 0
          err shouldBe empty
          json(out) shouldBe inspectionPublication(base)
          os.read(directory / "ohi.yaml") shouldBe declaration
          os.read(directory / "ohi.lock.json") shouldBe lock
          os.list(directory).map(_.last).toSet shouldBe Set("ohi.yaml", "ohi.lock.json")
          requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe
            Seq("/search", "/publication")
        }
      }
    }

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
      val minimal = json(publication)
      minimal.obj.remove("namespace_iri")
      minimal.obj.remove("preferred_prefix")
      minimal("dependencies") = ujson.Arr()
      withServer(ujson.write(minimal)) { (url, _) =>
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
          result(field).arr shouldBe empty
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
    "reject an ambiguous get reference with recovery guidance and no writes" in {
      withDirectory { directory =>
        val destination = directory / "files here"
        withInspection { (base, requests) =>
          val command = new GetCommand {
            override protected def config(options: GetOptions): CatalogConfig =
              CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name"))
          }
          val (out, err, code) = command.runTestCommandWithExitCode(
            List("example/z@2.0", "--format", "owl-turtle", "--to", destination.toString),
            args => command.main("ohi get", args),
          )
          code shouldBe 1
          out shouldBe empty
          err should startWith("example/z@2.0 has more than one file in owl-turtle format.")
          err should include(s"Files\n  $base/first.ttl\n  $base/second.ttl")
          err should include(s"ohi get $base/first.ttl --to '$destination'")
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

    "reject an ambiguous add reference with recovery guidance and no writes" in {
      withDirectory { directory =>
        val destination = directory / "files here"
        withInspection { (base, requests) =>
          val config =
            CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name"))
          val command = new AddCommand {
            override protected def client() =
              new eu.neverblink.ohi.catalog.CatalogClient(config)
          }
          val (out, err, code) = command.runTestCommandWithExitCode(
            List("example/z@2.0", "--format", "owl-turtle", "--project", directory.toString),
            args => command.main("ohi add", args),
          )
          code shouldBe 1
          out shouldBe empty
          err should startWith("Error: Could not update the project.")
          err should include("example/z@2.0 has more than one file in owl-turtle format.")
          err should include("standalone ohi get for an exact file URL")
          requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe Seq(
            "/search",
            "/publication",
          )
          os.exists(destination) shouldBe false
          os.list(directory) shouldBe empty
        }
      }
    }

    "default a reference to cwd .ohi without consulting an ancestor declaration" in {
      withDirectory { parent =>
        val cwd = parent / "child"
        os.makeDir(cwd)
        os.write(parent / "ohi.yaml", "invalid ancestor declaration")
        withAcquisition("linkml-yaml") { (base, requests) =>
          val (out, err, code) = runReference(cwd, base, "ConfiguredGet", "example/z@2.0", "--json")
          withClue(err) { code shouldBe 0 }
          err shouldBe empty
          json(out) shouldBe json(
            s"""{"url":"$base${hub}original","path":"$cwd/.ohi/example/z/2.0/z.linkml.yaml"}""",
          )
          os.read.bytes(
            cwd / ".ohi" / "example" / "z" / "2.0" / "z.linkml.yaml",
          ).toSeq shouldBe binary.toSeq
          os.read(cwd / ".ohi" / ".gitignore") shouldBe "*\n"
          os.list(cwd).map(_.last) shouldBe Seq(".ohi")
          val calls = requests.asScala.toSeq
          calls.map(value => new URI(value).getPath) shouldBe Seq("/search", hub, hub + "original")
          query(calls.head)("filter_by") shouldBe "namespace:=example && name:=z && version:=2.0"
        }
      }
    }

    "use the same cwd declaration's format and configured destination without changing records" in {
      withDirectory { cwd =>
        os.write(cwd / "ohi.yaml", configuredDeclaration)
        withAcquisition() { (base, requests) =>
          val (out, err, code) = runReference(cwd, base, "ConfiguredGet", "example/z", "--json")
          withClue(err) { code shouldBe 0 }
          err shouldBe empty
          json(out)("path").str shouldBe s"$cwd/ontologies/example/z/2.0/z.owl.ttl"
          os.read.bytes(
            cwd / "ontologies" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe binary.toSeq
          os.read(cwd / "ontologies" / ".gitignore") shouldBe "*\n"
          os.read(cwd / "ohi.yaml") shouldBe configuredDeclaration
          requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe Seq(
            "/search",
            hub,
            hub + "original",
          )
        }
      }
    }

    "read the cwd format with an explicit relative destination and preserve project records" in {
      withDirectory { cwd =>
        os.write(cwd / "ohi.yaml", configuredDeclaration)
        os.write(cwd / "ohi.lock.json", "stale lock bytes")
        withAcquisition() { (base, requests) =>
          val (out, err, code) =
            runReference(cwd, base, "ConfiguredGet", "example/z", "--to", "restored", "--json")
          withClue(err) { code shouldBe 0 }
          err shouldBe empty
          json(out)("path").str shouldBe s"$cwd/restored/example/z/2.0/z.owl.ttl"
          os.read.bytes(
            cwd / "restored" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe binary.toSeq
          os.exists(cwd / "restored" / ".gitignore") shouldBe false
          os.exists(cwd / "ontologies") shouldBe false
          os.read(cwd / "ohi.yaml") shouldBe configuredDeclaration
          os.read(cwd / "ohi.lock.json") shouldBe "stale lock bytes"
          requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe Seq(
            "/search",
            hub,
            hub + "original",
          )
        }
      }
    }

    "bypass a malformed cwd declaration when reference format and destination are explicit" in {
      withDirectory { cwd =>
        os.write(cwd / "ohi.yaml", "invalid declaration")
        withAcquisition() { (base, requests) =>
          val (out, err, code) = runReference(
            cwd,
            base,
            "ConfiguredGet",
            "example/z",
            "--format",
            "owl-turtle",
            "--to",
            ".ohi",
            "--json",
          )
          withClue(err) { code shouldBe 0 }
          err shouldBe empty
          json(out)("path").str shouldBe s"$cwd/.ohi/example/z/2.0/z.owl.ttl"
          os.read.bytes(
            cwd / ".ohi" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe binary.toSeq
          os.exists(cwd / ".ohi" / ".gitignore") shouldBe false
          os.read(cwd / "ohi.yaml") shouldBe "invalid declaration"
          requests.asScala.toSeq.map(value => new URI(value).getPath) shouldBe Seq(
            "/search",
            hub,
            hub + "original",
          )
        }
      }
    }

    "reject a malformed cwd declaration before resolving a reference with implicit format and destination" in {
      withDirectory { cwd =>
        os.write(cwd / "ohi.yaml", "invalid declaration")
        withAcquisition() { (base, requests) =>
          val (out, err, code) = runReference(cwd, base, "ConfiguredGet", "example/z", "--json")
          code shouldBe 1
          out shouldBe empty
          err should include("Invalid declaration")
          err should include("ohi get --help")
          requests shouldBe empty
          os.read(cwd / "ohi.yaml") shouldBe "invalid declaration"
          os.exists(cwd / ".ohi") shouldBe false
        }
      }
    }

    "reject a malformed cwd declaration before resolving a reference with explicit format and implicit destination" in {
      withDirectory { cwd =>
        os.write(cwd / "ohi.yaml", "invalid declaration")
        withAcquisition() { (base, requests) =>
          val (out, err, code) = runReference(
            cwd,
            base,
            "ConfiguredGet",
            "example/z",
            "--format",
            "owl-turtle",
            "--json",
          )
          code shouldBe 1
          out shouldBe empty
          err should include("Invalid declaration")
          err should include("ohi get --help")
          requests shouldBe empty
          os.read(cwd / "ohi.yaml") shouldBe "invalid declaration"
          os.exists(cwd / ".ohi") shouldBe false
        }
      }
    }

    "reject a malformed cwd declaration before requesting a URL with an implicit destination" in {
      withDirectory { cwd =>
        os.write(cwd / "ohi.yaml", "invalid declaration")
        withAcquisition() { (base, requests) =>
          val (out, err, code) =
            runReference(cwd, base, "ConfiguredGet", base + hub + "original", "--json")
          code shouldBe 1
          out shouldBe empty
          err should include("Invalid declaration")
          err should include("ohi get --help")
          requests shouldBe empty
          os.read(cwd / "ohi.yaml") shouldBe "invalid declaration"
          os.exists(cwd / ".ohi") shouldBe false
        }
      }
    }

    "use cwd settings for URL get without reading an unrelated declaration" in {
      withDirectory { root =>
        val cwd = root / "cwd"
        val elsewhere = root / "elsewhere"
        os.makeDir(cwd)
        os.makeDir(elsewhere)
        os.write(cwd / "ohi.yaml", configuredDeclaration)
        os.write(elsewhere / "ohi.yaml", "invalid unrelated declaration")
        withAcquisition() { (base, requests) =>
          val (out, err, code) =
            runReference(cwd, base, "ConfiguredGet", base + hub + "original", "--json")
          withClue(err) { code shouldBe 0 }
          err shouldBe empty
          val receipt = json(out)
          receipt("path").str shouldBe s"$cwd/ontologies/example/z/2.0/z.owl.ttl"
          receipt("url").str shouldBe base + hub + "original"
          os.read.bytes(
            cwd / "ontologies" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe binary.toSeq
          os.read(cwd / "ontologies" / ".gitignore") shouldBe "*\n"
          os.read(cwd / "ohi.yaml") shouldBe configuredDeclaration
          requests.asScala.toSeq shouldBe Seq(hub, hub + "original")
        }
      }
    }

    "bypass malformed cwd settings for URL get with an explicit destination" in {
      withDirectory { cwd =>
        os.write(cwd / "ohi.yaml", "invalid declaration")
        withAcquisition() { (base, requests) =>
          val (out, err, code) = runReference(
            cwd,
            base,
            "ConfiguredGet",
            base + hub + "original",
            "--to",
            "restored",
            "--json",
          )
          withClue(err) { code shouldBe 0 }
          err shouldBe empty
          val receipt = json(out)
          receipt("path").str shouldBe s"$cwd/restored/example/z/2.0/z.owl.ttl"
          os.read.bytes(
            cwd / "restored" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe binary.toSeq
          os.exists(cwd / "restored" / ".gitignore") shouldBe false
          os.read(cwd / "ohi.yaml") shouldBe "invalid declaration"
          requests.asScala.toSeq shouldBe Seq(hub, hub + "original")
        }
      }
    }

    "require exact advertised URL membership before installing a raw Hub file URL" in {
      withDirectory { directory =>
        withAcquisition() { (base, requests) =>
          val error =
            failed(Get, "get", base + hub + "unadvertised", "--to", directory.toString, "--json")
          error should include("does not advertise artifact")
          requests.asScala.toSeq shouldBe Seq(hub)
          os.list(directory) shouldBe empty
        }
      }
    }

    "reject a generic file URL without requesting it or creating a fallback destination" in {
      withDirectory { directory =>
        withServer("unexpected") { (base, requests) =>
          val error =
            json(failed(Get, "get", base + "/file.ttl", "--to", directory.toString, "--json"))
          error("code").str shouldBe "UNSUPPORTED_FILENAME"
          error("url").str shouldBe base + "/file.ttl"
          requests shouldBe empty
          os.list(directory) shouldBe empty
        }
      }
    }

    "summarize a single download and verification while preserving delivery JSON and exact bytes" in {
      withDirectory { cwd =>
        withAcquisition() { (base, requests) =>
          val args = Seq(base + hub + "original", "--to", "restored")
          val first = runReference(cwd, base, "ConfiguredGet", args*)
          first shouldBe (("Downloaded 1 ontology file to restored/.\n", "", 0))
          os.read.bytes(
            cwd / "restored" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe binary.toSeq
          requests.clear()
          val repeated = runReference(cwd, base, "ConfiguredGet", args*)
          repeated shouldBe (("Verified 1 existing ontology file in restored/.\n", "", 0))
          requests.asScala.toSeq shouldBe Seq(hub)
          requests.clear()
          val (out, err, code) = runReference(cwd, base, "ConfiguredGet", (args :+ "--json")*)
          withClue(err) { code shouldBe 0 }
          err shouldBe empty
          json(out) shouldBe json(s"""{
            "url":"$base${hub}original","path":"$cwd/restored/example/z/2.0/z.owl.ttl"}
          """)
          requests.asScala.toSeq shouldBe Seq(hub)
        }
      }
    }

    "report a local hash conflict through the existing file-error shape without artifact requests" in {
      withDirectory { directory =>
        val path = directory / "example" / "z" / "2.0" / "z.owl.ttl"
        os.write(path, "sentinel", createFolders = true)
        withAcquisition() { (base, requests) =>
          val error =
            json(failed(Get, "get", base + hub + "original", "--to", directory.toString, "--json"))
          keys(error) shouldBe Set("code", "message", "url", "directory", "path")
          error("code").str shouldBe "HASH_MISMATCH"
          error("url").str shouldBe base + hub + "original"
          error("directory").str shouldBe directory.toString
          error("path").str shouldBe path.toString
          requests.asScala.toSeq shouldBe Seq(hub)
          os.read(path) shouldBe "sentinel"
        }
      }
    }
  }

  "sync from a shared lock" should {
    "reject the removed get lock option" in {
      val error = failed(Get, "get", "--lock", "ohi.lock.json")
      error should include("--lock")
      error should include("ohi get --help")
    }

    "report only the completed prefix on failure and resume the same command in lock order" in {
      withDirectory { directory =>
        val zBytes = Array[Byte](0, -1, 65, -128)
        val unavailable = new java.util.concurrent.atomic.AtomicBoolean(true)
        val serverError = """{"error":{"reason":null,"retry":true},"attempt":2,"delay":1.25}"""
        withServerResponses(_ => {
          case "/files/packages/example/z/2.0/source.ttl" => 200 -> zBytes
          case "/files/packages/example/a/2.0/source.ttl" if unavailable.get() =>
            503 -> serverError.getBytes(UTF_8)
          case "/files/packages/example/a/2.0/source.ttl" => 200 -> "abc".getBytes(UTF_8)
          case "/files/packages/example/b/2.0/source.ttl" => 200 -> Array.emptyByteArray
          case _ => 404 -> "Unexpected request".getBytes(UTF_8)
        }) { (base, requests) =>
          val path = writeLock(
            directory,
            Seq(
              (base + "/files/packages/example/z/2.0/source.ttl") ->
                "ac7fa209c616517439d0e93add6855a02d7c4340f990084224ea290315ee3bd1",
              (base + "/files/packages/example/a/2.0/source.ttl") ->
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
              (base + "/files/packages/example/b/2.0/source.ttl") ->
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
              (base + "/files/packages/example/m/2.0/source.ttl") ->
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            ),
          )
          val lockBytes = os.read.bytes(path).toSeq
          os.write(
            directory / ".ohi" / "example" / "m" / "2.0" / "m.owl.ttl",
            "hello",
            createFolders = true,
          )
          val mTime =
            Files.getLastModifiedTime(
              (directory / ".ohi" / "example" / "m" / "2.0" / "m.owl.ttl").toNIO,
            )
          val args = Seq("sync", "--project", directory.toString, "--json")
          val error = json(failed(Sync, args*))
          keys(error) shouldBe Set(
            "code",
            "operation",
            "project_directory",
            "declaration_changed",
            "declaration_created",
            "lock_changed",
            "files",
            "message",
            "catalog_error",
          )
          error("operation").str shouldBe "SYNC"
          error("declaration_changed").bool shouldBe false
          error("declaration_created").bool shouldBe false
          error("lock_changed").bool shouldBe false
          error("message").str should include(s"ohi sync --project '$directory'")
          error("files") shouldBe json(s"""[
            {"url":"$base/files/packages/example/z/2.0/source.ttl","path":"$directory/.ohi/example/z/2.0/z.owl.ttl","status":"DOWNLOADED"}
          ]""")
          val cause = error("catalog_error")
          keys(cause) shouldBe Set("code", "message", "operation", "url", "status", "response_json")
          cause("code").str shouldBe "HTTP_ERROR"
          cause("operation").str shouldBe "GET"
          cause("url").str shouldBe base + "/files/packages/example/a/2.0/source.ttl"
          cause("status").num.toInt shouldBe 503
          cause("response_json") shouldBe json(serverError)
          requests.asScala.toSeq shouldBe Seq(
            "/files/packages/example/z/2.0/source.ttl",
            "/files/packages/example/a/2.0/source.ttl",
          )
          os.read.bytes(
            directory / ".ohi" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe zBytes.toSeq
          os.read(directory / ".ohi" / "example" / "m" / "2.0" / "m.owl.ttl") shouldBe "hello"
          Files.getLastModifiedTime(
            (directory / ".ohi" / "example" / "m" / "2.0" / "m.owl.ttl").toNIO,
          ) shouldBe mTime
          os.exists(directory / ".ohi" / "example" / "a" / "2.0" / "a.owl.ttl") shouldBe false
          os.exists(directory / ".ohi" / "example" / "b" / "2.0" / "b.owl.ttl") shouldBe false
          os.read.bytes(path).toSeq shouldBe lockBytes
          os.exists(directory / "ohi.yaml") shouldBe false

          val zTime =
            Files.getLastModifiedTime(
              (directory / ".ohi" / "example" / "z" / "2.0" / "z.owl.ttl").toNIO,
            )
          unavailable.set(false)
          requests.clear()
          json(successful(Sync, args*))("files") shouldBe json(s"""[
            {"url":"$base/files/packages/example/z/2.0/source.ttl","path":"$directory/.ohi/example/z/2.0/z.owl.ttl","status":"VERIFIED"},
            {"url":"$base/files/packages/example/a/2.0/source.ttl","path":"$directory/.ohi/example/a/2.0/a.owl.ttl","status":"DOWNLOADED"},
            {"url":"$base/files/packages/example/b/2.0/source.ttl","path":"$directory/.ohi/example/b/2.0/b.owl.ttl","status":"DOWNLOADED"},
            {"url":"$base/files/packages/example/m/2.0/source.ttl","path":"$directory/.ohi/example/m/2.0/m.owl.ttl","status":"VERIFIED"}
          ]""")
          requests.asScala.toSeq shouldBe Seq(
            "/files/packages/example/a/2.0/source.ttl",
            "/files/packages/example/b/2.0/source.ttl",
          )
          os.read.bytes(
            directory / ".ohi" / "example" / "z" / "2.0" / "z.owl.ttl",
          ).toSeq shouldBe zBytes.toSeq
          Files.getLastModifiedTime(
            (directory / ".ohi" / "example" / "z" / "2.0" / "z.owl.ttl").toNIO,
          ) shouldBe zTime
          os.read(directory / ".ohi" / "example" / "a" / "2.0" / "a.owl.ttl") shouldBe "abc"
          os.read.bytes(directory / ".ohi" / "example" / "b" / "2.0" / "b.owl.ttl") shouldBe empty
          os.read(directory / ".ohi" / "example" / "m" / "2.0" / "m.owl.ttl") shouldBe "hello"
          Files.getLastModifiedTime(
            (directory / ".ohi" / "example" / "m" / "2.0" / "m.owl.ttl").toNIO,
          ) shouldBe mTime
          os.read.bytes(path).toSeq shouldBe lockBytes
          os.exists(directory / "ohi.yaml") shouldBe false
        }
      }
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
          error("code").str shouldBe "HTTP_ERROR"
          error("operation").str shouldBe "INSPECT"
          error("url").str shouldBe url + "/absent?revision=2"
          error("status").num.toInt shouldBe 404
          error("response_json") shouldBe json(
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

// The child process retains production inspect routing with a local test catalog endpoint.
private[cli] object ConfiguredInspect {
  def command(searchUrl: String): InspectCommand = new InspectCommand {
    override protected def config(options: InspectOptions): CatalogConfig =
      super.config(options).copy(searchUrl = Some(Uri(searchUrl)), queryBy = Some("name"))
  }

  def main(args: Array[String]): Unit =
    command(args.head).main("ohi inspect", args.drop(1))
}
