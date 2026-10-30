package eu.neverblink.ohi.cli

import com.sun.net.httpserver.{HttpExchange, HttpServer}
import eu.neverblink.linkml.cli.BaseCommand
import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.catalog.CatalogClient
import eu.neverblink.ohi.model.generated.config.CatalogConfig
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml
import scala.jdk.CollectionConverters.*

class ProjectCommandsSpec extends AnyWordSpec, Matchers {

  private def json(text: String): ujson.Value = ujson.read(text)

  private def keys(node: ujson.Value): Set[String] = node.obj.keySet.toSet
  private val emptyDeclaration = "format_version: 1\nselections: []\n"

  private def withDirectory(check: os.Path => Unit): Unit = {
    val directory = os.temp.dir(prefix = "ohi project commands ")
    try check(os.Path(directory.toNIO.toRealPath()))
    finally os.remove.all(directory)
  }

  private def withServer(responses: String => Map[String, (Int, String)])(
      check: (String, ConcurrentLinkedQueue[String]) => Unit,
  ): Unit = {
    val requests = new ConcurrentLinkedQueue[String]()
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    val base = s"http://127.0.0.1:${server.getAddress.getPort}"
    val routes = responses(base)
    server.createContext(
      "/",
      (exchange: HttpExchange) => {
        requests.add(s"${exchange.getRequestMethod} ${exchange.getRequestURI}")
        val (status, body) =
          routes.getOrElse(
            exchange.getRequestURI.toString,
            routes.getOrElse(exchange.getRequestURI.getPath, 404 -> "Unexpected request"),
          )
        val bytes = body.getBytes(UTF_8)
        try {
          exchange.sendResponseHeaders(status, bytes.length.toLong)
          exchange.getResponseBody.write(bytes)
        } finally exchange.close()
      },
    )
    server.start()
    try check(base, requests)
    finally server.stop(0)
  }

  // Synthetic wire metadata crosses the real SDK decoder without production model construction.
  private def publication(
      baseUrl: String,
      artifacts: Seq[String],
      dependencies: Seq[String] = Seq.empty,
      format: String = "owl-turtle",
  ): String = {
    val files = artifacts.map(url => s"""{
      "format":"$format","origin":"UPSTREAM","url":"$url",
      "roots":["https://example.org/entry-root"],"size_bytes":4,
      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }""").mkString(",")
    val refs = dependencies.map(url => s"""{"base_url":"$url"}""").mkString(",")
    s"""{
      "schema_version":2,"base_url":"$baseUrl","namespace":"test","name":"example",
      "version":"1","title":"Example ontology","description":"Synthetic command metadata",
      "published_at":"2026-10-15T00:00:00Z","publisher":"Example publisher",
      "licenses":[],"keywords":[],"artifacts":[$files],"dependencies":[$refs]
    }"""
  }

  private def searchResponse(baseUrl: String): String = s"""{
    "found":1,"page":1,"request_params":{"per_page":250},"search_cutoff":false,"hits":[{"document":{
      "base_url":"$baseUrl","namespace":"test","name":"example","version":"1",
      "title":"Example ontology","description":"Synthetic command metadata",
      "published_at":"2026-10-15T00:00:00Z","licenses":[],"formats":["owl-turtle"]
    }}]
  }"""

  private def run(command: BaseCommand[?], args: String*): (String, String, Int) =
    command.runTestCommandWithExitCode(args.toList, new OhiApp().main)

  private def addArguments(
      baseUrl: String,
      artifact: String,
      project: String,
      to: String,
  ): Seq[String] =
    Seq("add", baseUrl, "--artifact", artifact, "--project", project, "--to", to, "--json")

  private def runProcess(cwd: os.Path, args: String*): (String, String, Int) = {
    val result = os.proc(
      os.Path(System.getProperty("java.home")) / "bin" / "java",
      "-cp",
      System.getProperty("java.class.path"),
      "eu.neverblink.ohi.cli.Main",
      args,
    ).call(cwd = cwd, check = false, stderr = os.Pipe, timeout = 10000)
    (result.out.text(), result.err.text(), result.exitCode)
  }

