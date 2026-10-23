package eu.neverblink.ohi.model

import eu.neverblink.linkml.runtime.Uri
import eu.neverblink.ohi.model.generated.lock.{Format, Lock, LockedFile}
import java.io.{FilterOutputStream, IOException}
import java.nio.charset.StandardCharsets.{UTF_8, UTF_16LE}
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import scala.jdk.CollectionConverters.*
import scala.util.Using

class LockFilesSpec extends AnyWordSpec, Matchers {
  private val emptyLock = Lock(Seq.empty, 1, Seq.empty)
  private val emptyJson = """{"format_version":1,"selections":[],"files":[]}"""
  private val previous = Array[Byte](71, 72, 0, -1)

  private def withDirectory(test: Path => Unit): Unit = {
    val dir = Files.createTempDirectory("ohi-lock-")
    try test(dir)
    finally
      Using.resource(Files.walk(dir))(_.iterator().asScala.toSeq)
        .sortBy(_.getNameCount).reverse.foreach(Files.delete)
  }

  private def entries(root: Path): Seq[String] =
    Using.resource(Files.list(root))(_.iterator().asScala.map(_.getFileName.toString).toSeq.sorted)

  private def assertFailure(
      failure: LockException,
      path: Path,
      code: String,
      operation: String,
  ): Unit = {
    val data = ujson.read(LockErrorJson.encode(failure.error))
    data("code").str shouldBe code
    data("operation").str shouldBe operation
    data("path").str shouldBe path.toString
    data.obj.keySet shouldBe Set("code", "operation", "path", "message")
    data("message").str should not be empty
  }

  "LockFiles.write" should {
    "create a complete UTF-8 record without a BOM or leftover staging files" in {
      withDirectory { root =>
        val path = root.resolve("chosen.json")
        LockFiles.write(path, emptyLock)
        val bytes = Files.readAllBytes(path)
        bytes.take(3).toSeq should not be Seq[Byte](-17, -69, -65)
        ujson.read(new String(bytes, UTF_8)) shouldBe
          ujson.Obj("format_version" -> 1, "selections" -> ujson.Arr(), "files" -> ujson.Arr())
        entries(root) shouldBe Seq("chosen.json")
      }
    }

    "replace the entire old invalid record without reading or appending it" in {
      withDirectory { root =>
        val path = root.resolve("chosen.json")
        Files.write(path, previous)
        LockFiles.write(path, emptyLock)
        ujson.read(Files.readString(path)) shouldBe
          ujson.Obj("format_version" -> 1, "selections" -> ujson.Arr(), "files" -> ujson.Arr())
        entries(root) shouldBe Seq("chosen.json")
      }
    }

    "preserve prior bytes and avoid staging for a revision 2 file without a hash" in {
      withDirectory { root =>
        val path = root.resolve("./chosen.json")
        Files.write(path, previous)
        val invalid = Lock(
          Seq(LockedFile(Uri("urn:release:z"), Format.OwlTurtle, Uri("urn:file:z"))),
          2,
          Seq.empty,
        )
        val failure = intercept[LockException](LockFiles.write(path, invalid))
        assertFailure(failure, path, "INVALID_LOCK", "WRITE")
        failure.error.path.get should endWith("/./chosen.json")
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        entries(root) shouldBe Seq("chosen.json")
      }
    }

    "refuse an absent parent without creating directories" in {
      withDirectory { root =>
        val path = root.resolve("missing/chosen.json")
        val failure = intercept[LockException](LockFiles.write(path, emptyLock))
        assertFailure(failure, path, "STORAGE_ERROR", "WRITE")
        failure.getCause should not be null
        entries(root) shouldBe empty
      }
    }

    "replace the final symlink while preserving its referent bytes" in {
      withDirectory { root =>
        val referent = root.resolve("referent.json")
        val path = root.resolve("chosen.json")
        Files.write(referent, previous)
        Files.createSymbolicLink(path, Path.of("referent.json"))
        LockFiles.write(path, emptyLock)
        Files.isSymbolicLink(path) shouldBe false
        Files.readAllBytes(referent).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        ujson.read(Files.readString(path)) shouldBe
          ujson.Obj("format_version" -> 1, "selections" -> ujson.Arr(), "files" -> ujson.Arr())
        entries(root) shouldBe Seq("chosen.json", "referent.json")
      }
    }

    "remove its stage after an opening failure and preserve the destination" in {
      withDirectory { root =>
        val path = root.resolve("chosen.json")
        Files.write(path, previous)
        val primary = new IOException("open failed")
        val failure = intercept[LockException] {
          LockFiles.write(
            path,
            emptyLock,
            _ => throw primary,
            (_, _) => fail("replacement must not run"),
            stage => Files.delete(stage),
          )
        }
        assertFailure(failure, path, "STORAGE_ERROR", "WRITE")
        failure.getCause should be theSameInstanceAs primary
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        entries(root) shouldBe Seq("chosen.json")
      }
    }

    for phase <- Seq("write", "close") do
      s"preserve prior bytes and remove the sibling stage after a $phase failure" in {
        withDirectory { root =>
          val path = root.resolve("chosen.json")
          Files.write(path, previous)
          val primary = new IOException(s"$phase failed")
          var closed = false
          val failure = intercept[LockException] {
            LockFiles.write(
              path,
              emptyLock,
              stage => {
                stage.getParent shouldBe root
                stage should not be path
                new FilterOutputStream(Files.newOutputStream(stage)) {
                  override def write(bytes: Array[Byte]): Unit = {
                    out.write(bytes, 0, 1)
                    if phase == "write" then throw primary
                  }
                  override def close(): Unit = {
                    super.close()
                    closed = true
                    if phase == "close" then throw primary
                  }
                }
              },
              (_, _) => fail("replacement must not run"),
              stage => Files.delete(stage),
            )
          }
          assertFailure(failure, path, "STORAGE_ERROR", "WRITE")
          failure.getCause should be theSameInstanceAs primary
          closed shouldBe true
          Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
          entries(root) shouldBe Seq("chosen.json")
        }
      }

    "refuse unsupported atomic replacement without a truncating fallback" in {
      withDirectory { root =>
        val path = root.resolve("chosen.json")
        Files.write(path, previous)
        val primary = new AtomicMoveNotSupportedException("stage", path.toString, "unsupported")
        val failure = intercept[LockException] {
          LockFiles.write(
            path,
            emptyLock,
            stage => Files.newOutputStream(stage),
            (stage, destination) => {
              destination shouldBe path
              ujson.read(Files.readString(stage)) shouldBe
                ujson.Obj(
                  "format_version" -> 1,
                  "selections" -> ujson.Arr(),
                  "files" -> ujson.Arr(),
                )
              Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
              throw primary
            },
            stage => Files.delete(stage),
          )
        }
        assertFailure(failure, path, "STORAGE_ERROR", "WRITE")
        failure.getCause should be theSameInstanceAs primary
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        entries(root) shouldBe Seq("chosen.json")
      }
    }

    "preserve the write cause and report the retained stage when close and cleanup also fail" in {
      withDirectory { root =>
        val path = root.resolve("chosen.json")
        Files.write(path, previous)
        val primary = new IOException("write failed")
        val closing = new IOException("close failed")
        val cleanup = new IOException("delete failed")
        var retained: Option[Path] = None
        val failure = intercept[LockException] {
          LockFiles.write(
            path,
            emptyLock,
            stage => {
              retained = Some(stage)
              new FilterOutputStream(Files.newOutputStream(stage)) {
                override def write(bytes: Array[Byte]): Unit = {
                  out.write(bytes, 0, 1)
                  throw primary
                }
                override def close(): Unit = {
                  super.close()
                  throw closing
                }
              }
            },
            (_, _) => fail("replacement must not run"),
            _ => throw cleanup,
          )
        }
        assertFailure(failure, path, "STORAGE_ERROR", "WRITE")
        failure.getCause should be theSameInstanceAs primary
        primary.getSuppressed.toSeq should contain allOf (closing, cleanup)
        failure.error.message should include(retained.get.toString)
        failure.error.message should include("write failed")
        Files.readAllBytes(retained.get).toSeq shouldBe Seq[Byte](123)
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        entries(root) shouldBe Seq("chosen.json", retained.get.getFileName.toString).sorted
      }
    }
  }

