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
  private val searchPath = "/search/collections/packages/documents/search"
  private val latestPath = "/search/collections/packages_latest/documents/search"

  private def json(text: String): ujson.Value = ujson.read(text)

  private def keys(node: ujson.Value): Set[String] = node.obj.keySet.toSet
  private val emptyDeclaration =
    "format_version: 1\ndefault_format: linkml-yaml\ndownload_dir: .ohi\ndependencies: []\n"

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
      name: String = "example",
      sha256: String = "2c8b08da5ce60398e1f19af0e5dccc744df274b826abe585eaba68c525434806",
  ): String = {
    val files = artifacts.map(url => s"""{
      "format":"$format","origin":"UPSTREAM","url":"$url",
      "roots":["https://example.org/entry-root"],"size_bytes":4,
      "sha256":"$sha256"
    }""").mkString(",")
    val refs = dependencies.map(url => s"""{"base_url":"$url"}""").mkString(",")
    s"""{
      "schema_version":2,"base_url":"$baseUrl","namespace":"test","name":"$name",
      "version":"1","title":"Example ontology","description":"Synthetic command metadata",
      "published_at":"2026-10-15T00:00:00Z","publisher":"Example publisher",
      "licenses":[],"keywords":[],"artifacts":[$files],"dependencies":[$refs]
    }"""
  }

  // The latest search answers test/example@1 at `baseUrl`.
  private def searchResponse(baseUrl: String): String =
    s"""{"found":1,"page":1,"request_params":{"per_page":10},"search_cutoff":false,"hits":[
      {"document":{
        "base_url":"$baseUrl","namespace":"test","name":"example","version":"1",
        "title":"Example ontology","description":"Synthetic command metadata",
        "published_at":"2026-10-15T00:00:00Z","licenses":[],"formats":["owl-turtle"]
      }}]}"""

  private def catalog(base: String): CatalogClient = new CatalogClient(
    CatalogConfig(searchUrl = Some(Uri(base + searchPath)), queryBy = Some("name")),
  )

  private def runAdd(base: String, args: String*): (String, String, Int) = {
    val command = new AddCommand {
      override protected def client(): CatalogClient = catalog(base)
    }
    command.runTestCommandWithExitCode(args.toList, values => command.main("ohi add", values))
  }

  private def runLock(base: String, project: os.Path): (String, String, Int) = {
    val command = new LockCommand {
      override protected def client(): CatalogClient = catalog(base)
    }
    command.runTestCommandWithExitCode(
      List("--project", project.toString, "--json"),
      values => command.main("ohi lock", values),
    )
  }

  private def runSync(base: String, project: os.Path, args: String*): (String, String, Int) = {
    val command = new SyncCommand {
      override protected def client(options: SyncOptions): CatalogClient = {
        options.timeoutMs shouldBe Some(1234)
        catalog(base)
      }
    }
    command.runTestCommandWithExitCode(
      List(
        "--project",
        project.toString,
        "--json",
        "--timeout-ms",
        "1234",
        "--color",
        "never",
      ) ++ args,
      values => command.main("ohi sync", values),
    )
  }

  private def run(command: BaseCommand[?], args: String*): (String, String, Int) =
    command.runTestCommandWithExitCode(args.toList, new OhiApp().main)

  private def addArguments(
      baseUrl: String,
      artifact: String,
      project: String,
  ): Seq[String] =
    Seq("add", baseUrl, "--artifact", artifact, "--project", project, "--json")

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
      files: Seq[(String, os.Path, String)] = Seq.empty,
      declarationCreated: Boolean = false,
  ): Unit = {
    val expected = ujson.Obj(
      "operation" -> operation,
      "project_directory" -> project.toString,
      "declaration_changed" -> declarationChanged,
      "declaration_created" -> declarationCreated,
      "lock_changed" -> lockChanged,
    )
    expected("files") = ujson.Arr.from(files.map { (url, path, status) =>
      ujson.Obj("url" -> url, "path" -> path.toString, "status" -> status)
    })
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
      "declaration_created",
      "lock_changed",
      "files",
    ) ++ cause
    error("operation").str shouldBe operation
    error("project_directory").str shouldBe project.toString
    error("code").str shouldBe code
    error("declaration_changed").bool shouldBe false
    error("declaration_created").bool shouldBe false
    error("lock_changed").bool shouldBe false
    error("message").str should not be empty
    error("files") shouldBe json("[]")
    error
  }

  "project command workflow" should {
    "dispatch sync --init and report declaration creation while preserving the supplied lock" in {
      withDirectory { project =>
        val supplied = "{\"format_version\":2,\"selections\":[],\"files\":[]}\n"
        os.write(project / "ohi.lock.json", supplied)
        assertResult(
          successful(run(Sync, "sync", "--init", "--project", project.toString, "--json")),
          "SYNC",
          project,
          declarationChanged = true,
          lockChanged = false,
          declarationCreated = true,
        )
        declaration(project / "ohi.yaml") shouldBe json(
          """{"format_version":1,"default_format":"linkml-yaml","download_dir":".ohi","dependencies":[]}""",
        )
        os.read(project / "ohi.lock.json") shouldBe supplied
        os.exists(project / ".ohi") shouldBe false
      }
    }

    "install the complete lock under the selected project and verify it on repeated add" in {
      withDirectory { root =>
        val project = root / "project"
        os.makeDir(project)
        withServer(base =>
          Map(
            latestPath -> (200 -> searchResponse(base + "/files/packages/test/example/1/")),
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/original.ttl"),
              Seq(base + "/files/packages/test/dependency/1/"),
            )),
            "/files/packages/test/dependency/1/" -> (200 -> publication(
              base + "/files/packages/test/dependency/1/",
              Seq(base + "/files/packages/test/dependency/1/source.ttl"),
              name = "dependency",
            )),
            "/files/packages/test/example/1/original.ttl" -> (200 -> "one\n"),
            "/files/packages/test/dependency/1/source.ttl" -> (200 -> "one\n"),
          ),
        ) { (base, requests) =>
          val args =
            Seq("test/example", "--format", "owl-turtle", "--project", project.toString, "--json")
          val direct = project / ".ohi" / "test" / "example" / "1" / "example.owl.ttl"
          val dependency = project / ".ohi" / "test" / "dependency" / "1" / "dependency.owl.ttl"
          assertResult(
            successful(runAdd(base, args*)),
            "ADD",
            project,
            true,
            true,
            Seq(
              (base + "/files/packages/test/example/1/original.ttl", direct, "DOWNLOADED"),
              (base + "/files/packages/test/dependency/1/source.ttl", dependency, "DOWNLOADED"),
            ),
            declarationCreated = true,
          )
          declaration(project / "ohi.yaml") shouldBe json("""{
            "format_version":1,"default_format":"linkml-yaml","download_dir":".ohi",
            "dependencies":[{"ontology":"test/example@1","format":"owl-turtle"}]
          }""")
          json(os.read(project / "ohi.lock.json")) shouldBe json(s"""{
            "format_version":2,"selections":[{"base_url":"$base/files/packages/test/example/1/","url":"$base/files/packages/test/example/1/original.ttl"}],
            "files":[
              {"base_url":"$base/files/packages/test/example/1/","url":"$base/files/packages/test/example/1/original.ttl","format":"owl-turtle","sha256":"2c8b08da5ce60398e1f19af0e5dccc744df274b826abe585eaba68c525434806"},
              {"base_url":"$base/files/packages/test/dependency/1/","url":"$base/files/packages/test/dependency/1/source.ttl","format":"owl-turtle","sha256":"2c8b08da5ce60398e1f19af0e5dccc744df274b826abe585eaba68c525434806"}]
          }""")
          os.read(direct) shouldBe "one\n"
          os.read(dependency) shouldBe "one\n"
          os.read(project / ".ohi" / ".gitignore") shouldBe "*\n"
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq(
            s"GET $latestPath",
            "GET /files/packages/test/example/1/",
            "GET /files/packages/test/dependency/1/",
            "GET /files/packages/test/example/1/original.ttl",
            "GET /files/packages/test/dependency/1/source.ttl",
          )

          val original = "# retain this comment\n" + os.read(project / "ohi.yaml")
          os.write.over(project / "ohi.yaml", original)
          requests.clear()
          assertResult(
            successful(runAdd(base, args*)),
            "ADD",
            project,
            false,
            true,
            Seq(
              (base + "/files/packages/test/example/1/original.ttl", direct, "VERIFIED"),
              (base + "/files/packages/test/dependency/1/source.ttl", dependency, "VERIFIED"),
            ),
          )
          os.read(project / "ohi.yaml") shouldBe original
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq(
            s"GET $latestPath",
            "GET /files/packages/test/example/1/",
            "GET /files/packages/test/dependency/1/",
          )
        }
      }
    }

    "use the selected project's default while installing into its configured directory" in {
      withDirectory { project =>
        val original =
          emptyDeclaration.replace("default_format: linkml-yaml", "default_format: owl-turtle")
            .replace("download_dir: .ohi", "download_dir: ontologies")
        os.write(project / "ohi.yaml", original)
        withServer(base =>
          Map(
            latestPath -> (200 -> searchResponse(base + "/files/packages/test/example/1/")),
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/source"),
              format = "owl-turtle",
            )),
            "/files/packages/test/example/1/source" -> (200 -> "one\n"),
          ),
        ) { (base, requests) =>
          val output = json(
            successful(runAdd(base, "test/example", "--project", project.toString, "--json")),
          )
          output("files")(0)(
            "path",
          ).str shouldBe s"$project/ontologies/test/example/1/example.owl.ttl"
          output("files")(0)("status").str shouldBe "DOWNLOADED"
          os.read(
            project / "ontologies" / "test" / "example" / "1" / "example.owl.ttl",
          ) shouldBe "one\n"
          val saved = declaration(project / "ohi.yaml")
          saved("default_format").str shouldBe "owl-turtle"
          saved("download_dir").str shouldBe "ontologies"
          saved("dependencies")(0)("format").str shouldBe "owl-turtle"
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq(
            s"GET $latestPath",
            "GET /files/packages/test/example/1/",
            "GET /files/packages/test/example/1/source",
          )
        }
      }
    }

    "fall back to LinkML when the preferred format is unavailable while installing into the configured directory" in {
      withDirectory { project =>
        val original =
          emptyDeclaration.replace("default_format: linkml-yaml", "default_format: owl-turtle")
            .replace("download_dir: .ohi", "download_dir: ontologies")
        os.write(project / "ohi.yaml", original)
        withServer(base =>
          Map(
            latestPath -> (200 -> searchResponse(base + "/files/packages/test/example/1/")),
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/source"),
              format = "linkml-yaml",
            )),
            "/files/packages/test/example/1/source" -> (200 -> "one\n"),
          ),
        ) { (base, requests) =>
          val output = json(
            successful(runAdd(base, "test/example", "--project", project.toString, "--json")),
          )
          output("files")(0)(
            "path",
          ).str shouldBe s"$project/ontologies/test/example/1/example.linkml.yaml"
          output("files")(0)("status").str shouldBe "DOWNLOADED"
          os.read(
            project / "ontologies" / "test" / "example" / "1" / "example.linkml.yaml",
          ) shouldBe "one\n"
          val saved = declaration(project / "ohi.yaml")
          saved("default_format").str shouldBe "owl-turtle"
          saved("download_dir").str shouldBe "ontologies"
          saved("dependencies")(0)("format").str shouldBe "linkml-yaml"
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq(
            s"GET $latestPath",
            "GET /files/packages/test/example/1/",
            "GET /files/packages/test/example/1/source",
          )
        }
      }
    }

    "sync an edited declaration into the new configured directory and retain old files" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        val old = project / ".ohi" / "test" / "obsolete" / "1" / "obsolete.owl.ttl"
        os.write(old, "retained", createFolders = true)
        os.write(
          project / "ohi.lock.json",
          """{
          "format_version":2,
          "selections":[{"base_url":"https://hub.example/files/packages/test/obsolete/1/","url":"https://hub.example/files/packages/test/obsolete/1/source.ttl"}],
          "files":[{"base_url":"https://hub.example/files/packages/test/obsolete/1/","url":"https://hub.example/files/packages/test/obsolete/1/source.ttl","format":"owl-turtle","sha256":"ca67bd59cc4116ac80db5f64cbf4747f26ba929c85e8cc689a6123368d08719b"}]
        }""",
        )
        val edited = """# edited by the user
          |format_version: 1
          |default_format: linkml-yaml
          |download_dir: ontologies
          |dependencies:
          |  - ontology: test/example@1
          |    format: owl-turtle
          |""".stripMargin
        os.write.over(project / "ohi.yaml", edited)
        withServer(base =>
          Map(
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/source.ttl"),
            )),
            "/files/packages/test/example/1/source.ttl" -> (200 -> "one\n"),
          ),
        ) { (base, requests) =>
          assertResult(
            successful(runSync(base, project)),
            "SYNC",
            project,
            false,
            true,
            Seq(
              (
                base + "/files/packages/test/example/1/source.ttl",
                project / "ontologies" / "test" / "example" / "1" / "example.owl.ttl",
                "DOWNLOADED",
              ),
            ),
          )
          os.read(project / "ohi.yaml") shouldBe edited
          os.read(old) shouldBe "retained"
          os.read(
            project / "ontologies" / "test" / "example" / "1" / "example.owl.ttl",
          ) shouldBe "one\n"
          val lock = json(os.read(project / "ohi.lock.json"))
          lock("format_version").num shouldBe 2
          lock("selections") shouldBe ujson.read(
            s"""[{"base_url":"$base/files/packages/test/example/1/","url":"$base/files/packages/test/example/1/source.ttl"}]""",
          )
          lock("files").arr.map(_("url").str).toSeq shouldBe Seq(
            base + "/files/packages/test/example/1/source.ttl",
          )
          requests.asScala.toSeq shouldBe Seq(
            "GET /files/packages/test/example/1/",
            "GET /files/packages/test/example/1/source.ttl",
          )
        }
      }
    }

    "resolve a declaration into a revision-2 lock without requesting artifacts or creating its directory" in {
      withDirectory { project =>
        val original = emptyDeclaration.replace(
          "dependencies: []",
          "dependencies:\n  - ontology: test/example@1\n    format: owl-turtle",
        )
        os.write(project / "ohi.yaml", original)
        os.write(project / "ohi.lock.json", """{"format_version":1,"selections":[],"files":[]}""")
        withServer(base =>
          Map(
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/source.ttl"),
            )),
          ),
        ) { (base, requests) =>
          assertResult(successful(runLock(base, project)), "LOCK", project, false, true)
          json(os.read(project / "ohi.lock.json")) shouldBe json(s"""{
            "format_version":2,
            "selections":[{"base_url":"$base/files/packages/test/example/1/","url":"$base/files/packages/test/example/1/source.ttl"}],
            "files":[{"base_url":"$base/files/packages/test/example/1/","url":"$base/files/packages/test/example/1/source.ttl", "format":"owl-turtle",
              "sha256":"2c8b08da5ce60398e1f19af0e5dccc744df274b826abe585eaba68c525434806"}]
          }""")
          os.read(project / "ohi.yaml") shouldBe original
          os.exists(project / ".ohi") shouldBe false
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq(
            "GET /files/packages/test/example/1/",
          )
        }
      }
    }

    "initialize the default .ohi setting without creating a download directory" in {
      withDirectory { project =>
        assertResult(
          successful(run(Init, "init", project.toString, "--json")),
          "INIT",
          project,
          true,
          false,
          declarationCreated = true,
        )
        declaration(project / "ohi.yaml") shouldBe json("""{
          "format_version":1,"default_format":"linkml-yaml","download_dir":".ohi","dependencies":[]}
        """)
        os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
      }
    }

    "initialize a custom project-relative directory and preserve it when init is repeated" in {
      withDirectory { root =>
        val cwd = root / "invocation"
        val project = root / "project"
        os.makeDir(cwd)
        assertResult(
          successful(
            runProcess(
              cwd,
              "init",
              "../project",
              "--to",
              "ontologies",
              "--default_format",
              "owl-turtle",
              "--json",
            ),
          ),
          "INIT",
          project,
          true,
          false,
          declarationCreated = true,
        )
        declaration(project / "ohi.yaml") shouldBe json("""{
          "format_version":1,"default_format":"owl-turtle","download_dir":"ontologies","dependencies":[]}
        """)
        val original = os.read(project / "ohi.yaml")
        os.write(project / "ohi.lock.json", "malformed lock sentinel")
        assertResult(
          successful(
            run(
              Init,
              "init",
              project.toString,
              "--to",
              "different",
              "--default_format",
              "linkml-yaml",
              "--json",
            ),
          ),
          "INIT",
          project,
          false,
          false,
        )
        os.read(project / "ohi.yaml") shouldBe original
        os.read(project / "ohi.lock.json") shouldBe "malformed lock sentinel"
        os.list(project).map(_.last).toSet shouldBe Set("ohi.yaml", "ohi.lock.json")
      }
    }

    "report project creation once with its download directory and concise acquisition counts" in {
      withDirectory { project =>
        withServer(base =>
          Map(
            latestPath -> (200 -> searchResponse(base + "/files/packages/test/example/1/")),
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/source.ttl"),
            )),
            "/files/packages/test/example/1/source.ttl" -> (200 -> "one\n"),
          ),
        ) { (base, _) =>
          val args = Seq("test/example", "--format", "owl-turtle", "--project", project.toString)
          val first = successful(runAdd(base, args*))
          first should include("Created")
          first should include("https://ohi.dev/docs/guides/projects/")
          first should include("Added test/example@1 (owl-turtle)")
          first should include("Downloaded 1 ontology file")
          first.sliding(".ohi".length).count(_ == ".ohi") shouldBe 1
          first should not include "Saved"
          val repeated = successful(runAdd(base, args*))
          repeated should not include "Created"
          repeated should include("Already exists: test/example@1 (owl-turtle)")
          repeated should not include "Added"
          repeated should include("Verified 1 existing ontology file")
          repeated should not include "Downloaded"
        }
      }
    }

    "reject an unsupported init format before creating the project directory" in {
      withDirectory { root =>
        val project = root / "missing"
        usageError(
          Init,
          "init",
          project.toString,
          "--default_format",
          "unknown",
          "--json",
        ) should include("format")
        os.exists(project) shouldBe false
      }
    }

    "reject an escaping init download directory without creating a declaration" in {
      withDirectory { project =>
        unchangedError(
          failed(run(Init, "init", project.toString, "--to", "../outside", "--json")),
          "INIT",
          project,
          "INVALID_DECLARATION",
        )
        os.list(project) shouldBe empty
      }
    }

    "reject a selections-only declaration before searching even when add supplies an explicit format" in {
      withDirectory { project =>
        val invalid = "format_version: 1\nselections: []\n"
        os.write(project / "ohi.yaml", invalid)
        withServer(_ => Map.empty) { (base, requests) =>
          unchangedError(
            failed(
              runAdd(
                base,
                "test/example",
                "--format",
                "owl-turtle",
                "--project",
                project.toString,
                "--json",
              ),
            ),
            "ADD",
            project,
            "INVALID_DECLARATION",
          )
          requests shouldBe empty
          os.read(project / "ohi.yaml") shouldBe invalid
          os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
        }
      }
    }

    "keep explicit add formats strict and preserve project records on refusal" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        withServer(base =>
          Map(
            latestPath -> (200 -> searchResponse(base + "/files/packages/test/example/1/")),
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/source.ttl"),
            )),
          ),
        ) { (base, requests) =>
          val error = unchangedError(
            failed(
              runAdd(
                base,
                "test/example",
                "--format",
                "ossie-yaml",
                "--project",
                project.toString,
                "--json",
              ),
            ),
            "ADD",
            project,
            "INVALID_SELECTION",
          )
          error("message").str should include("ossie-yaml")
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq(
            s"GET $latestPath",
            "GET /files/packages/test/example/1/",
          )
        }
      }
    }

    "refuse lock when ohi.yaml is missing" in {
      withDirectory { project =>
        unchangedError(
          failed(run(Lock, "lock", "--project", project.toString, "--json")),
          "LOCK",
          project,
          "INVALID_DECLARATION",
        )
        os.list(project) shouldBe empty
      }
    }

    "refuse sync with neither record and identify the project creation commands" in {
      withDirectory { project =>
        val error = unchangedError(
          failed(run(Sync, "sync", "--project", project.toString, "--json")),
          "SYNC",
          project,
          "INVALID_PROJECT",
        )
        error("message").str should include("ohi init")
        error("message").str should include("ohi add")
        os.list(project) shouldBe empty
      }
    }

    "refuse sync --init with neither record without creating an empty declaration" in {
      withDirectory { project =>
        val error = unchangedError(
          failed(run(Sync, "sync", "--init", "--project", project.toString, "--json")),
          "SYNC",
          project,
          "INVALID_PROJECT",
        )
        error("message").str should include("ohi init")
        error("message").str should include("ohi add")
        os.list(project) shouldBe empty
      }
    }

    "refuse sync of a version-1 lock and report the explicit lock rebuild command" in {
      withDirectory { project =>
        val supplied = """{"format_version":1,"selections":[],"files":[]}"""
        os.write(project / "ohi.yaml", emptyDeclaration)
        os.write(project / "ohi.lock.json", supplied)
        withServer(_ => Map.empty) { (base, requests) =>
          val error = unchangedError(
            failed(runSync(base, project)),
            "SYNC",
            project,
            "OPERATION_FAILED",
            Some("lock_error"),
          )
          error("lock_error")("code").str shouldBe "UNSUPPORTED_VERSION"
          error("lock_error")("operation").str shouldBe "READ"
          error("lock_error")("path").str shouldBe (project / "ohi.lock.json").toString
          error("lock_error")("message").str should include("format version 2")
          error("lock_error")("message").str should include("ohi lock --project")
          requests shouldBe empty
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.read(project / "ohi.lock.json") shouldBe supplied
          os.exists(project / ".ohi") shouldBe false
        }
      }
    }

    "refuse a malformed sync lock before requests or declaration creation" in {
      withDirectory { project =>
        os.write(project / "ohi.lock.json", "invalid lock sentinel")
        withServer(_ => Map.empty) { (base, requests) =>
          val error = unchangedError(
            failed(runSync(base, project, "--init")),
            "SYNC",
            project,
            "OPERATION_FAILED",
            Some("lock_error"),
          )
          error("lock_error")("code").str shouldBe "INVALID_LOCK"
          error("lock_error")("operation").str shouldBe "READ"
          error("lock_error")("path").str shouldBe (project / "ohi.lock.json").toString
          requests shouldBe empty
          os.read(project / "ohi.lock.json") shouldBe "invalid lock sentinel"
          os.list(project).map(_.last) shouldBe Seq("ohi.lock.json")
        }
      }
    }

    "refuse init when the existing declaration is invalid and preserve both records" in {
      withDirectory { project =>
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
        val project = root / "missing"
        unchangedError(
          failed(run(Lock, "lock", "--project", project.toString, "--json")),
          "LOCK",
          project,
          "INVALID_PROJECT",
        )
        os.exists(project) shouldBe false
      }
    }

    "refuse an orphan lock before any request" in {
      withDirectory { project =>
        os.write(project / "ohi.lock.json", """{"format_version":1,"selections":[],"files":[]}""")
        withServer(_ => Map.empty) { (base, requests) =>
          unchangedError(
            failed(runAdd(base, "test/example", "--project", project.toString, "--json")),
            "ADD",
            project,
            "INVALID_PROJECT",
          )
          requests shouldBe empty
          os.list(project).map(_.last) shouldBe Seq("ohi.lock.json")
        }
      }
    }

    "preserve a malformed lock as the sole nested error before requests" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        os.write(project / "ohi.lock.json", "invalid lock sentinel")
        withServer(_ => Map.empty) { (base, requests) =>
          val error = unchangedError(
            failed(runLock(base, project)),
            "LOCK",
            project,
            "OPERATION_FAILED",
            Some("lock_error"),
          )
          error("lock_error")("code").str shouldBe "INVALID_LOCK"
          error("lock_error")("operation").str shouldBe "READ"
          error("lock_error")("path").str shouldBe (project / "ohi.lock.json").toString
          requests shouldBe empty
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.read(project / "ohi.lock.json") shouldBe "invalid lock sentinel"
        }
      }
    }

    "refuse an unrepresentable URL addition before writes and point to standalone get" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        withServer(base =>
          Map(
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(base + "/files/packages/test/example/1/source.ttl"),
              name = "bad/name",
            )),
          ),
        ) { (base, requests) =>
          val error = unchangedError(
            failed(
              runAdd(
                base,
                base + "/files/packages/test/example/1/",
                "--artifact",
                base + "/files/packages/test/example/1/source.ttl",
                "--project",
                project.toString,
                "--json",
              ),
            ),
            "ADD",
            project,
            "INVALID_SELECTION",
          )
          error("message").str should include("ohi get")
          requests.asScala.toSeq.map(_.takeWhile(_ != '?')) shouldBe Seq(
            "GET /files/packages/test/example/1/",
          )
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
        }
      }
    }

    "refuse a format-ambiguous URL addition before writes and point to standalone get" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        withServer(base =>
          Map(
            "/files/packages/test/example/1/" -> (200 -> publication(
              base + "/files/packages/test/example/1/",
              Seq(
                base + "/files/packages/test/example/1/source.ttl",
                base + "/files/packages/test/example/1/alternate.ttl",
              ),
              name = "example",
            )),
          ),
        ) { (base, requests) =>
          val error = unchangedError(
            failed(
              runAdd(
                base,
                base + "/files/packages/test/example/1/",
                "--artifact",
                base + "/files/packages/test/example/1/source.ttl",
                "--project",
                project.toString,
                "--json",
              ),
            ),
            "ADD",
            project,
            "INVALID_SELECTION",
          )
          error("message").str should include("ohi get")
          // The URL, then the reference test/example@1, which resolves to the same release path.
          requests.asScala.toSeq shouldBe Seq(
            "GET /files/packages/test/example/1/",
            "GET /files/packages/test/example/1/",
          )
          os.read(project / "ohi.yaml") shouldBe emptyDeclaration
          os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
        }
      }
    }

    "preserve nested server JSON and original invocation recovery without completed writes" in {
      withDirectory { project =>
        os.write(project / "ohi.yaml", emptyDeclaration)
        val response =
          """{"error":{"reasons":["absent",{"retry":false},null]},"status":404,"ratio":1.25}"""
        withServer(_ => Map("/files/packages/test/example/1/" -> (404 -> response))) {
          (base, requests) =>
            val error = unchangedError(
              failed(
                run(
                  Add,
                  addArguments(
                    base + "/files/packages/test/example/1/",
                    base + "/files/packages/test/example/1/source.ttl",
                    project.toString,
                  )*,
                ),
              ),
              "ADD",
              project,
              "OPERATION_FAILED",
              Some("catalog_error"),
            )
            val cause = error("catalog_error")
            keys(cause) shouldBe Set(
              "code",
              "message",
              "operation",
              "url",
              "status",
              "response_json",
            )
            cause("code").str shouldBe "HTTP_ERROR"
            cause("operation").str shouldBe "INSPECT"
            cause("url").str shouldBe base + "/files/packages/test/example/1/"
            cause("status").num shouldBe 404
            cause("response_json") shouldBe json(response)
            requests.asScala.toSeq shouldBe Seq("GET /files/packages/test/example/1/")
            os.read(project / "ohi.yaml") shouldBe emptyDeclaration
            os.list(project).map(_.last) shouldBe Seq("ohi.yaml")
        }
      }
    }
  }

  "project command options" should {
    for (name, command) <- Seq("add" -> Add, "sync" -> Sync) do
      s"reject --to for $name because the project declaration selects the root" in {
        usageError(command, name, "--to", "downloads") should include("--to")
      }

    for option <- Seq("--artifact", "--format") do
      s"reject $option for sync before reading project records" in {
        usageError(Sync, "sync", option, "value") should include(option)
      }

    "reject positional sync input and identify the project option" in {
      usageError(Sync, "sync", "unexpected") should include(
        "Select the project directory with --project.",
      )
    }

    "show add help with its supported project options" in {
      val (raw, err, code) = run(Add, "add", "--help")
      code shouldBe 0
      err shouldBe empty
      val out = raw.replaceAll("\u001b\\[[0-9;]*m", "")
      out should include("Usage\n  ohi add")
      Seq("--artifact", "--format", "--project", "--json").foreach(out should include(_))
      out should not include "--timeout-ms"
      out should not include "--to"
      out should include("[--format <format>]")
      out should include("default_format from ohi.yaml, then linkml-yaml")
    }

    "reject add without --artifact and point to its help" in {
      usageError(Add, "add", "https://example.org/source", "--json") should include(
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
