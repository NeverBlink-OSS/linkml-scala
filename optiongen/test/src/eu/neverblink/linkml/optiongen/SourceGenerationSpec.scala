package eu.neverblink.linkml.optiongen

import org.scalatest.Inspectors.forEvery
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.{Files, Path, StandardCopyOption}
import scala.jdk.CollectionConverters.*

class SourceGenerationSpec extends AnyWordSpec, Matchers {
  private val source =
    """package example
      |
      |object Example{
      |  // BEGIN GENERATED OPTIONS first
      |  val old = false
      |  // END GENERATED OPTIONS first
      |  // BEGIN GENERATED OPTIONS untouched
      |  val keep =  7
      |  // END GENERATED OPTIONS untouched
      |}
      |""".stripMargin
  private val allowed = Set("first", "untouched")
  private val replacement = Map("first" -> "val enabled:Boolean=true\n")
  private val formatterConfig = "version = 3.11.5\nrunner.dialect = scala3future\n"
  private val markdown =
    """# Generator options
      |
      |<!-- BEGIN GENERATED OPTIONS first -->
      |Old options.
      |<!-- END GENERATED OPTIONS first -->
      |
      |<!-- BEGIN GENERATED OPTIONS untouched -->
      |Keep this  spacing.
      |<!-- END GENERATED OPTIONS untouched -->
      |Handwritten ending.""".stripMargin.replace("\n", "\r\n")

  private def inDirectory(f: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("optiongen-test-")
    try f(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.reverse.foreach(path => Files.delete(path))
      finally paths.close()
    }
  }

  private def write(path: Path, content: String): Path = Files.writeString(path, content)