  private def successful(result: (String, String, Int)): String = {
    val (out, err, code) = result
    withClue(s"stderr was: $err\n") { code shouldBe 0 }
    err shouldBe empty
    out should endWith("\n")
    out should not include "\u001b"
    out
  }

  private def failed(result: (String, String, Int)): String = {
    val (out, err, code) = result
    code shouldBe 1
    out shouldBe empty
    err should not be empty
    err
  }

  // Usage errors stay plain text even with --json and name the command's help.
  private def usageError(command: BaseCommand[?], args: String*): String = {
    val error = failed(run(command, args*))
    error should include(s"ohi ${args.head} --help")
    error.trim should not startWith "{"
    error
  }

  private def declaration(path: os.Path): ujson.Value =
    json(JsonUtil.yamlToJson(parseYaml(os.read(path)).toOption.get))

  private def assertResult(
      text: String,
      operation: String,
      project: os.Path,
      declarationChanged: Boolean,
      lockChanged: Boolean,
      delivery: Option[(String, os.Path)] = None,
  ): Unit = {
    val expected = ujson.Obj(
      "operation" -> operation,
      "project_directory" -> project.toString,
      "declaration_changed" -> declarationChanged,
      "lock_changed" -> lockChanged,
    )
    delivery.foreach { (url, path) =>
      expected("delivery") = ujson.Obj("url" -> url, "path" -> path.toString)
    }
    json(text) shouldBe expected
  }

  private def unchangedError(
      text: String,
      operation: String,
      project: os.Path,
      code: String,
      cause: Option[String] = None,
  ): ujson.Value = {
    val error = json(text)
    keys(error) shouldBe Set(
      "operation",
      "project_directory",
      "code",
      "message",
      "declaration_changed",
      "lock_changed",
    ) ++ cause
    error("operation").str shouldBe operation
    error("project_directory").str shouldBe project.toString
    error("code").str shouldBe code
    error("declaration_changed").bool shouldBe false
    error("lock_changed").bool shouldBe false
    error("message").str should not be empty
    error
  }

