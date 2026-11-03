package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.projecterror.{ProjectErrorCode, ProjectOperation}
import eu.neverblink.ohi.cli.generated.fileerror.CliFileErrorCode
import eu.neverblink.ohi.model.{LockException, LockFiles}
import eu.neverblink.ohi.model.generated.{Format, Publication}
import eu.neverblink.ohi.model.generated.search.{SearchHit, SearchResult}
import eu.neverblink.ohi.model.generated.searchrequest.SearchRequest
import eu.neverblink.linkml.runtime.{LinkmlDateTime, PlainText, Uri}
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.ohi.model.generated.lockerror.{LockErrorCode, LockError, LockOperation}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{FilterOutputStream, IOException}
import java.nio.file.Files
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.StandardOpenOption.{CREATE_NEW, WRITE}
import scala.collection.mutable.ArrayBuffer

class ProjectSpec extends AnyWordSpec, Matchers {
  private val emptyDeclaration =
    "# preserve this comment\nformat_version: 1\ndefault_format: linkml-yaml\ndownload_dir: .ohi\ndependencies: []\n"
  private val selectedDeclaration = emptyDeclaration.replace(
    "dependencies: []",
    "dependencies:\n  - ontology: test/example@1\n    format: owl-turtle",
  )
  private val oldLock = "{\"format_version\":1,\"selections\":[],\"files\":[]}"
  private val base = "https://example.org/files/packages/test/example/1/"
  private val url = base + "file.ttl"
  private val dependencyBase = "https://example.org/files/packages/test/dep/2/"
  private val dependencyUrl = dependencyBase + "source.ttl"
  private val sha = "a12871fee210fb8619291eaea194581cbd2531e4b23759d225f6806923f63222"
  private val bytes = Array[Byte](1, 2)
  private val emptyLock = "{\"format_version\":2,\"selections\":[],\"files\":[]}\n"
  private val currentLock = s"""{
    "format_version":2,
    "selections":[{"base_url":"$base","url":"$url"}],
    "files":[{"base_url":"$base","url":"$url","format":"owl-turtle","sha256":"$sha"}]
  }
  """
  private val graphLock = s"""{
    "format_version":2,
    "selections":[{"base_url":"$base","url":"$url"}],
    "files":[
      {"base_url":"$base","url":"$url","format":"owl-turtle","sha256":"$sha"},
      {"base_url":"$dependencyBase","url":"$dependencyUrl","format":"owl-turtle","sha256":"$sha"}
    ]
  }"""
  private val adoptionLock = s"""{
    "format_version":2,
    "selections":[{"base_url":"$base","url":"$url"},{"base_url":"$dependencyBase","url":"$dependencyUrl"}],
    "files":[
      {"base_url":"$dependencyBase","url":"$dependencyUrl","format":"owl-rdfxml","sha256":"$sha"},
      {"base_url":"https://example.org/files/packages/test/leaf/3/","url":"https://example.org/files/packages/test/leaf/3/file.yaml","format":"linkml-yaml","sha256":"$sha"},
      {"base_url":"$base","url":"$url","format":"owl-turtle","sha256":"$sha"}
    ]
  }"""
  private val metadata =
    ProjectTestData.publication(base, Seq(url -> Format.OwlTurtle), sha256 = sha)
  private val graph = Map(
    base -> metadata.copy(dependencies =
      Seq(eu.neverblink.ohi.model.generated.Dependency(Uri(dependencyBase))),
    ),
    dependencyBase -> ProjectTestData.publication(
      dependencyBase,
      Seq(dependencyUrl -> Format.OwlTurtle),
      sha256 = sha,
    ),
  )
  private val retry = "ohi add test/example@1 --format owl-turtle --project 'selected project'"

  private def withDirectory(test: os.Path => Unit): Unit = {
    val directory = os.temp.dir(prefix = "ohi project ")
    try test(directory)
    finally os.remove.all(directory)
  }

  private val search: SearchRequest => SearchResult = _ =>
    SearchResult(
      hits = Seq(
        SearchHit(
          baseUrl = Uri(base),
          description = "Synthetic project fixture",
          formats = Seq.empty,
          licenses = Seq.empty,
          name = "example",
          namespace = "test",
          publishedAt = LinkmlDateTime("2026-10-15T00:00:00Z"),
          title = "Example",
          version = "1",
        ),
      ),
      page = 1,
      perPage = 250,
      searchCutoff = false,
      total = 1,
    )

  private def noSearch(request: SearchRequest): SearchResult = fail(s"Unexpected search: $request")
  private def noInspect(url: String): Publication = fail(s"Unexpected inspection: $url")
  private def noFetch(url: String): Array[Byte] = fail(s"Unexpected retrieval: $url")

  private def error(
      project: Project,
      cause: Throwable,
      declaration: Boolean,
      lock: Boolean,
      created: Boolean = false,
      invocation: String = retry,
  ) = {
    val data = ujson.read(ProjectOutput.error(project.failure(cause, invocation), json = true))
    data("project_directory").str shouldBe project.directory.toString
    data("declaration_changed").bool shouldBe declaration
    data("declaration_created").bool shouldBe created
    data("lock_changed").bool shouldBe lock
    data
  }