  "Source generation" should {
    "format owned interiors, preserve other bytes, and regenerate without changes" in inDirectory {
      directory =>
        val path = write(directory.resolve("Example.scala"), source)
        val config = write(directory.resolve(".scalafmt.conf"), formatterConfig)
        ScalaFormatter.withFormatter(config) { format =>
          val planned = RegionWriter.prepare(path, replacement, allowed, format)
          planned.content shouldBe source.replace(
            "  val old = false\n",
            "  val enabled: Boolean = true\n",
          )
          planned.before shouldBe RegionWriter.snapshot(path)
          RegionWriter.stale(Vector(planned)) shouldBe Vector(path)
          Files.readString(path) shouldBe source
          RegionWriter.publish(Vector(planned)) shouldBe Vector(path)
          RegionWriter.stale(Vector(planned)) shouldBe empty
          val repeated = RegionWriter.prepare(path, replacement, allowed, format)
          repeated.content shouldBe planned.content
          RegionWriter.publish(Vector(repeated)) shouldBe empty
        }
        val markdownPath = write(directory.resolve("options.md"), markdown)
        val markdownReplacement = Map("first" -> "Generated options.\n")
        val identityFormat: (Path, String) => String = (_, content) => content
        val plannedMarkdown =
          RegionWriter.prepare(markdownPath, markdownReplacement, allowed, identityFormat)
        plannedMarkdown.content shouldBe markdown.replace(
          "Old options.\r\n",
          "Generated options.\n",
        )
        RegionWriter.publish(Vector(plannedMarkdown)) shouldBe Vector(markdownPath)
        val repeatedMarkdown =
          RegionWriter.prepare(markdownPath, markdownReplacement, allowed, identityFormat)
        RegionWriter.publish(Vector(repeatedMarkdown)) shouldBe empty
    }

    "reject malformed ownership and formatter failures before changing destinations" in inDirectory {
      directory =>
        val path = write(directory.resolve("Example.scala"), source)
        val config = write(directory.resolve(".scalafmt.conf"), formatterConfig)
        val malformed = Vector(
          source.replace("  // BEGIN GENERATED OPTIONS first\n", ""),
          source + source,
          source.replace("  val old = false\n", "  // BEGIN GENERATED OPTIONS first\n"),
          source.replace("END GENERATED OPTIONS first", "END GENERATED OPTIONS untouched"),
          source.replace("OPTIONS untouched", "OPTIONS unknown"),
          source.replace("  // END GENERATED OPTIONS first\n", ""),
          markdown.replace(
            "<!-- END GENERATED OPTIONS first -->",
            "// END GENERATED OPTIONS first",
          ),
          markdown.replace("OPTIONS untouched -->", "OPTIONS unknown -->"),
          markdown + "\n<!-- BEGIN GENERATED OPTIONS first ->\n",
          source + "\n// BEGIN GENERATED OPTIONS unknown -->\n",
        )
        forEvery(malformed) { input =>
          intercept[IllegalArgumentException](RegionWriter.compose(input, replacement, allowed))
        }
        ScalaFormatter.withFormatter(config) { format =>
          intercept[IllegalArgumentException] {
            RegionWriter.prepare(path, Map("first" -> "val =\n"), allowed, format)
          }
        }
        intercept[IllegalArgumentException] {
          ScalaFormatter.withFormatter(directory.resolve("missing.conf"))(_(path, source))
        }
        val excluded = write(
          directory.resolve("excluded.conf"),
          formatterConfig + "project.excludePaths = [\"glob:**/Example.scala\"]\n",
        )
        intercept[IllegalArgumentException] {
          ScalaFormatter.withFormatter(excluded)(_(path, source))
        }
        Files.readString(path) shouldBe source
        val paths = Files.list(directory)
        try
          paths.iterator().asScala.map(_.getFileName.toString).toSet shouldBe
            Set("Example.scala", ".scalafmt.conf", "excluded.conf")
        finally paths.close()
    }

    "check every snapshot before writes and preserve failed destinations" in inDirectory {
      directory =>
        val first = write(directory.resolve("first.txt"), "first original")
        val second = write(directory.resolve("second.txt"), "second original")
        val planned = Vector(
          RegionWriter.complete(first, "first generated"),
          RegionWriter.complete(second, "second generated"),
        )
        write(second, "concurrent edit")
        intercept[IllegalStateException](RegionWriter.publish(planned))
        Files.readString(first) shouldBe "first original"
        Files.readString(second) shouldBe "concurrent edit"

        val created = directory.resolve("new.txt")
        RegionWriter.stale(Vector(RegionWriter.complete(created, "new"))) shouldBe Vector(created)
        Files.exists(created) shouldBe false
        RegionWriter.publish(Vector(RegionWriter.complete(created, "new"))) shouldBe Vector(created)
        Files.readString(created) shouldBe "new"

        write(second, "second original")
        val failure = intercept[java.io.IOException] {
          RegionWriter.publishWith(
            Vector(
              RegionWriter.complete(first, "first generated"),
              RegionWriter.complete(second, "second generated"),
            ),
            (temporary, destination) => {
              if destination == second then
                throw new java.io.IOException(
                  "replacement failed",
                  new java.nio.file.AccessDeniedException(second.toString),
                )
              Files.move(
                temporary,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
              )
              ()
            },
          )
        }
        failure.getMessage should include(first.toString)
        Main.failureMessage(failure) shouldBe
          s"Failed to publish $second. Already updated: $first\n" +
          "Caused by: java.io.IOException: replacement failed\n" +
          s"Caused by: java.nio.file.AccessDeniedException: $second"
        Files.readString(first) shouldBe "first generated"
        Files.readString(second) shouldBe "second original"
        val paths = Files.list(directory)
        try
          paths.iterator().asScala.map(_.getFileName.toString).toSet shouldBe
            Set("first.txt", "second.txt", "new.txt")
        finally paths.close()
    }

    "keep validation messages concise and include formatting causes" in {
      Main.failureMessage(new IllegalArgumentException("Invalid option")) shouldBe "Invalid option"
      Main.failureMessage(
        new IllegalArgumentException(
          "Example.scala: formatting failed",
          new IllegalArgumentException("expected identifier"),
        ),
      ) shouldBe "Example.scala: formatting failed\n" +
        "Caused by: java.lang.IllegalArgumentException: expected identifier"
      Main.failureMessage(new java.io.IOException()) shouldBe "java.io.IOException"
    }
  }
}
