package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.validation.SchemaImportError
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.Comparator
import scala.util.control.NonFatal

class FileSystemImporterSpec extends AnyWordSpec, Matchers, Inside {
  private def schema(name: String, imports: String*): String =
    s"id: https://neverblink.eu/linkml/importer/$name/\nname: $name\n" +
      imports.map(i => s"  - $i\n").mkString(if (imports.isEmpty) "" else "imports:\n", "", "")

  private def withDir(test: Path => Any): Unit = {
    val dir = Files.createTempDirectory("linkml-importer")
    try test(dir)
    finally
      Files.walk(dir).sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
  }

  private def write(file: Path, text: String): Path = {
    Files.createDirectories(file.getParent)
    Files.write(file, text.getBytes(UTF_8))
  }

  /** Make a link with `make`, cancelling the test where the file system or user cannot. */
  private def link(make: => Path): Path =
    try make
    catch {
      case ex if NonFatal(ex) => cancel(s"Cannot create a link here: $ex")
    }

  private def loadedNames(root: Path): Seq[String] =
    inside(SchemaView.loadSchemas(root.toString)) { case Right(schemas) => schemas.map(_.name) }

  "FileSystemImporter" should {
    "load a schema once when it is also imported through a symbolic link to the file" in withDir {
      dir =>
        val target = write(dir.resolve("real/c.yaml"), schema("c"))
        Files.createDirectories(dir.resolve("links"))
        link(Files.createSymbolicLink(dir.resolve("links/c.yaml"), target))
        val root = write(dir.resolve("b.yaml"), schema("b", "real/c", "links/c"))
        loadedNames(root) shouldBe Seq("b", "c")
    }

    "load a schema once when it is also imported through a symbolic link to its directory" in
      withDir { dir =>
        write(dir.resolve("real/c.yaml"), schema("c"))
        link(Files.createSymbolicLink(dir.resolve("alias"), dir.resolve("real")))
        val root = write(dir.resolve("b.yaml"), schema("b", "real/c", "alias/c"))
        loadedNames(root) shouldBe Seq("b", "c")
      }

    "load a schema once when it is also imported through a hard link" in withDir { dir =>
      val target = write(dir.resolve("c.yaml"), schema("c"))
      link(Files.createLink(dir.resolve("c-hard.yaml"), target))
      val root = write(dir.resolve("b.yaml"), schema("b", "c", "c-hard"))
      loadedNames(root) shouldBe Seq("b", "c")
    }

    "load both of two separate files with the same content" in withDir { dir =>
      write(dir.resolve("c1.yaml"), schema("c"))
      write(dir.resolve("c2.yaml"), schema("c"))
      val root = write(dir.resolve("b.yaml"), schema("b", "c1", "c2"))
      loadedNames(root) shouldBe Seq("b", "c", "c")
    }

    "find a .yml file through an import that asks for .yaml or no extension" in withDir { dir =>
      write(dir.resolve("c.yml"), schema("c"))
      val root = write(dir.resolve("b.yml"), schema("b", "c", "c.yaml", "c.yml"))
      loadedNames(dir.resolve("b")) shouldBe Seq("b", "c")
      loadedNames(root) shouldBe Seq("b", "c")
    }

    "still report an import of a missing file" in withDir { dir =>
      val root = write(dir.resolve("b.yaml"), schema("b", "missing"))
      inside(SchemaView.loadSchemas(root.toString)) { case Left(issue) =>
        issue shouldBe a[SchemaImportError]
        SchemaIssues.description(issue.asInstanceOf[SchemaImportError].infer()) should
          include("missing.yaml")
      }
    }
  }
}