  "project write coordination" should {
    "report failed init with no completed writes after removing its partial declaration" in {
      withDirectory { directory =>
        val target = directory / "nested" / "ohi.yaml"
        val primary = new IOException("injected write failure")
        val project = new Project(
          target.toNIO.getParent,
          ProjectOperation.Init,
          createDeclaration = (path, declaration) =>
            DeclarationFiles.create(
              path,
              declaration,
              path =>
                new FilterOutputStream(Files.newOutputStream(path, CREATE_NEW, WRITE)) {
                  override def write(bytes: Array[Byte]): Unit = {
                    out.write(bytes, 0, 1)
                    throw primary
                  }
                },
              path => Files.delete(path),
            ),
        )
        val failure = intercept[ProjectException](
          project.init(
            createParents = true,
            defaultFormat = generated.declaration.Format.OwlTurtle,
            downloadDir = "downloads",
          ),
        )
        failure.getCause shouldBe primary
        val invocation = "ohi init nested --default_format owl-turtle --to downloads"
        val data =
          error(project, failure, declaration = false, lock = false, invocation = invocation)
        data("operation").str shouldBe "INIT"
        data("code").str shouldBe "STORAGE_ERROR"
        data("files").arr shouldBe empty
        project.recoveryCommand(failure, invocation) shouldBe invocation
        data("message").str should include(invocation)
        os.exists(target) shouldBe false
        os.exists(directory / "nested") shouldBe true
        os.exists(directory / "nested" / "ohi.lock.json") shouldBe false
        os.exists(directory / "nested" / "downloads") shouldBe false
      }
    }

    "stop before lock and installation when declaration replacement fails" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", emptyDeclaration)
        os.write(directory / "ohi.lock.json", oldLock)
        val project = new Project(
          directory.toNIO,
          ProjectOperation.Add,
          writeDeclaration = (_, _) =>
            throw new ProjectException(
              ProjectErrorCode.StorageError,
              "injected replacement failure",
            ),
          writeLock = (_, _) => fail("Unexpected lock write"),
        )
        val requests = ArrayBuffer.empty[String]
        val failure = intercept[ProjectException] {
          project.add(
            base,
            Some(url),
            None,
            search,
            value => { requests += value; metadata },
            noFetch,
          )
        }
        val data = error(project, failure, declaration = false, lock = false)
        data("code").str shouldBe "STORAGE_ERROR"
        data("message").str should include(retry)
        data("files").arr shouldBe empty
        requests.toSeq shouldBe Seq(base)
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        os.read(directory / "ohi.lock.json") shouldBe oldLock
        os.exists(directory / ".ohi") shouldBe false
      }
    }

    "preserve a newly created declaration and retry the original add after a lock failure" in {
      withDirectory { directory =>
        val owned = LockError(
          code = LockErrorCode.StorageError,
          message = "injected lock replacement failure",
          operation = LockOperation.Write,
          path = Some((directory / "ohi.lock.json").toString),
        )
        val project = new Project(
          directory.toNIO,
          ProjectOperation.Add,
          writeLock = (_, _) => throw new LockException(owned),
        )
        val failure = intercept[LockException] {
          project.add(base, Some(url), None, search, _ => metadata, noFetch)
        }
        val data = error(project, failure, declaration = true, lock = false, created = true)
        data("code").str shouldBe "OPERATION_FAILED"
        data("lock_error")("operation").str shouldBe "WRITE"
        data("lock_error")("path").str shouldBe (directory / "ohi.lock.json").toString
        data.obj.contains("catalog_error") shouldBe false
        data.obj.contains("file_error") shouldBe false
        data("files").arr shouldBe empty
        project.recoveryCommand(failure, retry) shouldBe retry
        data("message").str should include(retry)
        os.exists(directory / "ohi.lock.json") shouldBe false
        os.exists(directory / ".ohi") shouldBe false
        val human = ProjectOutput.error(project.failure(failure, retry), json = false)
        human should include("Created an ohi project in ")
        human should include("https://ohi.dev/docs/guides/projects/")
        val savedDeclaration = os.read(directory / "ohi.yaml")
        val resumedRequests = ArrayBuffer.empty[String]
        val resumed = new Project(directory.toNIO, ProjectOperation.Add)
          .add(
            base,
            Some(url),
            None,
            search,
            value => { resumedRequests += value; metadata },
            value => { resumedRequests += value; bytes },
          )
        resumedRequests.toSeq shouldBe Seq(base, url)
        resumed.declarationChanged shouldBe false
        os.read(directory / "ohi.yaml") shouldBe savedDeclaration
        ujson.read(os.read(directory / "ohi.lock.json"))("selections") shouldBe
          ujson.read(s"""[{"base_url":"$base","url":"$url"}]""")
        os.read.bytes(
          directory / ".ohi" / "test" / "example" / "1" / "example.owl.ttl",
        ).toSeq shouldBe bytes.toSeq
        os.read(directory / ".ohi" / ".gitignore") shouldBe "*\n"
      }
    }

    "preserve the previous lock and retry the original add after a lock failure" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", emptyDeclaration)
        os.write(directory / "ohi.lock.json", oldLock)
        val owned = LockError(
          code = LockErrorCode.StorageError,
          message = "injected lock replacement failure",
          operation = LockOperation.Write,
          path = Some((directory / "ohi.lock.json").toString),
        )
        val project = new Project(
          directory.toNIO,
          ProjectOperation.Add,
          writeLock = (_, _) => throw new LockException(owned),
        )
        val failure = intercept[LockException] {
          project.add(base, Some(url), None, search, _ => metadata, noFetch)
        }
        val data = error(project, failure, declaration = true, lock = false, created = false)
        data("code").str shouldBe "OPERATION_FAILED"
        data("lock_error")("operation").str shouldBe "WRITE"
        data("lock_error")("path").str shouldBe (directory / "ohi.lock.json").toString
        data.obj.contains("catalog_error") shouldBe false
        data.obj.contains("file_error") shouldBe false
        data("files").arr shouldBe empty
        project.recoveryCommand(failure, retry) shouldBe retry
        data("message").str should include(retry)
        os.read(directory / "ohi.lock.json") shouldBe oldLock
        os.exists(directory / ".ohi") shouldBe false
        val human = ProjectOutput.error(project.failure(failure, retry), json = false)
        human should not include "Created an ohi project"
        val savedDeclaration = os.read(directory / "ohi.yaml")
        val resumedRequests = ArrayBuffer.empty[String]
        val resumed = new Project(directory.toNIO, ProjectOperation.Add)
          .add(
            base,
            Some(url),
            None,
            search,
            value => { resumedRequests += value; metadata },
            value => { resumedRequests += value; bytes },
          )
        resumedRequests.toSeq shouldBe Seq(base, url)
        resumed.declarationChanged shouldBe false
        os.read(directory / "ohi.yaml") shouldBe savedDeclaration
        ujson.read(os.read(directory / "ohi.lock.json"))("selections") shouldBe
          ujson.read(s"""[{"base_url":"$base","url":"$url"}]""")
        os.read.bytes(
          directory / ".ohi" / "test" / "example" / "1" / "example.owl.ttl",
        ).toSeq shouldBe bytes.toSeq
        os.read(directory / ".ohi" / ".gitignore") shouldBe "*\n"
      }
    }

    "keep saved records and a competing artifact after installation fails" in {
      withDirectory { directory =>
        val root = directory / ".ohi"
        val target = root / "test" / "example" / "1" / "example.owl.ttl"
        val project = new Project(directory.toNIO, ProjectOperation.Add)
        val failure = intercept[LockDownload.InstallationException] {
          project.add(
            base,
            Some(url),
            None,
            search,
            _ => metadata,
            _ => {
              os.makeDir.all(target / os.up)
              os.write(target, "competing file")
              bytes
            },
          )
        }
        val data = error(project, failure, declaration = true, lock = true, created = true)
        data("code").str shouldBe "OPERATION_FAILED"
        data("file_error")("code").str shouldBe "DESTINATION_EXISTS"
        data("file_error")("path").str shouldBe target.toString
        data("file_error")("directory").str shouldBe root.toString
        data("file_error")("url").str shouldBe url
        data("files").arr shouldBe empty
        data.obj.contains("lock_error") shouldBe false
        data.obj.contains("catalog_error") shouldBe false
        project.recoveryCommand(failure, retry) shouldBe
          s"ohi sync --project '$directory'"
        ujson.read(os.read(directory / "ohi.lock.json")) shouldBe ujson.read(
          s"""{"format_version":2,"selections":[{"base_url":"$base","url":"$url"}],"files":[{"base_url":"$base","url":"$url","format":"owl-turtle","sha256":"$sha"}]}""",
        )
        os.read(directory / "ohi.yaml") should include("download_dir: .ohi")
        os.read(directory / "ohi.yaml") should include("ontology: test/example@1")
        os.read(target) shouldBe "competing file"
        os.read(root / ".gitignore") shouldBe "*\n"
      }
    }

    "retry add after an ignore write failure even though the complete lock was saved" in {
      withDirectory { directory =>
        val project = new Project(
          directory.toNIO,
          ProjectOperation.Add,
          writeLock = (path, value) => {
            LockFiles.write(path, value)
            os.makeDir.all(directory / ".ohi" / ".gitignore")
          },
        )
        val failure = intercept[CliFileException] {
          project.add(base, Some(url), None, search, _ => metadata, noFetch)
        }
        val data = error(project, failure, declaration = true, lock = true, created = true)
        data("file_error")("code").str shouldBe "DESTINATION_EXISTS"
        data("files").arr shouldBe empty
        project.recoveryCommand(failure, retry) shouldBe retry
        data("message").str should include(retry)
        os.exists(directory / "ohi.yaml") shouldBe true
        os.exists(directory / "ohi.lock.json") shouldBe true
        os.exists(directory / ".ohi" / "test") shouldBe false
        val savedDeclaration = os.read(directory / "ohi.yaml")
        os.remove(directory / ".ohi" / ".gitignore")
        val requests = ArrayBuffer.empty[String]
        val resumed = new Project(directory.toNIO, ProjectOperation.Add)
          .add(
            base,
            Some(url),
            None,
            search,
            value => { requests += value; metadata },
            value => { requests += value; bytes },
          )
        requests.toSeq shouldBe Seq(base, url)
        resumed.declarationChanged shouldBe false
        resumed.files.map(_.status.toString) shouldBe Seq("Downloaded")
        os.read(directory / "ohi.yaml") shouldBe savedDeclaration
        os.read(directory / ".ohi" / ".gitignore") shouldBe "*\n"
        os.read.bytes(
          directory / ".ohi" / "test" / "example" / "1" / "example.owl.ttl",
        ).toSeq shouldBe bytes.toSeq
      }
    }

    "retain completed outputs and their original cause, then reuse them on repeated add" in {
      withDirectory { directory =>
        val root = directory / ".ohi"
        val project = new Project(directory.toNIO, ProjectOperation.Add)
        val owned = FileDelivery.failure(
          CliFileErrorCode.FileWriteFailed,
          "injected retrieval failure",
          dependencyUrl,
          root.toString,
        )
        val failure = intercept[LockDownload.InstallationException] {
          project.add(
            base,
            Some(url),
            None,
            search,
            graph,
            requested => if requested == url then bytes else throw owned,
          )
        }
        failure.cause shouldBe owned
        val data = error(project, failure, declaration = true, lock = true, created = true)
        data("file_error")("url").str shouldBe dependencyUrl
        data("file_error")("message").str shouldBe "injected retrieval failure"
        data("files") shouldBe ujson.read(
          s"""[{"path":"${root / "test" / "example" / "1" / "example.owl.ttl"}","status":"DOWNLOADED","url":"$url"}]""",
        )
        ujson.read(os.read(directory / "ohi.lock.json"))("files").arr.map(
          _("url").str,
        ).toSeq shouldBe Seq(url, dependencyUrl)
        val savedDeclaration = os.read(directory / "ohi.yaml")
        val requested = ArrayBuffer.empty[String]
        val result = new Project(directory.toNIO, ProjectOperation.Add)
          .add(base, Some(url), None, search, graph, value => { requested += value; bytes })
        requested.toSeq shouldBe Seq(dependencyUrl)
        result.declarationChanged shouldBe false
        result.files.map(_.status.toString) shouldBe Seq("Verified", "Downloaded")
        os.read(directory / "ohi.yaml") shouldBe savedDeclaration
        os.read.bytes(
          root / "test" / "example" / "1" / "example.owl.ttl",
        ).toSeq shouldBe bytes.toSeq
        os.read.bytes(root / "test" / "dep" / "2" / "dep.owl.ttl").toSeq shouldBe bytes.toSeq
      }
    }

    "preserve declaration and old lock when refresh receives a different publication identity" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", selectedDeclaration)
        os.write(directory / "ohi.lock.json", oldLock)
        val project = new Project(directory.toNIO, ProjectOperation.Lock)
        val requests = ArrayBuffer.empty[String]
        val failure = intercept[ProjectException] {
          project.refresh(
            search,
            requested => {
              requests += requested
              metadata.copy(baseUrl = Uri(base + "/"))
            },
          )
        }
        failure.code shouldBe ProjectErrorCode.InvalidSelection
        failure.getMessage should include("Release metadata does not match ontology reference")
        requests.toSeq shouldBe Seq(base)
        val invocation = "ohi lock --project 'selected project'"
        val data =
          error(project, failure, declaration = false, lock = false, invocation = invocation)
        data("message").str should include(invocation)
        os.read(directory / "ohi.yaml") shouldBe selectedDeclaration
        os.read(directory / "ohi.lock.json") shouldBe oldLock
      }
    }
  }

  "project-root LinkML imports" should {
    "load an installed definition through its literal percent-encoded portable path" in {
      withDirectory { directory =>
        val schema =
          """# published formatting is preserved
            |id: https://example.org/imported
            |name: imported
            |classes:
            |  ImportedThing:
            |    description: Installed definition
            |""".stripMargin
        val schemaBase = "https://example.org/files/packages/test/a%41/1/"
        val schemaUrl = schemaBase + "published.yaml"
        val publication = ProjectTestData.publication(
          schemaBase,
          Seq(schemaUrl -> Format.LinkmlYaml),
          sha256 = "5c828d0c6395ddd93c159992066f6f9822a717402aa9b91fe4d82b77912abc66",
        )
        val result = new Project(directory.toNIO, ProjectOperation.Add).add(
          "test/a%41@1",
          None,
          None,
          request => {
            val response = search(request)
            response.copy(hits =
              response.hits.map(_.copy(baseUrl = Uri(schemaBase), name = "a%41")),
            )
          },
          requested => { requested shouldBe schemaBase; publication },
          requested => { requested shouldBe schemaUrl; schema.getBytes(UTF_8) },
        )
        val installed = directory / ".ohi" / "test" / "a%2541" / "1" / "a%2541.linkml.yaml"
        result.files.map(_.path) shouldBe Seq(installed.toString)
        os.read.bytes(installed).toSeq shouldBe schema.getBytes(UTF_8).toSeq
        os.write(
          directory / "model.yaml",
          """id: https://example.org/local
            |name: local
            |imports:
            |  - .ohi/test/a%2541/1/a%2541.linkml
            |classes:
            |  LocalThing:
            |    is_a: ImportedThing
            |""".stripMargin,
        )
        val view = SchemaIssues.orThrow(
          SchemaView.loadSchemaViewFromUri((directory / "model.yaml").toString),
        )
        view.classes.keySet shouldBe Set("LocalThing", "ImportedThing")
        view.classes("ImportedThing").cls.description shouldBe Some(
          PlainText("Installed definition"),
        )
        view.classes(
          "ImportedThing",
        ).definingSchema.id.original shouldBe "https://example.org/imported"
      }
    }
  }

  "project lock refresh" should {
    "write an empty revision-2 lock without catalog calls or a download directory" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", emptyDeclaration)
        val result =
          new Project(directory.toNIO, ProjectOperation.Lock).refresh(noSearch, noInspect)
        result.declarationChanged shouldBe false
        result.declarationCreated shouldBe false
        result.lockChanged shouldBe true
        result.files shouldBe empty
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        ujson.read(os.read(directory / "ohi.lock.json")) shouldBe ujson.read(
          """{"format_version":2,"selections":[],"files":[]}""",
        )
        os.exists(directory / ".ohi") shouldBe false
      }
    }
  }

  "project sync" should {
    "reuse exact lock bytes after dependency reordering and preference edits without metadata" in {
      withDirectory { directory =>
        val declaration = """# retained user formatting
          |format_version: 1
          |default_format: ossie-yaml
          |download_dir: ontologies
          |dependencies:
          |  - ontology: test/dep@2
          |    format: owl-rdfxml
          |  - ontology: test/example@1
          |    format: owl-turtle
          |""".stripMargin
        os.write(directory / "ohi.yaml", declaration)
        os.write(directory / "ohi.lock.json", adoptionLock)
        val requests = ArrayBuffer.empty[String]
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(noSearch, noInspect, value => { requests += value; bytes })
        result.declarationChanged shouldBe false
        result.declarationCreated shouldBe false
        result.lockChanged shouldBe false
        requests.toSeq shouldBe Seq(
          dependencyUrl,
          "https://example.org/files/packages/test/leaf/3/file.yaml",
          url,
        )
        result.files.map(_.path) shouldBe Seq(
          (directory / "ontologies" / "test" / "dep" / "2" / "dep.owl").toString,
          (directory / "ontologies" / "test" / "leaf" / "3" / "leaf.linkml.yaml").toString,
          (directory / "ontologies" / "test" / "example" / "1" / "example.owl.ttl").toString,
        )
        result.files.map(_.status.toString) shouldBe Seq("Downloaded", "Downloaded", "Downloaded")
        result.files.foreach(file => os.read.bytes(os.Path(file.path)).toSeq shouldBe bytes.toSeq)
        os.read(directory / "ohi.yaml") shouldBe declaration
        os.read(directory / "ohi.lock.json") shouldBe adoptionLock
        os.read(directory / "ontologies" / ".gitignore") shouldBe "*\n"
        os.exists(directory / ".ohi") shouldBe false
      }
    }

    "preserve an existing declaration with --init and verify its current lock without requests" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", selectedDeclaration)
        os.write(directory / "ohi.lock.json", currentLock)
        val target = directory / ".ohi" / "test" / "example" / "1" / "example.owl.ttl"
        os.write(target, bytes, createFolders = true)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(noSearch, noInspect, noFetch, init = true)
        result.declarationChanged shouldBe false
        result.declarationCreated shouldBe false
        result.lockChanged shouldBe false
        result.files.map(_.status.toString) shouldBe Seq("Verified")
        result.files.map(_.path) shouldBe Seq(target.toString)
        os.read(directory / "ohi.yaml") shouldBe selectedDeclaration
        os.read(directory / "ohi.lock.json") shouldBe currentLock
        os.read.bytes(target).toSeq shouldBe bytes.toSeq
      }
    }

    "build a missing lock from YAML without rewriting its bytes" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", selectedDeclaration)
        val requests = ArrayBuffer.empty[String]
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(
            search,
            value => { requests += value; metadata },
            value => { requests += value; bytes },
          )
        result.declarationChanged shouldBe false
        result.declarationCreated shouldBe false
        result.lockChanged shouldBe true
        result.files.map(_.status.toString) shouldBe Seq("Downloaded")
        requests.toSeq shouldBe Seq(base, url)
        os.read(directory / "ohi.yaml") shouldBe selectedDeclaration
        ujson.read(os.read(directory / "ohi.lock.json")) shouldBe ujson.read(currentLock)
      }
    }

    "preserve YAML with --init while creating its missing empty lock" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", emptyDeclaration)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(noSearch, noInspect, noFetch, init = true)
        result.declarationChanged shouldBe false
        result.declarationCreated shouldBe false
        result.lockChanged shouldBe true
        result.files shouldBe empty
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        ujson.read(os.read(directory / "ohi.lock.json")) shouldBe ujson.read(emptyLock)
        os.exists(directory / ".ohi") shouldBe false
      }
    }

    "adopt only direct selections in their supplied order and formats after preflight" in {
      withDirectory { directory =>
        os.write(directory / "ohi.lock.json", adoptionLock)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(noSearch, noInspect, _ => bytes, init = true)
        result.declarationChanged shouldBe true
        result.declarationCreated shouldBe true
        result.lockChanged shouldBe false
        result.files.map(_.status.toString) shouldBe Seq("Downloaded", "Downloaded", "Downloaded")
        os.read(directory / "ohi.yaml") shouldBe
          """# Ontologies used by this project.
            |# Docs: https://ohi.dev/docs/guides/projects/
            |format_version: 1
            |default_format: linkml-yaml
            |download_dir: .ohi
            |dependencies:
            |  - ontology: test/example@1
            |    format: owl-turtle
            |  - ontology: test/dep@2
            |    format: owl-rdfxml
            |""".stripMargin
        os.read(directory / "ohi.lock.json") shouldBe adoptionLock
        os.read(directory / ".ohi" / ".gitignore") shouldBe "*\n"
      }
    }

    "accept an empty lock without creating YAML or a download directory" in {
      withDirectory { directory =>
        os.write(directory / "ohi.lock.json", emptyLock)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(noSearch, noInspect, noFetch)
        result.declarationChanged shouldBe false
        result.declarationCreated shouldBe false
        result.lockChanged shouldBe false
        result.files shouldBe empty
        os.list(directory).map(_.last) shouldBe Seq("ohi.lock.json")
        os.read(directory / "ohi.lock.json") shouldBe emptyLock
      }
    }

    "adopt an empty lock without creating a download directory" in {
      withDirectory { directory =>
        os.write(directory / "ohi.lock.json", emptyLock)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(noSearch, noInspect, noFetch, init = true)
        result.declarationChanged shouldBe true
        result.declarationCreated shouldBe true
        result.lockChanged shouldBe false
        result.files shouldBe empty
        os.read(directory / "ohi.yaml") should endWith(
          "format_version: 1\ndefault_format: linkml-yaml\ndownload_dir: .ohi\ndependencies: []\n",
        )
        os.read(directory / "ohi.lock.json") shouldBe emptyLock
        os.exists(directory / ".ohi") shouldBe false
      }
    }

    "resolve an added requirement instead of reusing an empty lock" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", selectedDeclaration)
        os.write(directory / "ohi.lock.json", emptyLock)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(search, _ => metadata, _ => bytes)
        result.lockChanged shouldBe true
        result.files.map(_.url.original) shouldBe Seq(url)
        os.read(directory / "ohi.yaml") shouldBe selectedDeclaration
        ujson.read(os.read(directory / "ohi.lock.json")) shouldBe ujson.read(currentLock)
      }
    }

    "resolve a changed version while retaining its previous installed file" in {
      withDirectory { directory =>
        val nextBase = "https://example.org/files/packages/test/example/2/"
        val nextUrl = nextBase + "file.ttl"
        val declaration = selectedDeclaration.replace("test/example@1", "test/example@2")
        os.write(directory / "ohi.yaml", declaration)
        os.write(directory / "ohi.lock.json", currentLock)
        val previous = directory / ".ohi" / "test" / "example" / "1" / "example.owl.ttl"
        os.write(previous, "retained", createFolders = true)
        val result = new Project(directory.toNIO, ProjectOperation.Sync).sync(
          request => {
            request.filterBy shouldBe Some("namespace:=test && name:=example && version:=2")
            val response = search(request)
            response.copy(hits = response.hits.map(_.copy(baseUrl = Uri(nextBase), version = "2")))
          },
          requested => {
            requested shouldBe nextBase
            ProjectTestData.publication(nextBase, Seq(nextUrl -> Format.OwlTurtle), sha256 = sha)
          },
          requested => { requested shouldBe nextUrl; bytes },
        )
        result.lockChanged shouldBe true
        result.files.map(_.path) shouldBe Seq(
          (directory / ".ohi" / "test" / "example" / "2" / "example.owl.ttl").toString,
        )
        ujson.read(os.read(directory / "ohi.lock.json"))("selections") shouldBe ujson.read(
          s"""[{"base_url":"$nextBase","url":"$nextUrl"}]""",
        )
        os.read(directory / "ohi.yaml") shouldBe declaration
        os.read(previous) shouldBe "retained"
      }
    }

    "resolve a changed format instead of reusing the original artifact" in {
      withDirectory { directory =>
        val changedUrl = base + "file.owl"
        os.write(directory / "ohi.yaml", selectedDeclaration.replace("owl-turtle", "owl-rdfxml"))
        os.write(directory / "ohi.lock.json", currentLock)
        val result = new Project(directory.toNIO, ProjectOperation.Sync).sync(
          search,
          _ => ProjectTestData.publication(base, Seq(changedUrl -> Format.OwlRdfxml), sha256 = sha),
          requested => { requested shouldBe changedUrl; bytes },
        )
        result.lockChanged shouldBe true
        result.files.map(_.path) shouldBe Seq(
          (directory / ".ohi" / "test" / "example" / "1" / "example.owl").toString,
        )
        val file = ujson.read(os.read(directory / "ohi.lock.json"))("files")(0)
        file("format").str shouldBe "owl-rdfxml"
        file("url").str shouldBe changedUrl
      }
    }

    "remove unneeded lock entries while retaining obsolete installed files" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", emptyDeclaration)
        os.write(directory / "ohi.lock.json", graphLock)
        val previous = directory / ".ohi" / "test" / "example" / "1" / "example.owl.ttl"
        os.write(previous, "retained", createFolders = true)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(noSearch, noInspect, noFetch)
        result.lockChanged shouldBe true
        result.files shouldBe empty
        ujson.read(os.read(directory / "ohi.lock.json")) shouldBe ujson.read(emptyLock)
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        os.read(previous) shouldBe "retained"
      }
    }

    "rebuild when distinct locked selections map to a duplicate declared pair" in {
      withDirectory { directory =>
        val supplied = ujson.read(currentLock)
        supplied("selections").arr += ujson.Obj(
          "base_url" -> base,
          "url" -> (base + "alternate.ttl"),
        )
        supplied("files").arr += ujson.Obj(
          "base_url" -> base,
          "url" -> (base + "alternate.ttl"),
          "format" -> "owl-turtle",
          "sha256" -> sha,
        )
        os.write(directory / "ohi.yaml", selectedDeclaration)
        os.write(directory / "ohi.lock.json", supplied.toString)
        val result = new Project(directory.toNIO, ProjectOperation.Sync)
          .sync(search, _ => metadata, _ => bytes)
        result.lockChanged shouldBe true
        result.files.map(_.url.original) shouldBe Seq(url)
        ujson.read(os.read(directory / "ohi.lock.json")) shouldBe ujson.read(currentLock)
      }
    }

    "refuse malformed YAML before metadata requests or lock writes" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", "dependencies: [")
        os.write(directory / "ohi.lock.json", currentLock)
        val project = new Project(directory.toNIO, ProjectOperation.Sync)
        val failure = intercept[ProjectException](project.sync(noSearch, noInspect, noFetch))
        failure.code shouldBe ProjectErrorCode.InvalidDeclaration
        error(project, failure, declaration = false, lock = false)("files").arr shouldBe empty
        os.read(directory / "ohi.yaml") shouldBe "dependencies: ["
        os.read(directory / "ohi.lock.json") shouldBe currentLock
        os.exists(directory / ".ohi") shouldBe false
      }
    }

    "refuse an unrepresentable direct reference before creating YAML or downloading" in {
      withDirectory { directory =>
        val supplied = currentLock.replace("test/example/1/", "test/example/1+draft/")
        os.write(directory / "ohi.lock.json", supplied)
        val project = new Project(directory.toNIO, ProjectOperation.Sync)
        val failure = intercept[ProjectException](
          project.sync(noSearch, noInspect, noFetch, init = true),
        )
        failure.code shouldBe ProjectErrorCode.InvalidDeclaration
        error(project, failure, declaration = false, lock = false)("files").arr shouldBe empty
        os.read(directory / "ohi.lock.json") shouldBe supplied
        os.list(directory).map(_.last) shouldBe Seq("ohi.lock.json")
      }
    }

    "keep a lock unchanged and retry initialization after declaration creation fails" in {
      withDirectory { directory =>
        os.write(directory / "ohi.lock.json", currentLock)
        val project = new Project(
          directory.toNIO,
          ProjectOperation.Sync,
          createDeclaration = (_, _) =>
            throw new ProjectException(ProjectErrorCode.StorageError, "injected create failure"),
        )
        val failure = intercept[ProjectException](
          project.sync(noSearch, noInspect, noFetch, init = true),
        )
        val invocation = s"ohi sync --init --project '$directory'"
        error(project, failure, declaration = false, lock = false, invocation = invocation)(
          "code",
        ).str shouldBe "STORAGE_ERROR"
        project.recoveryCommand(failure, invocation) shouldBe invocation
        os.read(directory / "ohi.lock.json") shouldBe currentLock
        os.list(directory).map(_.last) shouldBe Seq("ohi.lock.json")
      }
    }

    "report the created declaration when adoption later fails to retrieve an artifact" in {
      withDirectory { directory =>
        os.write(directory / "ohi.lock.json", currentLock)
        val project = new Project(directory.toNIO, ProjectOperation.Sync)
        val cause = FileDelivery.failure(
          CliFileErrorCode.FileWriteFailed,
          "injected retrieval failure",
          url,
          (directory / ".ohi").toString,
        )
        val failure = intercept[LockDownload.InstallationException](
          project.sync(noSearch, noInspect, _ => throw cause, init = true),
        )
        val data = error(project, failure, declaration = true, lock = false, created = true)
        data("files").arr shouldBe empty
        data("file_error")("url").str shouldBe url
        project.recoveryCommand(failure, "ohi sync --init") shouldBe
          s"ohi sync --project '$directory'"
        os.read(directory / "ohi.yaml") should include("ontology: test/example@1")
        os.read(directory / "ohi.lock.json") shouldBe currentLock
        os.read(directory / ".ohi" / ".gitignore") shouldBe "*\n"
        os.exists(directory / ".ohi" / "test" / "example" / "1" / "example.owl.ttl") shouldBe false
      }
    }
  }

  "project preflight" should {
    "refuse a conflicting transitive file before add writes or artifact requests" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", emptyDeclaration)
        os.write(directory / "ohi.lock.json", oldLock)
        val target = directory / ".ohi" / "test" / "dep" / "2" / "dep.owl.ttl"
        os.write(target, "different bytes", createFolders = true)
        val project = new Project(directory.toNIO, ProjectOperation.Add)
        val failure = intercept[CliFileException] {
          project.add(base, Some(url), None, search, graph, noFetch)
        }
        val data = error(project, failure, declaration = false, lock = false)
        data("file_error")("code").str shouldBe "HASH_MISMATCH"
        data("files").arr shouldBe empty
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        os.read(directory / "ohi.lock.json") shouldBe oldLock
        os.read(target) shouldBe "different bytes"
        os.exists(directory / ".ohi" / "test" / "example") shouldBe false
        os.exists(directory / ".ohi" / ".gitignore") shouldBe false
      }
    }

    "refuse a conflicting transitive file before sync writes or artifact requests" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", selectedDeclaration)
        os.write(directory / "ohi.lock.json", graphLock)
        val target = directory / ".ohi" / "test" / "dep" / "2" / "dep.owl.ttl"
        os.write(target, "different bytes", createFolders = true)
        val project = new Project(directory.toNIO, ProjectOperation.Sync)
        val failure = intercept[CliFileException] {
          project.sync(noSearch, noInspect, noFetch)
        }
        val data = error(project, failure, declaration = false, lock = false)
        data("file_error")("code").str shouldBe "HASH_MISMATCH"
        data("files").arr shouldBe empty
        os.read(directory / "ohi.yaml") shouldBe selectedDeclaration
        os.read(directory / "ohi.lock.json") shouldBe graphLock
        os.read(target) shouldBe "different bytes"
        os.exists(directory / ".ohi" / "test" / "example") shouldBe false
        os.exists(directory / ".ohi" / ".gitignore") shouldBe false
      }
    }

    "refuse a conflicting file before sync initialization creates a declaration" in {
      withDirectory { directory =>
        os.write(directory / "ohi.lock.json", graphLock)
        val target = directory / ".ohi" / "test" / "dep" / "2" / "dep.owl.ttl"
        os.write(target, "different bytes", createFolders = true)
        val project = new Project(directory.toNIO, ProjectOperation.Sync)
        val failure = intercept[CliFileException](
          project.sync(noSearch, noInspect, noFetch, init = true),
        )
        error(project, failure, declaration = false, lock = false)("file_error")(
          "code",
        ).str shouldBe "HASH_MISMATCH"
        os.exists(directory / "ohi.yaml") shouldBe false
        os.read(directory / "ohi.lock.json") shouldBe graphLock
        os.read(target) shouldBe "different bytes"
        os.exists(directory / ".ohi" / "test" / "example") shouldBe false
        os.exists(directory / ".ohi" / ".gitignore") shouldBe false
      }
    }

    "refuse a final lock symlink without reading or replacing its referent" in {
      withDirectory { directory =>
        os.write(directory / "ohi.yaml", emptyDeclaration)
        os.write(directory / "referent", "malformed untouched lock")
        Files.createSymbolicLink(
          (directory / "ohi.lock.json").toNIO,
          (directory / "referent").toNIO,
        )
        val project = new Project(directory.toNIO, ProjectOperation.Lock)
        val failure = intercept[ProjectException](project.refresh(noSearch, noInspect))
        error(project, failure, declaration = false, lock = false)(
          "code",
        ).str shouldBe "INVALID_PROJECT"
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        os.read(directory / "referent") shouldBe "malformed untouched lock"
        Files.isSymbolicLink((directory / "ohi.lock.json").toNIO) shouldBe true
      }
    }
  }
}
