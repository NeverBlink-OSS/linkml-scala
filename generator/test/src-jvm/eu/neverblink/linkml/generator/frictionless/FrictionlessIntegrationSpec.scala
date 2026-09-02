package eu.neverblink.linkml.generator.frictionless

import com.github.plokhotnyuk.jsoniter_scala.core.{WriterConfig, writeToString}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import os.Path

class FrictionlessIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  val cwd: Path = Option(System.getenv("MILL_WORKSPACE_ROOT"))
    .map(Path(_))
    .getOrElse(os.pwd)
  val frictionless: Path = cwd / ".venv" / "bin" / "frictionless"

  override val globalEnable: Boolean =
    os.call((frictionless, "--version"), check = false).exitCode == 0 || System.getenv("CI") != null

  override val skipInstances: Map[(String, String), String] = Map(
    ("typeDesignator", "unknownType") ->
      "LNK-101/LNK-102: not yet implemented, so unknown types get accepted",
  )

  lazy val modelDir: os.Path = os.temp.dir()
  lazy val dataDir: os.Path = os.temp.dir()
  lazy val packageDir: os.Path = os.temp.dir()

  "FrictionlessGenerator" should {
    for entry <- ModelCatalogue.all do
      s"work for model '${entry.name}'" when {
        lazy val generator = FrictionlessGenerator(using entry.model)

        // The catalogue's CSVs are instances of the tree root, so they are checked against the
        // tree root's own table schema rather than the package as a whole.
        // TODO LNK-197: add test cases for multiple tables and foreign keys
        lazy val tableSchemaPath = {
          val root = entry.model.treeRoot.getOrElse(
            throw RuntimeException(s"model '${entry.name}' has no tree root"),
          )
          val json = writeToString(
            generator.tableSchema(root)(using FrictionlessGenerator.Options()),
            WriterConfig.withIndentionStep(2),
          )
          val path = modelDir / (entry.name + ".json")
          os.write(path, json)
          path
        }

        "the whole data package" in {
          processSkip(entry.name, "")
          val tables = generator.generate().resources.map(resource =>
            resource.path -> (resource.schema match {
              case SchemaRef.Inline(table) => table
              case other => fail(s"expected an inline schema, got $other")
            }),
          )
          assume(
            tables.forall((_, table) => table.fields.nonEmpty),
            "a class in this model has no slots, so its table would have no columns",
          )

          val dir = packageDir / entry.name
          generator.generateFiles().foreach((name, content) =>
            os.write.over(dir / os.SubPath(name), content, createFolders = true),
          )
          // Create dummy CSV files for each table, so that frictionless validate can run without error.
          tables.foreach((path, table) =>
            os.write.over(
              dir / os.SubPath(path),
              table.fields.map(_.name).mkString("", ",", "\n"),
              createFolders = true,
            ),
          )
          os.call((frictionless, "validate", "--trusted", dir / "datapackage.json"))
        }

        for valid <- entry.validInstances.filter(_.csv.isDefined) do {
          s"valid instance '${valid.name}'" in {
            processSkip(entry, valid)
            val dataPath = dataDir / entry.name / "valid" / valid.name / "data.csv"
            os.write(dataPath, valid.csv.get, createFolders = true)
            os.call(
              (
                frictionless,
                "validate",
                "--trusted",
                "--skip-errors",
                "blank-row",
                "--schema",
                tableSchemaPath,
                dataPath,
              ),
            )
          }
        }
        for invalid <- entry.invalidInstances.filter(_.csv.isDefined) do {
          s"invalid instance '${invalid.name}'" in {
            processSkip(entry, invalid)
            val dataPath = dataDir / entry.name / "invalid" / invalid.name / "data.csv"
            os.write(dataPath, invalid.csv.get, createFolders = true)
            val result = os.call(
              (
                frictionless,
                "validate",
                "--trusted",
                // Frictionless seems to override missing required field errors with blank-row errors
                // We need to accept blank rows - they are meaningful in linkml semantics.
                // "--skip-errors", "blank-row",
                "--schema",
                tableSchemaPath,
                dataPath,
              ),
              check = false,
            )
            result.exitCode should not be 0
          }
        }
      }
  }
}
