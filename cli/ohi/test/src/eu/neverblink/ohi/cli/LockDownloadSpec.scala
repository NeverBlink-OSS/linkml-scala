package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.generated.lockdownload.{
  CliFileErrorCode,
  LockDownloadError,
  LockDownloadResult,
  LockFileStatus,
}
import eu.neverblink.ohi.model.generated.error.{CatalogErrorCode, CatalogError, CatalogOperation}
import eu.neverblink.ohi.model.generated.lock.{Format, LockedFile}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermission

class LockDownloadSpec extends AnyWordSpec, Matchers {
  private val binary = Array[Byte](0, -1, 13, 10, -128, 42)
  private val binaryHash = "cd7698ad007941ffd938610f7c36f642306cfc58b9f9b9550e4f72da6c2d55b7"
  private val emptyHash = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  private val wrongHash = "a7770c6daaa4bdc22db60e2ad2d61f8a94b14b88e42865a93267469abb4cc365"

  private def file(
      url: String,
      hash: String = binaryHash,
      publication: String = "https://catalog.example/direct/",
  ): LockedFile = LockedFile(Uri(publication), Format.OwlTurtle, Uri(url), Some(hash))

  private def withDirectory(test: Path => Unit): Unit = {
    val dir = os.temp.dir(prefix = "ohi-lock-download-")
    try test(dir.toNIO)
    finally os.remove.all(dir)
  }

  private def success(result: Either[LockDownloadError, LockDownloadResult]): LockDownloadResult =
    result.fold(error => fail(s"Unexpected download error: $error"), identity)

  private def failure(result: Either[LockDownloadError, LockDownloadResult]): LockDownloadError =
    result.fold(identity, result => fail(s"Unexpected download success: $result"))

  "lock download planning" should {
    "retain first-source order and share only equal filenames and digests across publications" in {
      withDirectory { root =>
        val directory = root.resolve("nested")
        val files = Seq(
          file("https://first.example/z.ttl"),
          file("https://files.example/a.ttl", emptyHash, "https://catalog.example/dependency/"),
          file("https://other.example/z.ttl", publication = "https://catalog.example/dependency/"),
        )
        var requests = Vector.empty[String]
        val result = success(
          LockDownload.download(
            files,
            directory.toString,
            url => {
              requests :+= url
              if url == "https://first.example/z.ttl" then binary else Array.emptyByteArray
            },
          ),
        )
        requests shouldBe Vector("https://first.example/z.ttl", "https://files.example/a.ttl")
        result.files.map(f => (f.url.original, f.path, f.status)) shouldBe Seq(
          (
            "https://first.example/z.ttl",
            directory.resolve("z.ttl").toString,
            LockFileStatus.Downloaded,
          ),
          (
            "https://files.example/a.ttl",
            directory.resolve("a.ttl").toString,
            LockFileStatus.Downloaded,
          ),
        )
        Files.readAllBytes(directory.resolve("z.ttl")).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128,
          42)
        Files.size(directory.resolve("a.ttl")) shouldBe 0L
        os.list(os.Path(directory)).map(_.last).sorted shouldBe Seq("a.ttl", "z.ttl")
      }
    }

    for (first, second, description) <- Seq(
        (
          file("https://first.example/z.ttl"),
          file("https://second.example/z.ttl", emptyHash),
          "different hashes for an equal basename",
        ),
        (
          file("https://files.example/z.ttl"),
          file("https://files.example/z.ttl", emptyHash, "https://catalog.example/dependency/"),
          "contradictory hashes for one URL under different publications",
        ),
        (
          file("https://files.example/caf%C3%A9_A.ttl"),
          file("https://files.example/caf%C3%A9_a.ttl"),
          "ASCII case variants within Unicode names",
        ),
      )
    do
      s"refuse $description before requests or destination creation" in {
        withDirectory { root =>
          val directory = root.resolve("not-created")
          val error = failure(
            LockDownload.download(
              Seq(first, second),
              directory.toString,
              _ => fail("fetch must not run"),
            ),
          )
          error.files shouldBe empty
          error.fileError.get.code shouldBe CliFileErrorCode.OutputConflict
          error.fileError.get.url shouldBe second.url.original
          error.fileError.get.directory shouldBe directory.toString
          error.fileError.get.path shouldBe None
          Files.exists(directory) shouldBe false
          os.list(os.Path(root)) shouldBe empty
        }
      }