  "LockFiles.read" should {
    "read a UTF-8 control and preserve its explicit empty collections" in {
      withDirectory { root =>
        val path = root.resolve("chosen.json")
        Files.write(path, emptyJson.getBytes(UTF_8))
        val lock = LockFiles.read(path)
        lock.formatVersion shouldBe 1
        lock.selections shouldBe empty
        lock.files shouldBe empty
        Files.readAllBytes(path).toSeq shouldBe emptyJson.getBytes(UTF_8).toSeq
        entries(root) shouldBe Seq("chosen.json")
      }
    }

    "report a missing file as a read storage error with supplied path spelling" in {
      withDirectory { root =>
        val path = root.resolve("./missing.json")
        val failure = intercept[LockException](LockFiles.read(path))
        assertFailure(failure, path, "STORAGE_ERROR", "READ")
        failure.error.path.get should endWith("/./missing.json")
        failure.getCause should not be null
        entries(root) shouldBe empty
      }
    }

    for (label, bytes) <- Seq(
        "malformed JSON" -> "{".getBytes(UTF_8),
        "malformed UTF-8" -> Array[Byte](-61, 40),
        "a leading UTF-8 BOM" -> (Array[Byte](-17, -69, -65) ++ emptyJson.getBytes(UTF_8)),
        "UTF-16LE JSON" -> emptyJson.getBytes(UTF_16LE),
      )
    do
      s"reject $label without changing the file" in {
        withDirectory { root =>
          val path = root.resolve("chosen.json")
          Files.write(path, bytes)
          val failure = intercept[LockException](LockFiles.read(path))
          assertFailure(failure, path, "INVALID_LOCK", "READ")
          failure.getCause should not be null
          Files.readAllBytes(path).toSeq shouldBe bytes.toSeq
          entries(root) shouldBe Seq("chosen.json")
        }
      }
  }
}
