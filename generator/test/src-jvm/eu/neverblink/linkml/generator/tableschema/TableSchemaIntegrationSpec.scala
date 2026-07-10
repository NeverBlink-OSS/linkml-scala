package eu.neverblink.linkml.generator.tableschema

import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import os.Path

class TableSchemaIntegrationSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  val frictionless: Path = os.pwd / ".venv" / "bin" / "frictionless"

  override val globalEnable: Boolean =
    os.call((frictionless, "--version")).exitCode == 0 || System.getenv("CI") != null

  lazy val modelDir: os.Path = os.temp.dir()
  lazy val dataDir: os.Path = os.temp.dir()

  "TableSchemaGenerator" should {
    for entry <- ModelCatalogue.all do
      s"work for model '${entry.name}'" when {
        lazy val tableSchemaPath = {
          val jsonStr = TableSchemaGenerator(using entry.model).serialize()
          val path = modelDir / (entry.name + ".json")
          os.write(path, jsonStr)
          path
        }
        for valid <- entry.validInstances.filter(_.csv.isDefined) do {
          s"valid instance '${valid.name}'" in {
            processSkip(entry, valid)
            val dataPath = dataDir / entry.name / valid.name / "data.csv"
            os.write(dataPath, valid.csv.get, createFolders = true)
            os.call(
              (frictionless, "validate", "--trusted", "--schema", tableSchemaPath, dataPath),
            )
          }
        }
        for invalid <- entry.invalidInstances.filter(_.csv.isDefined) do {
          s"invalid instance '${invalid.name}'" in {
            processSkip(entry, invalid)
            val dataPath = dataDir / entry.name / invalid.name / "data.csv"
            os.write(dataPath, invalid.csv.get, createFolders = true)
            val result = os.call(
              (frictionless, "validate", "--trusted", "--schema", tableSchemaPath, dataPath),
              check = false,
            )
            result.exitCode should not be 0
          }
        }
      }
  }
}
