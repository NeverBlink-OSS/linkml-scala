package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.generated.fileerror.CliFileErrorCode
import eu.neverblink.ohi.cli.generated.projectresult.LockFileStatus
import eu.neverblink.ohi.model.generated.error.{CatalogErrorCode, CatalogError, CatalogOperation}
import eu.neverblink.ohi.model.generated.lock.{Format, LockedFile}
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermission

class LockDownloadSpec extends AnyWordSpec, Matchers, Inside {
  private val binary = Array[Byte](0, -1, 13, 10, -128, 42)
  private val binaryHash = "cd7698ad007941ffd938610f7c36f642306cfc58b9f9b9550e4f72da6c2d55b7"
  private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  private val wrongHash = "a7770c6daaa4bdc22db60e2ad2d61f8a94b14b88e42865a93267469abb4cc365"
  private val zUrl = "https://hub.example/files/packages/test/z/1/source.ttl"
  private val aUrl = "https://hub.example/files/packages/test/a/1/source.ttl"

  private def file(
      name: String,
      hash: String = binaryHash,
      host: String = "hub.example",
  ): LockedFile = {
    val base = s"https://$host/files/packages/test/$name/1/"
    LockedFile(Uri(base), Format.OwlTurtle, Uri(base + "source.ttl"), Some(hash))
  }

  private def withDirectory(test: Path => Unit): Unit = {
    val dir = os.temp.dir(prefix = "ohi-lock-download-")
    try test(dir.toNIO)
    finally os.remove.all(dir)
  }

  private def write(root: Path, relative: String, bytes: Array[Byte]): Path = {
    val path = root.resolve(relative)
    Files.createDirectories(path.getParent)
    Files.write(path, bytes)
    path
  }

