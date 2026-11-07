package eu.neverblink.ohi.cli

import eu.neverblink.ohi.catalog.CatalogException
import eu.neverblink.ohi.cli.generated.fileerror.CliFileErrorCode
import eu.neverblink.ohi.model.generated.error.{CatalogErrorCode, CatalogError, CatalogOperation}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.{FilterOutputStream, IOException, OutputStream}
import java.nio.file.{Files, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.{CREATE_NEW, WRITE}

class FileDeliverySpec extends AnyWordSpec, Matchers {

  private val url = "https://files.example/model.ttl?revision=2"

  private def withDirectory(test: Path => Unit): Unit = {
    val dir = os.temp.dir(prefix = "ohi-delivery-")
    try test(dir.toNIO)
    finally os.remove.all(dir)
  }

  private def openFile(path: Path): OutputStream = Files.newOutputStream(path, CREATE_NEW, WRITE)

  "file delivery naming" should {
    "resolve relative directories without creating them during preflight" in {
      withDirectory { root =>
        val directory = Path.of("").toAbsolutePath.relativize(root.resolve("unused/../nested"))
        FileDelivery.resolve(url, directory.toString, Path.of("model.ttl")) shouldBe root.resolve(
          "nested/model.ttl",
        )
        Files.exists(root.resolve("nested")) shouldBe false
        Files.exists(root.resolve("unused")) shouldBe false
      }
    }

  }

  "file delivery" should {
    "fetch once before creating parents and preserve binary bytes without text conversion" in {
      withDirectory { root =>
        val directory = root.resolve("nested/output.ttl")
        var requests = Vector.empty[String]
        FileDelivery.save(
          url,
          directory.toString,
          Path.of("model.ttl"),
          actual => {
            requests :+= actual
            Files.exists(root.resolve("nested")) shouldBe false
            Array[Byte](0, -1, 13, 10, -128, 42)
          },
        )
        requests shouldBe Vector("https://files.example/model.ttl?revision=2")
        Files.readAllBytes(directory.resolve("model.ttl")).toSeq shouldBe
          Seq[Byte](0, -1, 13, 10, -128, 42)
      }
    }

    "create an empty file instead of treating an empty payload as a failed fetch" in {
      withDirectory { root =>
        FileDelivery.save(url, root.toString, Path.of("model.ttl"), _ => Array.emptyByteArray)
        Files.isRegularFile(root.resolve("model.ttl")) shouldBe true
        Files.size(root.resolve("model.ttl")) shouldBe 0L
      }
    }

    "reject an existing non-directory destination before fetching and preserve its bytes" in {
      withDirectory { root =>
        val directory = root.resolve("existing-file")
        Files.write(directory, Array[Byte](71, 72))
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            directory.toString,
            Path.of("model.ttl"),
            _ => fail("fetch must not run"),
          )
        }.error
        error.code shouldBe CliFileErrorCode.InvalidDestination
        error.directory shouldBe directory.toString
        error.path shouldBe Some(directory.resolve("model.ttl").toString)
        error.partialPath shouldBe None
        Files.readAllBytes(directory).toSeq shouldBe Seq[Byte](71, 72)
      }
    }

    "refuse an existing target file before fetching without changing the entry" in {
      withDirectory { root =>
        val path = root.resolve("model.ttl")
        Files.write(path, Array[Byte](71, 72))
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => fail("fetch must not run"),
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(path.toString)
        error.partialPath shouldBe None
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72)
      }
    }

    "refuse an existing target directory before fetching without changing the entry" in {
      withDirectory { root =>
        val path = root.resolve("model.ttl")
        Files.createDirectory(path)
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => fail("fetch must not run"),
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(path.toString)
        error.partialPath shouldBe None
        Files.isDirectory(path) shouldBe true
      }
    }

    "refuse an existing target dangling symbolic link before fetching without changing the entry" in {
      withDirectory { root =>
        val path = root.resolve("model.ttl")
        Files.createSymbolicLink(path, Path.of("missing-target"))
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => fail("fetch must not run"),
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(path.toString)
        error.partialPath shouldBe None
        Files.readSymbolicLink(path) shouldBe Path.of("missing-target")
      }
    }

    "preserve a real competing target created after preflight instead of overwriting it" in {
      withDirectory { root =>
        val path = root.resolve("model.ttl")
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => {
              Files.write(path, Array[Byte](71, 72))
              Array[Byte](1, 2, 3)
            },
          )
        }.error
        error.code shouldBe CliFileErrorCode.DestinationExists
        error.path shouldBe Some(path.toString)
        error.partialPath shouldBe None
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72)
      }
    }

    "propagate the original SDK fetch failure without creating destination parents" in {
      withDirectory { root =>
        val directory = root.resolve("not-created/nested")
        val expected = new CatalogException(
          CatalogError(
            code = CatalogErrorCode.HttpError,
            message = "File is unavailable",
            operation = CatalogOperation.Get,
            status = Some(404),
            targetUrl = Some(url),
          ),
        )
        val actual = intercept[CatalogException] {
          FileDelivery.save(url, directory.toString, Path.of("model.ttl"), _ => throw expected)
        }
        actual should be theSameInstanceAs expected
        actual.error.code shouldBe CatalogErrorCode.HttpError
        actual.error.operation shouldBe CatalogOperation.Get
        actual.error.status shouldBe Some(404)
        actual.error.targetUrl shouldBe Some("https://files.example/model.ttl?revision=2")
        Files.exists(root.resolve("not-created")) shouldBe false
      }
    }

    "report directory creation failure after retrieval without changing a competing parent file" in {
      withDirectory { root =>
        val parent = root.resolve("blocked")
        val directory = parent.resolve("nested")
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            directory.toString,
            Path.of("model.ttl"),
            _ => {
              Files.write(parent, Array[Byte](71, 72))
              Array[Byte](1)
            },
          )
        }.error
        error.code shouldBe CliFileErrorCode.InvalidDestination
        error.path shouldBe Some(directory.resolve("model.ttl").toString)
        error.partialPath shouldBe None
        Files.readAllBytes(parent).toSeq shouldBe Seq[Byte](71, 72)
        Files.exists(directory, NOFOLLOW_LINKS) shouldBe false
      }
    }

    "refuse an ancestor replaced by a symlink during retrieval before writing outside the root" in {
      withDirectory { root =>
        val directory = Files.createDirectory(root.resolve("managed"))
        val outside = Files.createDirectory(root.resolve("outside"))
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            directory.toString,
            Path.of("nested/model.ttl"),
            _ => {
              Files.createSymbolicLink(directory.resolve("nested"), outside)
              Array[Byte](1)
            },
          )
        }.error
        error.code shouldBe CliFileErrorCode.InvalidDestination
        error.path shouldBe Some(directory.resolve("nested/model.ttl").toString)
        Files.readSymbolicLink(directory.resolve("nested")) shouldBe outside
        os.list(os.Path(outside)) shouldBe empty
      }
    }

    "report target creation failure without attempting to delete an unowned file" in {
      withDirectory { root =>
        val primary = new IOException("Cannot open target")
        val failure = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => Array[Byte](1),
            _ => throw primary,
            _ => fail("delete must not run before creation"),
          )
        }
        failure.error.code shouldBe CliFileErrorCode.FileWriteFailed
        failure.error.path shouldBe Some(root.resolve("model.ttl").toString)
        failure.error.partialPath shouldBe None
        failure.getCause should be theSameInstanceAs primary
        Files.exists(root.resolve("model.ttl")) shouldBe false
      }
    }

    "close and remove its own new file after a write failure without reporting success" in {
      withDirectory { root =>
        val primary = new IOException("write failed")
        var closed = false
        val failure = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => Array[Byte](1, 2, 3),
            path =>
              new FilterOutputStream(openFile(path)) {
                override def write(bytes: Array[Byte]): Unit = {
                  out.write(bytes, 0, 1)
                  throw primary
                }
                override def close(): Unit = {
                  super.close()
                  closed = true
                }
              },
            path => Files.delete(path),
          )
        }
        failure.error.code shouldBe CliFileErrorCode.FileWriteFailed
        failure.error.path shouldBe Some(root.resolve("model.ttl").toString)
        failure.error.partialPath shouldBe None
        failure.getCause should be theSameInstanceAs primary
        closed shouldBe true
        Files.exists(root.resolve("model.ttl"), NOFOLLOW_LINKS) shouldBe false
      }
    }

    "close and remove its own new file after a close failure without reporting success" in {
      withDirectory { root =>
        val primary = new IOException("close failed")
        var closed = false
        val failure = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => Array[Byte](1, 2, 3),
            path =>
              new FilterOutputStream(openFile(path)) {
                override def write(bytes: Array[Byte]): Unit = {
                  out.write(bytes, 0, 1)
                }
                override def close(): Unit = {
                  super.close()
                  closed = true
                  throw primary
                }
              },
            path => Files.delete(path),
          )
        }
        failure.error.code shouldBe CliFileErrorCode.FileWriteFailed
        failure.error.path shouldBe Some(root.resolve("model.ttl").toString)
        failure.error.partialPath shouldBe None
        failure.getCause should be theSameInstanceAs primary
        closed shouldBe true
        Files.exists(root.resolve("model.ttl"), NOFOLLOW_LINKS) shouldBe false
      }
    }

    "retain the primary write failure and partial path when closing and deletion also fail" in {
      withDirectory { root =>
        val primary = new IOException("Write failed")
        val closing = new IOException("Close failed")
        val cleanup = new IOException("Delete failed")
        val failure = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
            Path.of("model.ttl"),
            _ => Array[Byte](1, 2, 3),
            path =>
              new FilterOutputStream(openFile(path)) {
                override def write(bytes: Array[Byte]): Unit = {
                  out.write(bytes, 0, 1)
                  throw primary
                }
                override def close(): Unit = {
                  super.close()
                  throw closing
                }
              },
            _ => throw cleanup,
          )
        }
        failure.error.code shouldBe CliFileErrorCode.FileWriteFailed
        failure.error.partialPath shouldBe Some(root.resolve("model.ttl").toString)
        failure.error.path shouldBe Some(root.resolve("model.ttl").toString)
        failure.error.message should include("Remove the incomplete file")
        failure.error.message should include("before retrying")
        failure.error.message should include("Write failed")
        failure.getCause should be theSameInstanceAs primary
        primary.getSuppressed.toSeq should contain allOf (closing, cleanup)
        Files.readAllBytes(root.resolve("model.ttl")).toSeq shouldBe Seq[Byte](1)
      }
    }
  }
}