  "project command workflow" should {
    "resolve a named ontology and record its exact URLs without inspecting it twice" in {
      withDirectory { root =>
        val project = root / "project"
        val downloads = root / "downloads"
        os.makeDir(project)
        withServer(base =>
          Map(
            "/search" -> (200 -> searchResponse(base + "/publication")),
            "/publication" -> (200 -> publication(base + "/publication", Seq(base + "/file.ttl"))),
            "/file.ttl" -> (200 -> "one\n"),
          ),
        ) { (base, requests) =>
          val command = new AddCommand {
            override protected def client(): CatalogClient =
              new CatalogClient(
                CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name")),
              )
          }
          val result = command.runTestCommandWithExitCode(
            List(
              "test/example",
              "--format",
              "owl-turtle",
              "--project",
              project.toString,
              "--to",
              downloads.toString,
              "--json",
            ),
            args => command.main("ohi add", args),
          )
          assertResult(
            successful(result),
            "ADD",
            project,
            true,
            true,
            Some((base + "/file.ttl") -> (downloads / "file.ttl")),
          )
          declaration(project / "ohi.yaml") shouldBe json(s"""{
            "format_version":1,"selections":[{"base_url":"$base/publication","url":"$base/file.ttl"}]
          }""")
          json(os.read(project / "ohi.lock.json")) shouldBe json(s"""{
            "format_version":2,"selections":[{"base_url":"$base/publication","url":"$base/file.ttl"}],
            "files":[{"base_url":"$base/publication","url":"$base/file.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]
          }""")
          os.read(downloads / "file.ttl") shouldBe "one\n"
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe
            Seq("GET /search", "GET /publication", "GET /file.ttl")
        }
      }
    }

    for (format, filename, behavior) <- Seq(
        ("owl-turtle", "file.ttl", "use the selected project's common format"),
        ("linkml-yaml", "file.yaml", "fall back to LinkML when the project format is unavailable"),
      )
    do
      s"$behavior and reuse inspected metadata when building the lock" in {
        withDirectory { root =>
          val project = root / "project"
          val downloads = root / "downloads"
          os.makeDir(project)
          os.makeDir(downloads)
          os.write(downloads / "ohi.yaml", "unrelated destination declaration")
          withServer(base =>
            Map(
              "/existing" -> (200 -> publication(base + "/existing", Seq(base + "/selected.yaml"))),
              "/search" -> (200 -> searchResponse(base + "/publication")),
              "/publication" -> (200 -> publication(
                base + "/publication",
                Seq(base + "/" + filename),
                format = format,
              )),
              ("/" + filename) -> (200 -> "selected bytes\n"),
            ),
          ) { (base, requests) =>
            os.write(
              project / "ohi.yaml",
              s"""format_version: 1
              |selections:
              |  - base_url: $base/existing
              |    url: $base/selected.yaml
              |""".stripMargin,
            )
            os.write(
              project / "ohi.lock.json",
              """{"format_version":2,"selections":[],"files":[]}""",
            )
            val command = new AddCommand {
              override protected def client(): CatalogClient =
                new CatalogClient(
                  CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name")),
                )
            }
            val result = command.runTestCommandWithExitCode(
              List(
                "test/example",
                "--project",
                project.toString,
                "--to",
                downloads.toString,
                "--json",
              ),
              args => command.main("ohi add", args),
            )
            assertResult(
              successful(result),
              "ADD",
              project,
              true,
              true,
              Some((base + "/" + filename) -> (downloads / filename)),
            )
            declaration(project / "ohi.yaml") shouldBe json(s"""{
              "format_version":1,"selections":[
                {"base_url":"$base/existing","url":"$base/selected.yaml"},
                {"base_url":"$base/publication","url":"$base/$filename"}]
            }""")
            val locked = json(os.read(project / "ohi.lock.json"))
            locked("files") shouldBe json(s"""[
              {"base_url":"$base/existing","url":"$base/selected.yaml","format":"owl-turtle",
               "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
              {"base_url":"$base/publication","url":"$base/$filename","format":"$format",
               "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]
            """)
            os.read(downloads / filename) shouldBe "selected bytes\n"
            os.read(downloads / "ohi.yaml") shouldBe "unrelated destination declaration"
            requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe
              Seq("GET /existing", "GET /search", "GET /publication", "GET /" + filename)
          }
        }
      }

    "report an invalid declaration before searching for an omitted-format add" in {
      withDirectory { project =>
        val invalid = "format_version: 99\nselections: []\n"
        os.write(project / "ohi.yaml", invalid)
        withServer(_ => Map.empty) { (base, requests) =>
          val command = new AddCommand {
            override protected def client(): CatalogClient =
              new CatalogClient(
                CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name")),
              )
          }
          val result = command.runTestCommandWithExitCode(
            List("test/example", "--project", project.toString, "--json"),
            args => command.main("ohi add", args),
          )
          unchangedError(failed(result), "ADD", project, "INVALID_DECLARATION")
          requests shouldBe empty
          os.read(project / "ohi.yaml") shouldBe invalid
          os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
        }
      }
    }

    "leave project records and the destination untouched when a reference lookup fails" in {
      withDirectory { project =>
        val downloads = project / "downloads"
        val lock = """{"format_version":1,"selections":[],"files":[]}"""
        os.write(project / "ohi.yaml", emptyDeclaration)
        os.write(project / "ohi.lock.json", lock)
        withServer(_ =>
          Map(
            "/search" -> (200 ->
              """{"found":0,"page":1,"request_params":{"per_page":250},"search_cutoff":false,"hits":[]}"""),
          ),
        ) { (base, requests) =>
          val command = new AddCommand {
            override protected def client(): CatalogClient =
              new CatalogClient(
                CatalogConfig(searchUrl = Some(Uri(base + "/search")), queryBy = Some("name")),
              )
          }
          val error = failed(
            command.runTestCommandWithExitCode(
              List(
                "test/missing",
                "--format",
                "owl-turtle",
                "--project",
                project.toString,
                "--to",
                downloads.toString,
                "--json",
              ),
              args => command.main("ohi add", args),
            ),
          )
          error should include("No matching release for ontology reference 'test/missing'")
          error should include("ohi add --help")
          error.trim should not startWith "{"
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq("GET /search")
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.read(project / "ohi.lock.json") shouldBe lock
          os.exists(downloads) shouldBe false
        }
      }
    }

    "resolve paths from cwd, download selected files and refresh a legacy lock from edited selections" in {
      withDirectory { root =>
        val cwd = root / "invocation"
        val project = root / "project"
        os.makeDir(cwd)
        withServer(base =>
          Map(
            "/z" -> (200 -> publication(
              base + "/z",
              Seq(base + "/z.ttl", base + "/z-alt.ttl"),
              Seq(base + "/a"),
            )),
            "/b" -> (200 -> publication(base + "/b", Seq(base + "/b.ttl"))),
            "/a" -> (200 -> publication(base + "/a", Seq(base + "/a.ttl"))),
            "/z.ttl" -> (200 -> "one\n"),
            "/b.ttl" -> (200 -> "two\n"),
          ),
        ) { (base, requests) =>
          assertResult(
            successful(runProcess(cwd, "init", "../project", "--json")),
            "INIT",
            project,
            true,
            false,
          )
          declaration(project / "ohi.yaml") shouldBe json(
            """{"format_version":1,"selections":[]}""",
          )
          os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
          requests shouldBe empty

          os.write.over(project / "ohi.yaml", "# removed by append\n" + emptyDeclaration)
          val firstAdd = successful(
            runProcess(
              cwd,
              "add",
              base + "/z",
              "--artifact",
              base + "/z.ttl",
              "--project",
              "../project",
              "--json",
            ),
          )
          assertResult(
            firstAdd,
            "ADD",
            project,
            true,
            true,
            Some((base + "/z.ttl") -> (cwd / "z.ttl")),
          )
          requests.asScala.toSeq shouldBe Seq("GET /z", "GET /a", "GET /z.ttl")
          os.read(cwd / "z.ttl") shouldBe "one\n"
          os.exists(project / "downloads") shouldBe false
          os.list(project).map(_.last).toSet shouldBe Set("ohi.yaml", "ohi.lock.json")
          os.read(project / "ohi.yaml") should not include "# removed by append"
          declaration(project / "ohi.yaml") shouldBe json(s"""{
            "format_version":1,"selections":[{"base_url":"$base/z","url":"$base/z.ttl"}]
          }""")
          json(os.read(project / "ohi.lock.json")) shouldBe json(s"""{
            "format_version":2,"selections":[{"base_url":"$base/z","url":"$base/z.ttl"}],
            "files":[{"base_url":"$base/z","url":"$base/z.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
                     {"base_url":"$base/a","url":"$base/a.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]
          }""")

          requests.clear()
          val secondAdd = successful(
            run(
              Add,
              addArguments(
                base + "/b",
                base + "/b.ttl",
                project.toString,
                (cwd / "downloads").toString,
              )*,
            ),
          )
          assertResult(
            secondAdd,
            "ADD",
            project,
            true,
            true,
            Some((base + "/b.ttl") -> (cwd / "downloads" / "b.ttl")),
          )
          declaration(project / "ohi.yaml") shouldBe json(s"""{
            "format_version":1,"selections":[{"base_url":"$base/z","url":"$base/z.ttl"},
                                             {"base_url":"$base/b","url":"$base/b.ttl"}]
          }""")
          requests.asScala.toSeq shouldBe Seq("GET /z", "GET /b", "GET /a", "GET /b.ttl")
          os.read(cwd / "downloads" / "b.ttl") shouldBe "two\n"
          json(os.read(project / "ohi.lock.json")) shouldBe json(s"""{
            "format_version":2,"selections":[{"base_url":"$base/z","url":"$base/z.ttl"},
                                             {"base_url":"$base/b","url":"$base/b.ttl"}],
            "files":[{"base_url":"$base/z","url":"$base/z.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
                     {"base_url":"$base/a","url":"$base/a.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
                     {"base_url":"$base/b","url":"$base/b.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]
          }""")

          val repeated = "# preserved for repeated selection\n" + os.read(project / "ohi.yaml")
          os.write.over(project / "ohi.yaml", repeated)
          requests.clear()
          val repeatedAdd = successful(
            run(
              Add,
              addArguments(
                base + "/z",
                base + "/z.ttl",
                project.toString,
                (cwd / "repeated").toString,
              )*,
            ),
          )
          assertResult(
            repeatedAdd,
            "ADD",
            project,
            false,
            true,
            Some((base + "/z.ttl") -> (cwd / "repeated" / "z.ttl")),
          )
          os.read(project / "ohi.yaml") shouldBe repeated
          os.read(cwd / "repeated" / "z.ttl") shouldBe "one\n"
          requests.asScala.toSeq shouldBe Seq("GET /z", "GET /b", "GET /a", "GET /z.ttl")

          val edited = s"""# preserved by lock refresh
            |format_version: 1
            |selections:
            |  - base_url: $base/b
            |    url: $base/b.ttl
            |  - base_url: $base/z
            |    url: $base/z-alt.ttl
            |""".stripMargin
          os.write.over(project / "ohi.yaml", edited)
          os.write.over(
            project / "ohi.lock.json",
            s"""{
            "format_version":1,"selections":[{"base_url":"$base/z","url":"$base/z.ttl"}],
            "files":[{"base_url":"$base/z","url":"$base/z.ttl","format":"owl-turtle"},
                     {"base_url":"$base/a","url":"$base/a.ttl","format":"owl-turtle"}]
          }""",
          )
          requests.clear()
          assertResult(
            successful(runProcess(cwd, "lock", "--project", "../project", "--json")),
            "LOCK",
            project,
            false,
            true,
          )
          requests.asScala.toSeq shouldBe Seq("GET /b", "GET /z", "GET /a")
          os.read(project / "ohi.yaml") shouldBe edited
          json(os.read(project / "ohi.lock.json")) shouldBe json(s"""{
            "format_version":2,"selections":[{"base_url":"$base/b","url":"$base/b.ttl"},
                                             {"base_url":"$base/z","url":"$base/z-alt.ttl"}],
            "files":[{"base_url":"$base/b","url":"$base/b.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
                     {"base_url":"$base/z","url":"$base/z-alt.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"},
                     {"base_url":"$base/a","url":"$base/a.ttl","format":"owl-turtle",
                      "sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]
          }""")
          os.list(cwd / "downloads").map(_.last).toSet shouldBe Set("b.ttl")
          os.read(cwd / "z.ttl") shouldBe "one\n"
          os.read(cwd / "downloads" / "b.ttl") shouldBe "two\n"
        }
      }
    }

    "initialize with an existing declaration while leaving a malformed lock unread" in {
      withDirectory { project =>
        val original = "# keep this comment\n" + emptyDeclaration
        os.write(project / "ohi.yaml", original)
        os.write(project / "ohi.lock.json", "malformed lock sentinel")
        assertResult(
          successful(run(Init, "init", project.toString, "--json")),
          "INIT",
          project,
          false,
          false,
        )
        os.read(project / "ohi.lock.json") shouldBe "malformed lock sentinel"
        os.read(project / "ohi.yaml") shouldBe original
        os.list(project).map(_.last).toSet shouldBe Set("ohi.yaml", "ohi.lock.json")
      }
    }

    "report completed initialization effects in human output without requiring JSON" in {
      withDirectory { root =>
        val project = root / "two  spaces"
        val out = successful(run(Init, "init", project.toString))
        out shouldBe s"Created $project/ohi.yaml\n\nNext: ohi add --help\nDocs: https://ohi.dev/docs/\n"
        declaration(project / "ohi.yaml") shouldBe json("""{"format_version":1,"selections":[]}""")
        os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
        successful(run(Init, "init", project.toString)) shouldBe
          s"$project/ohi.yaml already exists. No changes made.\n"
      }
    }
  }

