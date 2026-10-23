package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.projecterror.{ProjectErrorCode, ProjectOperation}
import eu.neverblink.ohi.model.LockException
import eu.neverblink.ohi.model.generated.{Format, Publication}
import eu.neverblink.ohi.model.generated.lockerror.{LockErrorCode, LockError, LockOperation}
import eu.neverblink.linkml.generator.util.JsonUtil
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml
import tools.jackson.databind.json.JsonMapper

import java.io.{FilterOutputStream, IOException}
import java.nio.file.{Files, Path}
import java.nio.file.StandardOpenOption.{CREATE_NEW, WRITE}
import java.util.Locale
import scala.collection.mutable.ArrayBuffer

class ProjectSpec extends AnyWordSpec, Matchers {
  private val mapper = JsonMapper.builder().build()
  private val emptyDeclaration = "# preserve this comment\nformat_version: 1\nselections: []\n"
  private val oldLock = "{\"format_version\":1,\"selections\":[],\"files\":[]}"
  private val base = "https://example.org/publication"
  private val url = "https://example.org/file.ttl"
  private val metadata = ProjectTestData.publication(base, Seq(url -> Format.OwlTurtle))

  private def withDirectory(test: os.Path => Unit): Unit = {
    val directory = os.temp.dir(prefix = "ohi project ")
    try test(directory)
    finally os.remove.all(directory)
  }

  // Case-check resolvers that give the same answer on every host filesystem.
  private val ignoringCase: Path => Path =
    path => path.resolveSibling(path.getFileName.toString.toLowerCase(Locale.ROOT))
  private val respectingCase: Path => Path = identity

  private def noInspect(url: String): Publication = fail(s"Unexpected inspection: $url")
  private def noFetch(url: String): Array[Byte] = fail(s"Unexpected retrieval: $url")

