package eu.neverblink.linkml.generator

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks that every generator is reachable from every binding.
  *
  * It reads the repository's own sources, so it needs to know where the checkout is;
  * `generator.jvm.test.forkEnv` passes `LINKML_REPO_ROOT`.
  */
class BindingCoverageSpec extends AnyWordSpec, Matchers {

  private val repoRoot: Option[os.Path] =
    sys.env.get("MILL_WORKSPACE_ROOT").map(os.Path(_)).filter(os.exists)

  /** Every generator in the generator module: a `*Generator.scala` declaring an `Options` case
    * class. The `Options` is what makes it a public generator rather than an internal helper.
    */
  private def generators(root: os.Path): Seq[String] = {
    val dir = root / "generator" / "src" / "eu" / "neverblink" / "linkml" / "generator"
    os.walk(dir)
      .filter(p => p.last.endsWith("Generator.scala"))
      .filter(p => os.read(p).contains("final case class Options("))
      .map(_.last.stripSuffix(".scala"))
      .sorted
  }

  /** The JavaScript facade's public methods that operate on a loaded schema, which is every
    * generator plus `lint`. Read from the facade because the JS names are not derivable from the
    * generator names.
    */
  private def jsMethods(root: os.Path): Seq[String] = {
    val facade = os.read(
      root / "generator" / "src-js" / "eu" / "neverblink" / "linkml" / "js" / "LinkMlJsApi.scala",
    )
    "def (\\w+)\\(\\s*schema: SchemaViewJs".r
      .findAllMatchIn(facade)
      .map(_.group(1))
      .toSeq
      .sorted
  }

  /** The commands the CLI actually registers. A `generate` command that exists but is not in this
    * list cannot be run, so the list is what matters rather than the file defining it.
    */
  private def cliCommands(root: os.Path): Set[String] = {
    val app = os.read(root / "cli" / "src" / "eu" / "neverblink" / "linkml" / "cli" / "App.scala")
    val block = "override def commands: Seq\\[Command\\[\\?\\]\\] = Seq\\(([^)]*)\\)".r
      .findFirstMatchIn(app)
      .map(_.group(1))
      .getOrElse(fail("could not find the command list in App.scala"))
    block.split(',').map(_.trim).filter(_.nonEmpty).toSet
  }

  /** The generator ids offered by the playground's target list. */
  private def playgroundTargets(root: os.Path): Set[String] =
    "id: \"(\\w+)\"".r
      .findAllMatchIn(os.read(root / "ui" / "targets.ts"))
      .map(_.group(1))
      .toSet

  "every generator" should {
    "be registered as a CLI command" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val registered = cliCommands(root)
      // The CLI names its commands after the generator, minus the suffix: ShaclGenerator -> Shacl.
      val missing = generators(root).map(_.stripSuffix("Generator")).filterNot(registered)
      withClue("add a Generate command and register it in App.scala: ")(missing shouldBe empty)
    }

    "be offered by the playground" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val targets = playgroundTargets(root)
      // The playground drives the JS API, so its target ids are the facade's method names.
      val missing = jsMethods(root).filterNot(targets)
      withClue("add a target to ui/targets.ts, then run ./mill uiCheck: ")(missing shouldBe empty)
    }

    "be listed in the shared entry-point table" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val table = os.read(root / "mill-build" / "src" / "Entrypoints.scala")
      val missing = generators(root).filterNot(name => table.contains(s"\"$name\""))
      withClue(
        "add a row to mill-build/src/Entrypoints.scala, then run ./mill bindings: ",
      )(missing shouldBe empty)
    }

    "be exposed by the JavaScript facade" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val facade =
        os.read(
          root / "generator" / "src-js" / "eu" / "neverblink" / "linkml" / "js" / "LinkMlJsApi.scala",
        )
      val missing = generators(root).filterNot(facade.contains)
      withClue("add a method to LinkMlJsApi.scala, then run ./mill uiTypes: ")(
        missing shouldBe empty,
      )
    }

    "be exposed by the native library's Scala facade" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val facade = os.read(
        root / "nativelib" / "src" / "eu" / "neverblink" / "linkml" / "nativelib" /
          "LinkMlNativeApi.scala",
      )
      val missing = generators(root).filterNot(facade.contains)
      withClue("add a method to LinkMlNativeApi.scala: ")(missing shouldBe empty)
    }

    "be exposed as a C entry point" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val generated = os.read(
        root / "nativelib" / "src" / "eu" / "neverblink" / "linkml" / "nativelib" /
          "LinkMlCGenerators.java",
      )
      // Generated from the table, so this catches a stale checked-in copy rather than a missing row.
      val exported = "@CEntryPoint\\(name = \"(\\w+)\"\\)".r.findAllMatchIn(generated).size
      withClue("run ./mill bindings and commit the result: ")(
        exported shouldBe generators(root).size,
      )
    }

    "be exposed as a Python method" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val generated = os.read(root / "python" / "linkml_scala" / "_generated.py")
      val methods = "\\n    def (\\w+)\\(".r.findAllMatchIn(generated).size
      withClue("run ./mill bindings and commit the result: ")(
        methods shouldBe generators(root).size,
      )
    }

    "be documented in the npm module's README" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val readme = os.read(root / "generator" / "npm" / "README.md")
      // Taken from the facade rather than derived from the generator names, because the JS spelling
      // is not mechanical: LinkMlGenerator is `linkml`, not `linkMl`. This also covers `lint`.
      val missing = jsMethods(root).filterNot(readme.contains)
      withClue("add it to the API table in generator/npm/README.md: ")(missing shouldBe empty)
    }

    "be documented in the Python bindings guide" in {
      val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
      val docs = os.read(root / "docs" / "python_bindings.md")
      // The doc lists the Python method names, so check those rather than the Scala class names.
      val pythonNames = "\\n    def (\\w+)\\(".r
        .findAllMatchIn(os.read(root / "python" / "linkml_scala" / "_generated.py"))
        .map(_.group(1))
        .toSeq
      val missing = pythonNames.filterNot(name => docs.contains(s"schema.$name("))
      withClue("add it to the Generating section of docs/python_bindings.md: ")(
        missing shouldBe empty,
      )
    }
  }
}
