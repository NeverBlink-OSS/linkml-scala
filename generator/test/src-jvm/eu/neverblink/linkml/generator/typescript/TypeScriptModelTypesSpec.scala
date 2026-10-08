package eu.neverblink.linkml.generator.typescript

import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Checks that the TypeScript types the npm package ships for values that follow a LinkML model
  * (the validation report and the build info) are in sync with the models they are generated from.
  *
  * It reads the repository's own files, so it needs `MILL_WORKSPACE_ROOT`.
  */
class TypeScriptModelTypesSpec extends AnyWordSpec, Matchers {

  private val repoRoot: Option[os.Path] =
    sys.env.get("MILL_WORKSPACE_ROOT").map(os.Path(_)).filter(os.exists)

  /** (model, committed TypeScript) */
  private val generated = Seq(
    ("model/issue-types.yaml", "generator/npm/validation-report.d.ts"),
    ("model/build-info.yaml", "generator/npm/build-info.d.ts"),
  )

  "The npm package's model types" should {
    for (model, file) <- generated do
      s"match $model in $file" in {
        val root = repoRoot.getOrElse(cancel("MILL_WORKSPACE_ROOT is not set"))
        given SchemaView =
          SchemaIssues.orThrow(
            SchemaView.loadSchemaViewFromUri((root / os.RelPath(model)).toString),
          )
        withClue(s"$file is out of date, run ./mill regenerate: ") {
          os.read(root / os.RelPath(file)) shouldBe TypeScriptGenerator().serialize()
        }
      }
  }
}