  "project command failures" should {
    "refuse lock when ohi.yaml is missing" in {
      withDirectory { root =>
        val project = root / "project"
        os.makeDir(project)
        unchangedError(
          failed(run(Lock, "lock", "--project", project.toString, "--json")),
          "LOCK",
          project,
          "INVALID_DECLARATION",
        )
        os.list(project) shouldBe empty
      }
    }

    "refuse init when the existing ohi.yaml is invalid and keep both records" in {
      withDirectory { root =>
        val project = root / "project"
        os.makeDir(project)
        os.write(project / "ohi.yaml", "selections: [")
        os.write(project / "ohi.lock.json", "untouched lock")
        val error = unchangedError(
          failed(run(Init, "init", project.toString, "--json")),
          "INIT",
          project,
          "INVALID_DECLARATION",
        )
        error("message").str should include((project / "ohi.yaml").toString)
        os.read(project / "ohi.yaml") shouldBe "selections: ["
        os.read(project / "ohi.lock.json") shouldBe "untouched lock"
      }
    }

    "refuse lock when the project directory is missing" in {
      withDirectory { root =>
        val project = root / "project"
        unchangedError(
          failed(run(Lock, "lock", "--project", project.toString, "--json")),
          "LOCK",
          project,
          "INVALID_PROJECT",
        )
        os.exists(project) shouldBe false
      }
    }

    "refuse add when ohi.lock.json exists without ohi.yaml, before any request" in {
      withDirectory { root =>
        val project = root / "project"
        val lock = """{"format_version":1,"selections":[],"files":[]}"""
        os.makeDir(project)
        os.write(project / "ohi.lock.json", lock)
        withServer(base =>
          Map(
            "/publication" -> (200 -> publication(base + "/publication", Seq(base + "/file.ttl"))),
          ),
        ) { (base, requests) =>
          val result = run(
            Add,
            addArguments(
              base + "/publication",
              base + "/file.ttl",
              project.toString,
              (root / "downloads").toString,
            )*,
          )
          unchangedError(failed(result), "ADD", project, "INVALID_PROJECT")
          requests shouldBe empty
          os.exists(project / "ohi.yaml") shouldBe false
          os.read(project / "ohi.lock.json") shouldBe lock
          os.exists(root / "downloads") shouldBe false
        }
      }
    }

    "refuse lock when the existing ohi.lock.json is invalid, before any request" in {
      withDirectory { root =>
        val project = root / "project"
        os.makeDir(project)
        os.write(project / "ohi.lock.json", "invalid lock sentinel")
        withServer(base =>
          Map(
            "/publication" -> (200 -> publication(base + "/publication", Seq(base + "/file.ttl"))),
          ),
        ) { (base, requests) =>
          val declarationText = "format_version: 1\nselections:\n" +
            s"- base_url: $base/publication\n  url: $base/file.ttl\n"
          os.write(project / "ohi.yaml", declarationText)
          val error = unchangedError(
            failed(run(Lock, "lock", "--project", project.toString, "--json")),
            "LOCK",
            project,
            "OPERATION_FAILED",
            Some("lock_error"),
          )
          val cause = error("lock_error")
          cause("code").str shouldBe "INVALID_LOCK"
          cause("operation").str shouldBe "READ"
          cause("path").str shouldBe (project / "ohi.lock.json").toString
          requests shouldBe empty
          os.read(project / "ohi.yaml") shouldBe declarationText
          os.read(project / "ohi.lock.json") shouldBe "invalid lock sentinel"
        }
      }
    }

    "preserve a destination collision as the only nested cause before metadata or record writes" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        val downloads = project / "downloads"
        os.makeDir(downloads)
        os.write(downloads / "file.ttl", "existing file")
        withServer(_ => Map.empty) { (base, requests) =>
          val result = run(
            Add,
            addArguments(
              base + "/publication",
              base + "/file.ttl",
              project.toString,
              downloads.toString,
            )*,
          )
          val error = unchangedError(
            failed(result),
            "ADD",
            project,
            "OPERATION_FAILED",
            Some("file_error"),
          )
          error("file_error")("code").str shouldBe "DESTINATION_EXISTS"
          error("file_error")("url").str shouldBe base + "/file.ttl"
          error("file_error")("path").str shouldBe (downloads / "file.ttl").toString
          requests shouldBe empty
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.exists(project / "ohi.lock.json") shouldBe false
          os.read(downloads / "file.ttl") shouldBe "existing file"
          os.list(downloads).map(_.last) shouldBe Seq("file.ttl")
        }
      }
    }

    "preserve nested server JSON under catalog_error without alternate causes or completed writes" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        val response =
          """{"error":{"reasons":["absent",{"retry":false},null],"detail":null},"status":404,"ratio":1.25}"""
        withServer(_ => Map("/missing" -> (404 -> response))) { (base, requests) =>
          val downloads = project / "downloads"
          val result = run(
            Add,
            addArguments(
              base + "/missing",
              base + "/file.ttl",
              project.toString,
              downloads.toString,
            )*,
          )
          val error = unchangedError(
            failed(result),
            "ADD",
            project,
            "OPERATION_FAILED",
            Some("catalog_error"),
          )
          val cause = error("catalog_error")
          keys(cause) shouldBe Set("code", "message", "operation", "url", "status", "response_json")
          cause("code").str shouldBe "HTTP_ERROR"
          cause("operation").str shouldBe "INSPECT"
          cause("url").str shouldBe base + "/missing"
          cause("status").num.toInt shouldBe 404
          cause("response_json") shouldBe json(
            """{"error":{"reasons":["absent",{"retry":false},null],"detail":null},"status":404,"ratio":1.25}""",
          )
          requests.asScala.toSeq shouldBe Seq("GET /missing")
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.exists(project / "ohi.lock.json") shouldBe false
          os.exists(downloads) shouldBe false
        }
      }
    }
  }

  "project command options" should {
    "show add help with its supported project options" in {
      val (raw, err, code) = run(Add, "add", "--help")
      code shouldBe 0
      err shouldBe empty
      val out = raw.replaceAll("\u001b\\[[0-9;]*m", "")
      out should include("Usage\n  ohi add")
      Seq("--artifact", "--format", "--to", "--project", "--json").foreach(out should include(_))
      out should not include "--timeout-ms"
      out should include("[--format <format>]")
      out should include("common format in ohi.yaml, then linkml-yaml")
    }

    "reject add without --artifact and point to its help" in {
      usageError(Add, "add", "https://example.org/source", "--to", ".", "--json") should include(
        "--artifact",
      )
    }

    for (description, arguments, expected) <- Seq(
        (
          "reject --artifact with an ontology reference",
          Seq("test/example", "--artifact", "https://example.org/file"),
          "--artifact cannot be used",
        ),
        (
          "reject --format with a publication URL",
          Seq("https://example.org/source", "--format", "owl-turtle"),
          "--format cannot be used",
        ),
      )
    do
      description in {
        usageError(Add, (Seq("add") ++ arguments ++ Seq("--json"))*) should include(expected)
      }

    "reject add without a publication URL and point to its help" in {
      usageError(
        Add,
        "add",
        "--artifact",
        "https://example.org/file",
        "--to",
        ".",
        "--json",
      ) should include("ohi add <namespace>/<name>")
    }

    "reject init with two directories and point to its help" in {
      usageError(Init, "init", "first", "second", "--json") should include(
        "Init accepts at most one directory.",
      )
    }

    "reject lock with an argument and point to its help" in {
      usageError(Lock, "lock", "unexpected", "--json") should include(
        "Select the project directory with --project.",
      )
    }
  }
}