  "lock download planning" should {
    "refuse a managed ignore-file ancestor conflict before creating the root" in {
      withDirectory { root =>
        val directory = root.resolve("managed")
        val base = "https://hub.example/files/packages/.gitignore/z/1/"
        val locked =
          LockedFile(Uri(base), Format.OwlTurtle, Uri(base + "source.ttl"), Some(binaryHash))
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(locked),
            directory.toString,
            managed = true,
          )
        }.error
        error.code shouldBe CliFileErrorCode.OutputConflict
        Files.exists(directory) shouldBe false
        val plan = LockDownload.prepare(Seq(locked), directory.toString, managed = false)
        LockDownload.ensureIgnore(plan)
        val result = LockDownload.install(plan, _ => binary)
        result.head.path shouldBe directory.resolve(".gitignore/z/1/z.owl.ttl").toString
        Files.readAllBytes(
          directory.resolve(".gitignore/z/1/z.owl.ttl"),
        ).toSeq shouldBe binary.toSeq
      }
    }

    "retain first-source order and share only equal target paths and digests" in {
      withDirectory { root =>
        val directory = root.resolve("nested")
        val files = Seq(file("z"), file("a", emptyHash), file("z", host = "other.example"))
        var requests = Vector.empty[String]
        val plan = LockDownload.prepare(files, directory.toString, managed = false)
        LockDownload.ensureIgnore(plan)
        val result = LockDownload.install(
          plan,
          url => {
            requests :+= url
            if url == zUrl then binary else Array.emptyByteArray
          },
        )
        requests shouldBe Vector(zUrl, aUrl)
        result.map(f => (f.url.original, f.path, f.status)) shouldBe Seq(
          (zUrl, directory.resolve("test/z/1/z.owl.ttl").toString, LockFileStatus.Downloaded),
          (aUrl, directory.resolve("test/a/1/a.owl.ttl").toString, LockFileStatus.Downloaded),
        )
        Files.readAllBytes(directory.resolve("test/z/1/z.owl.ttl")).toSeq shouldBe Seq[Byte](0, -1,
          13, 10, -128, 42)
        Files.size(directory.resolve("test/a/1/a.owl.ttl")) shouldBe 0L
        Files.exists(directory.resolve(".gitignore")) shouldBe false
      }
    }

    "refuse contradictory hashes before interpreting the destination or fetching" in {
      val error = intercept[CliFileException] {
        LockDownload.prepare(
          Seq(file("z"), file("z", emptyHash, "other.example")),
          "invalid\u0000directory",
          managed = false,
        )
      }.error
      error.code shouldBe CliFileErrorCode.OutputConflict
      error.url shouldBe "https://other.example/files/packages/test/z/1/source.ttl"
      error.path shouldBe None
    }

    "reject malformed locked Hub routes before creating a destination" in {
      withDirectory { root =>
        val directory = root.resolve("not-created")
        val invalid = file("z").copy(baseUrl = Uri("https://hub.example/arbitrary/"))
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(invalid),
            directory.toString,
            managed = false,
          )
        }.error
        error.code shouldBe CliFileErrorCode.UnsupportedFilename
        os.list(os.Path(root)) shouldBe empty
      }
    }

    "stop on the first source failure without trying an equal-content alternate URL" in {
      withDirectory { root =>
        var requests = Vector.empty[String]
        val plan = LockDownload.prepare(
          Seq(file("z"), file("z", host = "alternate.example")),
          root.resolve("not-created").toString,
          managed = false,
        )
        LockDownload.ensureIgnore(plan)
        val error = intercept[LockDownload.InstallationException] {
          LockDownload.install(
            plan,
            url => {
              requests :+= url
              throw new CatalogException(
                CatalogError(
                  CatalogErrorCode.HttpError,
                  "Unavailable",
                  CatalogOperation.Get,
                  url = Some(url),
                  status = Some(404),
                ),
              )
            },
          )
        }
        requests shouldBe Vector(zUrl)
        error.files shouldBe empty
        inside(error.cause) { case cause: CatalogException =>
          cause.error.url shouldBe Some(zUrl)
          cause.error.status shouldBe Some(404)
        }
        os.list(os.Path(root)) shouldBe empty
      }
    }
  }

  "lock download verification" should {
    "refuse a later mismatching file before fetching an earlier missing output" in {
      withDirectory { root =>
        val existing = write(root, "test/a/1/a.owl.ttl", Array[Byte](71, 72))
        val modified = Files.getLastModifiedTime(existing)
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(file("z"), file("a")),
            root.toString,
            managed = false,
          )
        }.error
        error.code shouldBe CliFileErrorCode.HashMismatch
        error.path shouldBe Some(existing.toString)
        error.message should include(binaryHash)
        error.message should include(wrongHash)
        error.message should include("Move or remove the local file")
        Files.exists(root.resolve("test/z")) shouldBe false
        Files.readAllBytes(existing).toSeq shouldBe Seq[Byte](71, 72)
        Files.getLastModifiedTime(existing) shouldBe modified
      }
    }

    "refuse a directory instead of verifying through it" in {
      withDirectory { root =>
        val path = root.resolve("test/z/1/z.owl.ttl")
        Files.createDirectories(path.getParent)
        Files.createDirectory(path)
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(file("z")),
            root.toString,
            managed = false,
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(path.toString)
        Files.isDirectory(path) shouldBe true
      }
    }

    "refuse a matching symbolic link instead of verifying through it" in {
      withDirectory { root =>
        val path = root.resolve("test/z/1/z.owl.ttl")
        Files.createDirectories(path.getParent)
        Files.write(root.resolve("retained.ttl"), binary)
        Files.createSymbolicLink(path, root.resolve("retained.ttl"))
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(file("z")),
            root.toString,
            managed = false,
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(path.toString)
        Files.readSymbolicLink(path) shouldBe root.resolve("retained.ttl")
        Files.readAllBytes(root.resolve("retained.ttl")).toSeq shouldBe binary.toSeq
      }
    }

    "refuse a symlink at the root before fetching or writing outside the root" in {
      withDirectory { root =>
        val outside = Files.createDirectory(root.resolve("outside"))
        val directory = root.resolve("managed")
        val link = directory
        Files.createSymbolicLink(link, outside)
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(file("z")),
            directory.toString,
            managed = false,
          )
        }.error
        error.code shouldBe CliFileErrorCode.InvalidDestination
        error.path shouldBe Some(directory.resolve("test/z/1/z.owl.ttl").toString)
        Files.readSymbolicLink(link) shouldBe outside
        os.list(os.Path(outside)) shouldBe empty
      }
    }

    "refuse a symlink at a nested ancestor before fetching or writing outside the root" in {
      withDirectory { root =>
        val outside = Files.createDirectory(root.resolve("outside"))
        val directory = root.resolve("managed")
        Files.createDirectory(directory)
        val link = directory.resolve("test")
        Files.createSymbolicLink(link, outside)
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(file("z")),
            directory.toString,
            managed = false,
          )
        }.error
        error.code shouldBe CliFileErrorCode.InvalidDestination
        error.path shouldBe Some(directory.resolve("test/z/1/z.owl.ttl").toString)
        Files.readSymbolicLink(link) shouldBe outside
        os.list(os.Path(outside)) shouldBe empty
      }
    }

    "report unreadable file permissions instead of treating an existing output as missing" in {
      withDirectory { root =>
        assume(Files.getFileStore(root).supportsFileAttributeView("posix"))
        val path = write(root, "test/z/1/z.owl.ttl", binary)
        val blockedPath = path
        val permissions = Files.getPosixFilePermissions(blockedPath)
        try {
          Files.setPosixFilePermissions(blockedPath, java.util.Set.of[PosixFilePermission]())
          assume(
            !Files.isReadable(path),
            "The process can read files despite removed read permissions",
          )
          val error = intercept[CliFileException] {
            LockDownload.prepare(
              Seq(file("z")),
              root.toString,
              managed = false,
            )
          }.error
          error.code shouldBe CliFileErrorCode.FileReadFailed
          error.path shouldBe Some(path.toString)
          error.message should include("Check its permissions")
        } finally Files.setPosixFilePermissions(blockedPath, permissions)
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
      }
    }

    "report unreadable directory permissions instead of treating an existing output as missing" in {
      withDirectory { root =>
        assume(Files.getFileStore(root).supportsFileAttributeView("posix"))
        val path = write(root, "test/z/1/z.owl.ttl", binary)
        val blockedPath = path.getParent
        val permissions = Files.getPosixFilePermissions(blockedPath)
        try {
          Files.setPosixFilePermissions(blockedPath, java.util.Set.of[PosixFilePermission]())
          assume(
            !Files.isReadable(path),
            "The process can read files despite removed read permissions",
          )
          val error = intercept[CliFileException] {
            LockDownload.prepare(
              Seq(file("z")),
              root.toString,
              managed = false,
            )
          }.error
          error.code shouldBe CliFileErrorCode.FileReadFailed
          error.path shouldBe Some(path.toString)
          error.message should include("Check its permissions")
        } finally Files.setPosixFilePermissions(blockedPath, permissions)
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
      }
    }

    "reject incorrect fetched bytes before creating the target or its missing parents" in {
      withDirectory { root =>
        val directory = root.resolve("not-created/nested")
        var requests = Vector.empty[String]
        val plan = LockDownload.prepare(Seq(file("z")), directory.toString, managed = false)
        LockDownload.ensureIgnore(plan)
        val error = intercept[LockDownload.InstallationException] {
          LockDownload.install(
            plan,
            url => {
              requests :+= url
              Array[Byte](71, 72)
            },
          )
        }
        requests shouldBe Vector(zUrl)
        error.files shouldBe empty
        inside(error.cause) { case cause: CliFileException =>
          cause.error.code shouldBe CliFileErrorCode.HashMismatch
          cause.error.path shouldBe Some(directory.resolve("test/z/1/z.owl.ttl").toString)
          cause.error.message should include(binaryHash)
          cause.error.message should include(wrongHash)
          cause.error.message should include("Correct the lock or remote file")
          cause.error.partialPath shouldBe None
        }
        os.list(os.Path(root)) shouldBe empty
      }
    }

    "retain completed files after a later fetch failure and verify them on retry" in {
      withDirectory { root =>
        val files = Seq(file("z"), file("a"))
        val plan = LockDownload.prepare(files, root.toString, managed = false)
        LockDownload.ensureIgnore(plan)
        val error = intercept[LockDownload.InstallationException] {
          LockDownload.install(
            plan,
            url => {
              if url == aUrl then
                throw new CatalogException(
                  CatalogError(
                    CatalogErrorCode.HttpError,
                    "Unavailable",
                    CatalogOperation.Get,
                    url = Some(url),
                    status = Some(404),
                  ),
                )
              binary
            },
          )
        }
        error.files.map(f => (f.url.original, f.path, f.status)) shouldBe Seq(
          (zUrl, root.resolve("test/z/1/z.owl.ttl").toString, LockFileStatus.Downloaded),
        )
        inside(error.cause) { case cause: CatalogException =>
          cause.error.url shouldBe Some(aUrl)
        }
        Files.readAllBytes(root.resolve("test/z/1/z.owl.ttl")).toSeq shouldBe Seq[Byte](0, -1, 13,
          10, -128, 42)
        Files.exists(root.resolve("test/a")) shouldBe false
        var requests = Vector.empty[String]
        val retryPlan = LockDownload.prepare(files, root.toString, managed = false)
        LockDownload.ensureIgnore(retryPlan)
        val retry = LockDownload.install(
          retryPlan,
          url => {
            requests :+= url
            binary
          },
        )
        requests shouldBe Vector(aUrl)
        retry.map(_.status) shouldBe Seq(LockFileStatus.Verified, LockFileStatus.Downloaded)
        Files.readAllBytes(root.resolve("test/a/1/a.owl.ttl")).toSeq shouldBe binary.toSeq
      }
    }

    "verify distinct hard-link entries without conflating their shared file identity" in {
      withDirectory { root =>
        val z = write(root, "test/z/1/z.owl.ttl", binary)
        val a = root.resolve("test/a/1/a.owl.ttl")
        Files.createDirectories(a.getParent)
        Files.createLink(a, z)
        val plan = LockDownload.prepare(Seq(file("z"), file("a")), root.toString, managed = false)
        LockDownload.ensureIgnore(plan)
        val result = LockDownload.install(plan, _ => fail("fetch must not run"))
        result.map(f => (f.path, f.status)) shouldBe Seq(
          (z.toString, LockFileStatus.Verified),
          (a.toString, LockFileStatus.Verified),
        )
        Files.readAllBytes(z).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
        Files.readAllBytes(a).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
      }
    }
  }

  "managed ignore files" should {
    "defer creation until after read-only preflight and write the literal ignore bytes" in {
      withDirectory { root =>
        val directory = root.resolve("managed")
        val plan = LockDownload.prepare(Seq(file("z")), directory.toString, managed = true)
        Files.exists(directory) shouldBe false
        LockDownload.ensureIgnore(plan)
        Files.readAllBytes(directory.resolve(".gitignore")).toSeq shouldBe Seq[Byte](42, 10)
        Files.exists(directory.resolve("test")) shouldBe false
        val result = LockDownload.install(plan, _ => binary)
        result.map(_.status) shouldBe Seq(LockFileStatus.Downloaded)
        Files.readAllBytes(directory.resolve("test/z/1/z.owl.ttl")).toSeq shouldBe binary.toSeq
      }
    }

    "preserve existing regular ignore text verbatim" in {
      withDirectory { root =>
        val original = "# Keep selected ontology files\n*.tmp\n".getBytes(UTF_8)
        Files.write(root.resolve(".gitignore"), original)
        val plan = LockDownload.prepare(Seq(file("z")), root.toString, managed = true)
        LockDownload.ensureIgnore(plan)
        val result = LockDownload.install(plan, _ => binary)
        result.map(_.status) shouldBe Seq(LockFileStatus.Downloaded)
        Files.readAllBytes(root.resolve(".gitignore")).toSeq shouldBe original.toSeq
      }
    }

    "refuse an ignore-file directory during preflight without artifact requests" in {
      withDirectory { root =>
        val ignore = root.resolve(".gitignore")
        Files.createDirectory(ignore)
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(file("z")),
            root.toString,
            managed = true,
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(ignore.toString)
        Files.exists(root.resolve("test")) shouldBe false
      }
    }

    "refuse an ignore-file symbolic link during preflight without artifact requests" in {
      withDirectory { root =>
        val ignore = root.resolve(".gitignore")
        Files.createSymbolicLink(ignore, Path.of("missing"))
        val error = intercept[CliFileException] {
          LockDownload.prepare(
            Seq(file("z")),
            root.toString,
            managed = true,
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(ignore.toString)
        Files.exists(root.resolve("test")) shouldBe false
      }
    }

    "create no directory or ignore file for an empty managed installation" in {
      withDirectory { root =>
        val directory = root.resolve("not-created")
        val plan = LockDownload.prepare(Seq.empty, directory.toString, managed = true)
        LockDownload.ensureIgnore(plan)
        val result = LockDownload.install(plan, _ => fail("fetch must not run"))
        result shouldBe empty
        os.list(os.Path(root)) shouldBe empty
      }
    }
  }
}