  private def error(project: Project, cause: Throwable, declaration: Boolean, lock: Boolean) = {
    val data = mapper.readTree(ProjectOutput.error(project.failure(cause), json = true))
    data.get("project_directory").stringValue() shouldBe project.directory.toString
    data.get("declaration_changed").booleanValue() shouldBe declaration
    data.get("lock_changed").booleanValue() shouldBe lock
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
        val failure = intercept[ProjectException](project.init(createParents = true))
        failure.getCause shouldBe primary
        val data = error(project, failure, declaration = false, lock = false)
        data.get("operation").stringValue() shouldBe "INIT"
        data.get("code").stringValue() shouldBe "STORAGE_ERROR"
        os.exists(target) shouldBe false
        os.exists(directory / "nested") shouldBe true
        os.exists(directory / "nested" / "ohi.lock.json") shouldBe false
      }
    }

    "stop before lock and delivery when declaration replacement fails" in {
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
            url,
            (directory / "downloads").toString,
            value => { requests += value; metadata },
            noFetch,
          )
        }
        val data = error(project, failure, declaration = false, lock = false)
        data.get("code").stringValue() shouldBe "STORAGE_ERROR"
        data.get("message").stringValue() should include("rerun ohi add")
        requests.toSeq shouldBe Seq(base)
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        os.read(directory / "ohi.lock.json") shouldBe oldLock
        os.exists(directory / "downloads") shouldBe false
      }
    }

    "retain the new declaration and old lock with lock-then-get recovery after a lock failure" in {
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
          project.add(base, url, (directory / "downloads").toString, _ => metadata, noFetch)
        }
        val data = error(project, failure, declaration = true, lock = false)
        data.get("code").stringValue() shouldBe "OPERATION_FAILED"
        data.get("lock_error").get("operation").stringValue() shouldBe "WRITE"
        data.get("lock_error").get(
          "path",
        ).stringValue() shouldBe (directory / "ohi.lock.json").toString
        data.has("catalog_error") shouldBe false
        data.has("file_error") shouldBe false
        data.get("message").stringValue() should include(
          s"ohi lock --project '${directory.toString}'",
        )
        data.get("message").stringValue() should include(
          s"ohi get '$url' --to '${directory / "downloads"}'",
        )
        os.read(directory / "ohi.yaml") should include(base)
        os.read(directory / "ohi.yaml") should include(url)
        os.read(directory / "ohi.lock.json") shouldBe oldLock
        os.exists(directory / "downloads") shouldBe false
      }
    }

    "keep matching records and a competing download after delivery fails" in {
      withDirectory { directory =>
        val downloads = directory / "downloads"
        val target = downloads / "file.ttl"
        val project = new Project(directory.toNIO, ProjectOperation.Add)
        val failure = intercept[CliFileException] {
          project.add(
            base,
            url,
            downloads.toString,
            _ => metadata,
            _ => {
              os.makeDir(downloads)
              os.write(target, "competing file")
              Array[Byte](1, 2)
            },
          )
        }
        val data = error(project, failure, declaration = true, lock = true)
        data.get("code").stringValue() shouldBe "OPERATION_FAILED"
        data.get("file_error").get("code").stringValue() shouldBe "DESTINATION_EXISTS"
        data.get("file_error").get("path").stringValue() shouldBe target.toString
        data.get("file_error").get("directory").stringValue() shouldBe downloads.toString
        data.get("file_error").get("url").stringValue() shouldBe url
        data.has("lock_error") shouldBe false
        data.has("catalog_error") shouldBe false
        data.get("message").stringValue() should include(
          s"ohi get '$url' --to '$downloads'",
        )
        data.get("message").stringValue() should not include "ohi lock"
        val recorded = mapper.readTree(os.read(directory / "ohi.lock.json"))
        recorded shouldBe mapper.readTree(
          s"""{"format_version":2,"selections":[{"base_url":"$base","url":"$url"}],"files":[{"base_url":"$base","url":"$url","format":"owl-turtle","sha256":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"}]}""",
        )
        mapper.readTree(
          JsonUtil.yamlToJson(parseYaml(os.read(directory / "ohi.yaml")).toOption.get),
        ) shouldBe
          mapper.readTree(
            s"""{"format_version":1,"selections":[{"base_url":"$base","url":"$url"}]}""",
          )
        os.read(target) shouldBe "competing file"
        os.list(downloads).map(_.last) shouldBe Seq("file.ttl")
        os.list(directory).map(_.last).toSet shouldBe Set("ohi.yaml", "ohi.lock.json", "downloads")
      }
    }

    "preserve the declaration and old lock when refresh receives a different publication identity" in {
      withDirectory { directory =>
        val declaration =
          s"format_version: 1\nselections:\n  - base_url: $base\n    url: $url\n# human comment\n"
        os.write(directory / "ohi.yaml", declaration)
        os.write(directory / "ohi.lock.json", oldLock)
        val project = new Project(directory.toNIO, ProjectOperation.Lock)
        val requests = ArrayBuffer.empty[String]
        val failure = intercept[ProjectException] {
          project.refresh { requested =>
            requests += requested
            ProjectTestData.publication(base + "/", Seq(url -> Format.OwlTurtle))
          }
        }
        failure.code shouldBe ProjectErrorCode.InvalidSelection
        failure.getMessage should include(s"$base returned identity $base/")
        requests.toSeq shouldBe Seq(base)
        val data = error(project, failure, declaration = false, lock = false)
        data.get("message").stringValue() should include("Lock refresh did not complete")
        data.get("message").stringValue() should include(
          s"ohi lock --project '${directory.toString}'",
        )
        os.read(directory / "ohi.yaml") shouldBe declaration
        os.read(directory / "ohi.lock.json") shouldBe oldLock
      }
    }
  }

  "project preflight" should {
    "refuse a download named like the declaration when the directory ignores case" in {
      withDirectory { directory =>
        val project =
          new Project(directory.toNIO, ProjectOperation.Add, resolveProbe = ignoringCase)
        val failure = intercept[ProjectException] {
          project.add(base, "https://example.org/OHI.YAML", directory.toString, noInspect, noFetch)
        }
        failure.code shouldBe ProjectErrorCode.InvalidProject
        error(project, failure, declaration = false, lock = false)
        os.list(directory) shouldBe empty
      }
    }

    "refuse a download below a directory named like the lock when the directory ignores case" in {
      withDirectory { directory =>
        val project =
          new Project(directory.toNIO, ProjectOperation.Add, resolveProbe = ignoringCase)
        val failure = intercept[ProjectException] {
          project.add(
            base,
            "https://example.org/artifact.ttl",
            (directory / "OHI.LOCK.JSON" / "downloads").toString,
            noInspect,
            noFetch,
          )
        }
        failure.code shouldBe ProjectErrorCode.InvalidProject
        error(project, failure, declaration = false, lock = false)
        os.list(directory) shouldBe empty
      }
    }

    "accept a download named like the declaration when the directory respects case" in {
      withDirectory { directory =>
        val project =
          new Project(directory.toNIO, ProjectOperation.Add, resolveProbe = respectingCase)
        val inspected = ArrayBuffer.empty[String]
        val stop = new RuntimeException("stop after the path checks")
        val thrown = intercept[RuntimeException] {
          project.add(
            base,
            "https://example.org/OHI.YAML",
            directory.toString,
            value => { inspected += value; throw stop },
            noFetch,
          )
        }
        thrown shouldBe stop
        inspected.toSeq shouldBe Seq(base)
        os.list(directory) shouldBe empty
      }
    }

    "save a download named like the declaration inside a download subdirectory" in {
      withDirectory { directory =>
        val selected = "https://example.org/OHI.YAML"
        val publication = ProjectTestData.publication(base, Seq(selected -> Format.OwlTurtle))
        val requests = ArrayBuffer.empty[String]
        val project = new Project(directory.toNIO, ProjectOperation.Add)
        val result = project.add(
          base,
          selected,
          (directory / "downloads").toString,
          value => { requests += value; publication },
          value => { requests += value; Array[Byte](1, 2) },
        )
        result.declarationChanged shouldBe true
        result.lockChanged shouldBe true
        requests.toSeq shouldBe Seq(base, selected)
        os.read.bytes(directory / "downloads" / "OHI.YAML").toSeq shouldBe Seq[Byte](1, 2)
        os.walk(directory).filter(os.isFile(_)).map(_.relativeTo(directory).toString).toSet shouldBe
          Set("ohi.yaml", "ohi.lock.json", "downloads/OHI.YAML")
      }
    }

    "report the case probe it cannot remove after a successful case check" in {
      withDirectory { directory =>
        val cleanup = new IOException("injected cleanup failure")
        val project = new Project(
          directory.toNIO,
          ProjectOperation.Add,
          resolveProbe = respectingCase,
          deleteProbe = _ => throw cleanup,
        )
        val failure = intercept[ProjectException] {
          project.add(base, "https://example.org/OHI.YAML", directory.toString, noInspect, noFetch)
        }
        failure.getCause shouldBe cleanup
        cleanup.getSuppressed.toSeq shouldBe empty
        val data = error(project, failure, declaration = false, lock = false)
        data.get("code").stringValue() shouldBe "STORAGE_ERROR"
        val retained = os.list(directory)
        retained.size shouldBe 1
        retained.head.last should startWith(".ohi-case-")
        val message = data.get("message").stringValue()
        message should include(retained.head.toNIO.toRealPath().toString)
        message should include(cleanup.getMessage)
      }
    }

    "report the case probe it cannot remove and the lookup failure after a failed case check" in {
      withDirectory { directory =>
        val lookup = new IOException("injected lookup failure")
        val cleanup = new IOException("injected cleanup failure")
        val project = new Project(
          directory.toNIO,
          ProjectOperation.Add,
          resolveProbe = _ => throw lookup,
          deleteProbe = _ => throw cleanup,
        )
        val failure = intercept[ProjectException] {
          project.add(base, "https://example.org/OHI.YAML", directory.toString, noInspect, noFetch)
        }
        failure.getCause shouldBe lookup
        lookup.getSuppressed.toSeq shouldBe Seq(cleanup)
        val data = error(project, failure, declaration = false, lock = false)
        data.get("code").stringValue() shouldBe "STORAGE_ERROR"
        val retained = os.list(directory)
        retained.size shouldBe 1
        retained.head.last should startWith(".ohi-case-")
        val message = data.get("message").stringValue()
        message should include(retained.head.toNIO.toRealPath().toString)
        message should include(lookup.getMessage)
        message should include(cleanup.getMessage)
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
        val failure = intercept[ProjectException](project.refresh(noInspect))
        error(project, failure, declaration = false, lock = false).get(
          "code",
        ).stringValue() shouldBe "INVALID_PROJECT"
        os.read(directory / "ohi.yaml") shouldBe emptyDeclaration
        os.read(directory / "referent") shouldBe "malformed untouched lock"
        Files.isSymbolicLink((directory / "ohi.lock.json").toNIO) shouldBe true
      }
    }

  }
}