    "complete string collision checks before interpreting a destination path" in {
      val error = failure(
        LockDownload.download(
          Seq(file("https://files.example/z.ttl"), file("https://files.example/Z.ttl")),
          "invalid\u0000directory",
          _ => fail("fetch must not run"),
        ),
      )
      error.files shouldBe empty
      error.fileError.get.code shouldBe CliFileErrorCode.OutputConflict
      error.fileError.get.path shouldBe None
    }

    "stop on the first source failure without trying an equal-content alternate URL" in {
      withDirectory { root =>
        var requests = Vector.empty[String]
        val error = failure(
          LockDownload.download(
            Seq(file("https://first.example/z.ttl"), file("https://alternate.example/z.ttl")),
            root.resolve("not-created").toString,
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
          ),
        )
        requests shouldBe Vector("https://first.example/z.ttl")
        error.files shouldBe empty
        error.catalogError.get.url shouldBe Some("https://first.example/z.ttl")
        error.catalogError.get.status shouldBe Some(404)
        os.list(os.Path(root)) shouldBe empty
      }
    }
  }

  "lock download verification" should {
    "refuse a later mismatching file before fetching an earlier missing output" in {
      withDirectory { root =>
        val existing = root.resolve("a.ttl")
        Files.write(existing, Array[Byte](71, 72))
        val modified = Files.getLastModifiedTime(existing)
        val error = failure(
          LockDownload.download(
            Seq(file("https://files.example/z.ttl"), file("https://files.example/a.ttl")),
            root.toString,
            _ => fail("fetch must not run"),
          ),
        )
        error.files shouldBe empty
        error.fileError.get.code shouldBe CliFileErrorCode.HashMismatch
        error.fileError.get.path shouldBe Some(existing.toString)
        error.fileError.get.message should include(binaryHash)
        error.fileError.get.message should include(wrongHash)
        error.fileError.get.message should include("Move or remove the local file")
        Files.exists(root.resolve("z.ttl")) shouldBe false
        Files.readAllBytes(existing).toSeq shouldBe Seq[Byte](71, 72)
        Files.getLastModifiedTime(existing) shouldBe modified
      }
    }

    for entry <- Seq("directory", "matching symbolic link") do
      s"refuse a $entry instead of verifying through it" in {
        withDirectory { root =>
          val path = root.resolve("z.ttl")
          if entry == "directory" then Files.createDirectory(path)
          else {
            Files.write(root.resolve("retained.ttl"), binary)
            Files.createSymbolicLink(path, Path.of("retained.ttl"))
          }
          val error = failure(
            LockDownload.download(
              Seq(file("https://files.example/z.ttl")),
              root.toString,
              _ => fail("fetch must not run"),
            ),
          )
          error.files shouldBe empty
          error.fileError.get.code shouldBe CliFileErrorCode.DestinationExists
          error.fileError.get.path shouldBe Some(path.toString)
          if entry == "directory" then Files.isDirectory(path) shouldBe true
          else {
            Files.readSymbolicLink(path) shouldBe Path.of("retained.ttl")
            Files.readAllBytes(root.resolve("retained.ttl")).toSeq shouldBe binary.toSeq
          }
        }
      }

    for blockedEntry <- Seq("file", "directory") do
      s"report unreadable $blockedEntry permissions instead of treating an existing output as missing" in {
        withDirectory { root =>
          assume(Files.getFileStore(root).supportsFileAttributeView("posix"))
          val path = root.resolve("z.ttl")
          Files.write(path, binary)
          val blockedPath = if blockedEntry == "file" then path else root
          val permissions = Files.getPosixFilePermissions(blockedPath)
          try {
            Files.setPosixFilePermissions(blockedPath, java.util.Set.of[PosixFilePermission]())
            assume(
              !Files.isReadable(path),
              "The process can read files despite removed read permissions",
            )
            val error = failure(
              LockDownload.download(
                Seq(file("https://files.example/z.ttl")),
                root.toString,
                _ => fail("fetch must not run"),
              ),
            )
            error.files shouldBe empty
            error.fileError.get.code shouldBe CliFileErrorCode.FileReadFailed
            error.fileError.get.path shouldBe Some(path.toString)
            error.fileError.get.message should include("Check its permissions")
          } finally Files.setPosixFilePermissions(blockedPath, permissions)
          Files.readAllBytes(path).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
        }
      }

    "reject incorrect fetched bytes before creating the target or its missing parents" in {
      withDirectory { root =>
        val directory = root.resolve("not-created/nested")
        var requests = Vector.empty[String]
        val error = failure(
          LockDownload.download(
            Seq(file("https://files.example/z.ttl")),
            directory.toString,
            url => {
              requests :+= url
              Array[Byte](71, 72)
            },
          ),
        )
        requests shouldBe Vector("https://files.example/z.ttl")
        error.files shouldBe empty
        error.fileError.get.code shouldBe CliFileErrorCode.HashMismatch
        error.fileError.get.path shouldBe Some(directory.resolve("z.ttl").toString)
        error.fileError.get.message should include(binaryHash)
        error.fileError.get.message should include(wrongHash)
        error.fileError.get.message should include("Correct the lock or remote file")
        error.fileError.get.partialPath shouldBe None
        os.list(os.Path(root)) shouldBe empty
      }
    }
  }

  "lock download filesystem aliases" should {
    "retain the first missing alias and refuse the second without reclassifying it, then refuse retry preflight" in {
      withDirectory { root =>
        val first = root.resolve("Ä.ttl")
        val second = root.resolve("ä.ttl")
        Files.write(first, binary)
        assume(Files.exists(second), "The filesystem keeps non-ASCII case spellings distinct")
        Files.delete(first)
        val files =
          Seq(file("https://files.example/%C3%84.ttl"), file("https://files.example/%C3%A4.ttl"))
        var requests = Vector.empty[String]
        val error = failure(
          LockDownload.download(
            files,
            root.toString,
            url => {
              requests :+= url
              binary
            },
          ),
        )
        requests shouldBe Vector("https://files.example/%C3%84.ttl")
        error.files.map(f => (f.url.original, f.path, f.status)) shouldBe Seq(
          ("https://files.example/%C3%84.ttl", first.toString, LockFileStatus.Downloaded),
        )
        error.fileError.get.code shouldBe CliFileErrorCode.DestinationExists
        error.fileError.get.path shouldBe Some(second.toString)
        Files.readAllBytes(first).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
        val modified = Files.getLastModifiedTime(first)
        val retry =
          failure(LockDownload.download(files, root.toString, _ => fail("fetch must not run")))
        retry.files shouldBe empty
        retry.fileError.get.code shouldBe CliFileErrorCode.OutputConflict
        retry.fileError.get.path shouldBe Some(second.toString)
        Files.readAllBytes(first).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
        Files.getLastModifiedTime(first) shouldBe modified
        os.list(os.Path(root)).size shouldBe 1
      }
    }

    "verify distinct hard-link entries without conflating their shared file identity" in {
      withDirectory { root =>
        Files.write(root.resolve("z.ttl"), binary)
        Files.createLink(root.resolve("a.ttl"), root.resolve("z.ttl"))
        val result = success(
          LockDownload.download(
            Seq(file("https://files.example/z.ttl"), file("https://files.example/a.ttl")),
            root.toString,
            _ => fail("fetch must not run"),
          ),
        )
        result.files.map(f => (f.path, f.status)) shouldBe Seq(
          (root.resolve("z.ttl").toString, LockFileStatus.Verified),
          (root.resolve("a.ttl").toString, LockFileStatus.Verified),
        )
        Files.readAllBytes(root.resolve("z.ttl")).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
        Files.readAllBytes(root.resolve("a.ttl")).toSeq shouldBe Seq[Byte](0, -1, 13, 10, -128, 42)
      }
    }
  }
}
