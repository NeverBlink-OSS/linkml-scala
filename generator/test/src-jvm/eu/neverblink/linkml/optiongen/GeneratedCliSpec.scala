package eu.neverblink.linkml.optiongen

import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import io.circe.parser.parse as parseJson
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GeneratedCliSpec extends AnyWordSpec, Matchers {
  "A regenerated CLI" should {
    "use a renamed flag, its interface default and an explicit false value" in {
      val schemaSource = os.Path(
        sys.env.getOrElse(
          "OPTION_SCHEMA",
          fail("OPTION_SCHEMA must point to model/generator-options.yaml"),
        ),
      )
      val sourceRoot = schemaSource / os.up / os.up
      val root = os.temp.dir(prefix = "optiongen-cli-")
      try {
        val sources = Vector(
          "cli/src/eu/neverblink/linkml/cli/GenerateImpl.scala",
          "cli/src/eu/neverblink/linkml/cli/PruningOptions.scala",
        )
        for relative <- sources :+ ".scalafmt.conf" do
          os.copy(
            sourceRoot / os.SubPath(relative),
            root / os.SubPath(relative),
            createFolders = true,
          )
        val schema = root / "generator-options.yaml"
        os.copy(schemaSource, schema)

        val original =
          """                - selector: open
            |                  name: open
            |                  description: >-
            |                    Whether the generated JSON Schema should allow additionalProperties for classes.
            |                    Default: false""".stripMargin
        val changed =
          """                - selector: open
            |                  name: allowExtra
            |                  default: {kind: literal, value: true}
            |                  description: >-
            |                    Whether the generated JSON Schema should allow additionalProperties for classes.
            |                    Default: true""".stripMargin
        val before = os.read(schema)
        withClue("JSON Schema CLI fixture must match exactly once: ") {
          before.sliding(original.length).count(_ == original) shouldBe 1
        }
        os.write.over(schema, before.replace(original, changed))
        JsonSchemaGenerator.Options().open shouldBe false

        val bundle = root / "bundle"
        Main.run(
          Vector(
            "render",
            "--root",
            root.toString,
            "--schema",
            schema.toString,
            "--config",
            (root / ".scalafmt.conf").toString,
            "--output",
            bundle.toString,
            "--group",
            "cli",
          ),
        )
        Main.run(Vector("apply", "--root", root.toString, "--bundle", bundle.toString))

        val classes =
          GeneratedScala.compile(root, sources.map(relative => root / os.SubPath(relative)))

        val runtime = root / "runtime"
        os.makeDir(runtime)
        val input = runtime / "schema.yaml"
        os.write(
          input,
          """id: https://neverblink.eu/generated-cli-test/
            |name: generated_cli_test
            |default_range: string
            |imports:
            |  - linkml:types
            |classes:
            |  Root:
            |    tree_root: true
            |    attributes:
            |      name:
            |""".stripMargin,
        )
        def run(arguments: String*): os.CommandResult =
          GeneratedScala.run(
            classes,
            "eu.neverblink.linkml.cli.Main",
            Seq("generate", "json-schema") ++ arguments,
            runtime,
          )

        val help = run("--help")
        withClue(s"generated CLI help:\n${help.out.text()}\n${help.err.text()}\n") {
          help.exitCode shouldBe 0
          help.out.text() should include("--allow-extra")
          help.out.text() should not include "--open"
        }

        for (arguments, expected) <- Vector(
            Vector.empty[String] -> true,
            Vector("--allow-extra=false") -> false,
          )
        do {
          val result = run((arguments :+ input.toString)*)
          withClue(
            s"generated CLI ${arguments.mkString(" ")}:\n${result.out.text()}\n${result.err.text()}\n",
          ) {
            result.exitCode shouldBe 0
            parseJson(result.out.text()).flatMap(
              _.hcursor.downField("$defs").downField("Root").get[Boolean]("additionalProperties"),
            ) shouldBe Right(expected)
          }
        }

        val oldFlag = run("--open", input.toString)
        withClue(s"removed CLI flag:\n${oldFlag.out.text()}\n${oldFlag.err.text()}\n") {
          oldFlag.exitCode should not be 0
          oldFlag.err.text() should include("--open")
        }
      } finally os.remove.all(root)
    }
  }
}
