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
        FileDelivery.target(url, directory.toString) shouldBe root.resolve("nested/model.ttl")
        Files.exists(root.resolve("nested")) shouldBe false
        Files.exists(root.resolve("unused")) shouldBe false
      }
    }

    for (segment, filename, behavior) <- Seq(
        (
          "energy%20model.ttl?revision=2#other.ttl",
          "energy model.ttl",
          "ignore query and fragment",
        ),
        ("caf%C3%A9+%252F.ttl", "café+%2F.ttl", "preserve plus and decode UTF-8 exactly once"),
      )
    do
      s"$behavior when deriving the decoded basename" in {
        withDirectory { root =>
          val supplied = s"https://files.example/$segment"
          val result = FileDelivery.save(
            supplied,
            root.toString,
            actual => {
              actual shouldBe supplied
              Array[Byte](42)
            },
          )
          result.url.original shouldBe supplied
          result.path shouldBe root.resolve(filename).toString
          Files.readAllBytes(root.resolve(filename)).toSeq shouldBe Seq[Byte](42)
        }
      }

    for (supplied, behavior) <- Seq(
        ("https://files.example/?id=123", "query-only URL"),
        ("https://files.example/.", "dot filename"),
        ("https://files.example/%2E%2E", "decoded parent filename"),
        ("https://files.example/one%2Ftwo.ttl", "encoded slash"),
        ("https://files.example/one%5Ctwo.ttl", "encoded backslash"),
        ("https://files.example/one%00two.ttl", "encoded NUL"),
        ("https://files.example/%zz", "unparseable URL"),
        ("urn:filename", "URL without a hierarchical path"),
      )
    do
      s"reject a $behavior before fetching or creating directories" in {
        withDirectory { root =>
          val directory = root.resolve("not-created")
          val error = intercept[CliFileException] {
            FileDelivery.save(supplied, directory.toString, _ => fail("fetch must not run"))
          }.error
          error.code shouldBe CliFileErrorCode.UnsupportedFilename
          error.url shouldBe supplied
          error.directory shouldBe directory.toString
          error.path shouldBe None
          error.partialPath shouldBe None
          Files.exists(directory) shouldBe false
          os.list(os.Path(root)) shouldBe empty
        }
      }
  }

  "file delivery" should {
    "fetch once before creating parents and preserve binary bytes without text conversion" in {
      withDirectory { root =>
        val directory = root.resolve("nested/output.ttl")
        var requests = Vector.empty[String]
        val result = FileDelivery.save(
          url,
          directory.toString,
          actual => {
            requests :+= actual
            Files.exists(root.resolve("nested")) shouldBe false
            Array[Byte](0, -1, 13, 10, -128, 42)
          },
        )
        requests shouldBe Vector("https://files.example/model.ttl?revision=2")
        result.url.original shouldBe "https://files.example/model.ttl?revision=2"
        result.path shouldBe directory.resolve("model.ttl").toString
        Files.readAllBytes(directory.resolve("model.ttl")).toSeq shouldBe
          Seq[Byte](0, -1, 13, 10, -128, 42)
      }
    }

    "create an empty file instead of treating an empty payload as a failed fetch" in {
      withDirectory { root =>
        val result = FileDelivery.save(url, root.toString, _ => Array.emptyByteArray)
        result.url.original shouldBe "https://files.example/model.ttl?revision=2"
        result.path shouldBe root.resolve("model.ttl").toString
        Files.isRegularFile(root.resolve("model.ttl")) shouldBe true
        Files.size(root.resolve("model.ttl")) shouldBe 0L
      }
    }

    "reject an existing non-directory destination before fetching and preserve its bytes" in {
      withDirectory { root =>
        val directory = root.resolve("existing-file")
        Files.write(directory, Array[Byte](71, 72))
        val error = intercept[CliFileException] {
          FileDelivery.save(url, directory.toString, _ => fail("fetch must not run"))
        }.error
        error.code shouldBe CliFileErrorCode.InvalidDestination
        error.directory shouldBe directory.toString
        error.path shouldBe Some(directory.resolve("model.ttl").toString)
        error.partialPath shouldBe None
        Files.readAllBytes(directory).toSeq shouldBe Seq[Byte](71, 72)
      }
    }

    for entry <- Seq("file", "directory", "dangling symbolic link") do
      s"refuse an existing target $entry before fetching without changing the entry" in {
        withDirectory { root =>
          val path = root.resolve("model.ttl")
          entry match {
            case "file" => Files.write(path, Array[Byte](71, 72))
            case "directory" => Files.createDirectory(path)
            case _ => Files.createSymbolicLink(path, Path.of("missing-target"))
          }
          val error = intercept[CliFileException] {
            FileDelivery.save(url, root.toString, _ => fail("fetch must not run"))
          }.error
          error.code shouldBe CliFileErrorCode.DestinationExists
          error.path shouldBe Some(path.toString)
          error.partialPath shouldBe None
          entry match {
            case "file" => Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72)
            case "directory" => Files.isDirectory(path) shouldBe true
            case _ => Files.readSymbolicLink(path) shouldBe Path.of("missing-target")
          }
        }
      }

    "preserve a real competing target created after preflight instead of overwriting it" in {
      withDirectory { root =>
        val path = root.resolve("model.ttl")
        val error = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
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
            url = Some(url),
          ),
        )
        val actual = intercept[CatalogException] {
          FileDelivery.save(url, directory.toString, _ => throw expected)
        }
        actual should be theSameInstanceAs expected
        actual.error.code shouldBe CatalogErrorCode.HttpError
        actual.error.operation shouldBe CatalogOperation.Get
        actual.error.status shouldBe Some(404)
        actual.error.url shouldBe Some("https://files.example/model.ttl?revision=2")
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

    "report target creation failure without attempting to delete an unowned file" in {
      withDirectory { root =>
        val primary = new IOException("Cannot open target")
        val failure = intercept[CliFileException] {
          FileDelivery.save(
            url,
            root.toString,
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

    for phase <- Seq("write", "close") do
      s"close and remove its own new file after a $phase failure without reporting success" in {
        withDirectory { root =>
          val primary = new IOException(s"$phase failed")
          var closed = false
          val failure = intercept[CliFileException] {
            FileDelivery.save(
              url,
              root.toString,
              _ => Array[Byte](1, 2, 3),
              path =>
                new FilterOutputStream(openFile(path)) {
                  override def write(bytes: Array[Byte]): Unit = {
                    out.write(bytes, 0, 1)
                    if phase == "write" then throw primary
                  }
                  override def close(): Unit = {
                    super.close()
                    closed = true
                    if phase == "close" then throw primary
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
