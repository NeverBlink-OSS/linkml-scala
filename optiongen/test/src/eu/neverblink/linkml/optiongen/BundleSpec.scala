package eu.neverblink.linkml.optiongen

import org.scalatest.Inspectors.forEvery
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

class BundleSpec extends AnyWordSpec, Matchers {
  "Generation commands" should {
    "detect schema changes and generated drift without changing destinations during checks" in {
      import GenerationFixture.*
      GenerationFixture.withFixture { fixture =>
        val original = fixture.destinations
        fixture.render()
        fixture.destinations shouldBe original
        fixture.stagedFiles.keySet shouldBe outputs
        fixture.command("apply")
        fixture.command("check")
        val baseline = fixture.destinations

        fixture.updateSchema(
          replaceOnce(
            fixture.schemaText,
            "description: Options for generating JSON Schema.",
            "description: Changed JSON Schema description.",
          ),
        )
        fixture.render()
        fixture.destinations shouldBe baseline
        def staleFiles: Set[String] = {
          val error = intercept[IllegalArgumentException](fixture.command("check"))
          error.getMessage should include("Generated option files are stale:")
          error.getMessage.linesIterator.drop(1).toSet
        }
        val affected = Set(core("json_schema"), python, native, js, tsOutput, reference, npm)
        staleFiles shouldBe affected
        fixture.destinations shouldBe baseline
        fixture.command("apply")
        fixture.command("check")
        val applied = fixture.destinations
        applied.filter { case (path, contents) =>
          baseline(path) != contents
        }.keySet shouldBe affected
        val generated = fixture.stagedFiles
        fixture.render()
        fixture.stagedFiles shouldBe generated
        fixture.command("apply")
        fixture.destinations shouldBe applied

        val target = fixture.root.resolve(core("json_schema"))
        Files.writeString(
          target,
          replaceOnce(
            Files.readString(target),
            "open: Boolean = false,",
            "open: Boolean = true,",
          ),
        )
        val drifted = fixture.destinations
        fixture.render()
        staleFiles shouldBe Set(core("json_schema"))
        fixture.destinations shouldBe drifted
        fixture.command("apply")
        val handwritten = fixture.root.resolve(cli)
        Files.writeString(
          handwritten,
          "// Another handwritten edit.\n" + Files.readString(handwritten),
        )
        val edited = fixture.destinations
        fixture.render()
        fixture.command("check")
        fixture.destinations shouldBe edited
      }
    }

    "reject empty, incomplete, and modified bundles before changing any source" in {
      val root = Files.createTempDirectory("optiongen-bundle-")
      try {
        val bundle = root.resolve("bundle")
        val targets = Entrypoints.all.take(2).map(entry => root.resolve(entry.source)).toVector
        targets.foreach { path =>
          Files.createDirectories(path.getParent)
          Files.writeString(path, "original")
        }
        val planned = targets.map(path => RegionWriter.complete(path, "generated"))
        def command(name: String): Unit =
          Main.run(Vector(name, "--root", root.toString, "--bundle", bundle.toString))
        val mutations: Vector[() => Unit] = Vector(
          () => { Files.writeString(bundle.resolve("manifest.tsv"), ""); () },
          () => {
            val manifest = bundle.resolve("manifest.tsv")
            Files.writeString(
              manifest,
              Files.readString(manifest).linesIterator.toVector.dropRight(1).mkString(
                "",
                "\n",
                "\n",
              ),
            )
            ()
          },
          () => {
            Files.writeString(
              bundle.resolve("files").resolve(Entrypoints.all.head.source),
              "modified exterior",
            );
            ()
          },
        )
        forEvery(mutations.zipWithIndex) { (mutate, index) =>
          forEvery(Vector("check", "apply")) { name =>
            targets.foreach(Files.writeString(_, "original"))
            Main.writeBundle(root, bundle, planned)
            mutate()
            withClue(s"$name corrupt bundle $index: ") {
              intercept[IllegalArgumentException](command(name)).getMessage should include("bundle")
            }
            targets.map(Files.readString(_)) shouldBe Vector("original", "original")
          }
        }
        Main.writeBundle(root, bundle, planned)
        intercept[IllegalArgumentException](command("check")).getMessage should include("stale")
        command("apply")
        command("check")
        targets.map(Files.readString(_)) shouldBe Vector("generated", "generated")
      } finally {
        val paths = Files.walk(root)
        try paths.iterator().asScala.toVector.reverse.foreach(Files.delete(_))
        finally paths.close()
      }
    }
  }
}
