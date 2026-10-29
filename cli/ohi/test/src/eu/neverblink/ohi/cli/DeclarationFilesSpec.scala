package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.declaration.Declaration
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import java.io.{FilterOutputStream, IOException, OutputStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{AtomicMoveNotSupportedException, Files, Path}
import java.nio.file.StandardOpenOption.{CREATE_NEW, WRITE}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class DeclarationFilesSpec extends AnyWordSpec, Matchers {
  private val emptyDeclaration = Declaration(1, Seq.empty)
  private val yaml = "format_version: 1\nselections: []\n"
  private val previous = Array[Byte](71, 72, 0, -1)

  private def withDirectory(test: Path => Unit): Unit = {
    val dir = os.temp.dir(prefix = "ohi-declaration-")
    try test(dir.toNIO)
    finally os.remove.all(dir)
  }

  private def entries(root: Path): Seq[String] = os.list(os.Path(root)).map(_.last).sorted.toSeq
  private def openNew(path: Path): OutputStream = Files.newOutputStream(path, CREATE_NEW, WRITE)

  "DeclarationFiles.read" should {
    "report malformed UTF-8 at its supplied path without changing bytes" in {
      withDirectory { root =>
        val path = root.resolve("ohi.yaml")
        val bytes = "# ".getBytes(UTF_8) ++ Array[Byte](-61, 40) ++ s"\n$yaml".getBytes(UTF_8)
        Files.write(path, bytes)
        val failure = intercept[ProjectException](DeclarationFiles.read(path))
        failure.code shouldBe ProjectErrorCode.InvalidDeclaration
        failure.getMessage should include(path.toString)
        Files.readAllBytes(path).toSeq shouldBe bytes.toSeq
      }
    }
  }

  "DeclarationFiles.create" should {
    "preserve a target that appears at exclusive creation without deleting it" in {
      withDirectory { root =>
        val path = root.resolve("ohi.yaml")
        val failure = intercept[ProjectException] {
          DeclarationFiles.create(
            path,
            emptyDeclaration,
            target => {
              Files.write(target, previous)
              openNew(target)
            },
            target => Files.delete(target),
          )
        }
        failure.code shouldBe ProjectErrorCode.StorageError
        Files.exists(path) shouldBe true
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        entries(root) shouldBe Seq("ohi.yaml")
      }
    }

    "retain the primary failure and partial path when writing and cleanup fail" in {
      withDirectory { root =>
        val path = root.resolve("ohi.yaml")
        val primary = new IOException("write failed")
        val cleanup = new IOException("delete failed")
        val failure = intercept[ProjectException] {
          DeclarationFiles.create(
            path,
            emptyDeclaration,
            target =>
              new FilterOutputStream(openNew(target)) {
                override def write(bytes: Array[Byte]): Unit = {
                  out.write(bytes, 0, 1)
                  throw primary
                }
              },
            _ => throw cleanup,
          )
        }
        failure.code shouldBe ProjectErrorCode.StorageError
        failure.getCause should be theSameInstanceAs primary
        primary.getSuppressed.toSeq should contain(cleanup)
        failure.getMessage should include(path.toString)
        failure.getMessage should include("Inspect or remove")
        failure.getMessage should include("before retrying")
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](35)
        entries(root) shouldBe Seq("ohi.yaml")
      }
    }
  }

  "DeclarationFiles.write" should {
    "preserve prior bytes and remove the sibling stage when atomic replacement is unavailable" in {
      withDirectory { root =>
        val path = root.resolve("ohi.yaml")
        Files.write(path, previous)
        val primary = new AtomicMoveNotSupportedException("stage", path.toString, "unsupported")
        val failure = intercept[ProjectException] {
          DeclarationFiles.write(
            path,
            emptyDeclaration,
            stage => Files.newOutputStream(stage),
            (stage, target) => {
              stage.getParent shouldBe root
              target shouldBe path
              Files.readString(
                stage,
              ) shouldBe "# Ontologies used by this project.\n# Docs: https://ohi.dev/docs/\n" + yaml
              Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
              throw primary
            },
            stage => Files.delete(stage),
          )
        }
        failure.code shouldBe ProjectErrorCode.StorageError
        failure.getCause should be theSameInstanceAs primary
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        entries(root) shouldBe Seq("ohi.yaml")
      }
    }

    "preserve prior bytes and remove the sibling stage after a write failure" in {
      withDirectory { root =>
        val path = root.resolve("ohi.yaml")
        Files.write(path, previous)
        val primary = new IOException("write failed")
        val failure = intercept[ProjectException] {
          DeclarationFiles.write(
            path,
            emptyDeclaration,
            stage =>
              new FilterOutputStream(Files.newOutputStream(stage)) {
                override def write(bytes: Array[Byte]): Unit = {
                  out.write(bytes, 0, 1)
                  throw primary
                }
              },
            (_, _) => fail("replacement must not run"),
            stage => Files.delete(stage),
          )
        }
        failure.code shouldBe ProjectErrorCode.StorageError
        failure.getCause should be theSameInstanceAs primary
        Files.readAllBytes(path).toSeq shouldBe Seq[Byte](71, 72, 0, -1)
        entries(root) shouldBe Seq("ohi.yaml")
      }
    }
  }
}
