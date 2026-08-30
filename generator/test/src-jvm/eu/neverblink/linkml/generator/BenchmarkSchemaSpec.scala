package eu.neverblink.linkml.generator

import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import io.circe.parser.parse as parseJson
import org.eclipse.rdf4j.rio.{RDFFormat, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml

import java.io.StringReader

/** End-to-end smoke test over the real-world schemas in the benchmark dataset:
  * https://github.com/NeverBlink-labs/linkml-benchmark-schemas
  *
  * For every dataset directory it loads `main.yaml` and runs the schema through every generator,
  * asserting that the output is well-formed in its target format.
  *
  * The dataset is not vendored into this repo. When run via mill, the `generator.jvm.test`
  * `benchmarkSchemas` task fetches it and points `LINKML_BENCHMARK_SCHEMAS` at it.
  */
class BenchmarkSchemaSpec extends AnyWordSpec, Matchers {
  import BenchmarkSchemaSpec.*

  private def assertParsesAsJson(name: String, s: String): Unit = {
    withClue("output is empty: ") { s.trim should not be empty }
    parseJson(s) match {
      case Right(_) => ()
      case Left(err) =>
        val path = os.Path(s"$name.json", os.pwd)
        os.write(path, s)
        fail(s"output did not parse as JSON: ${err.message}\noutput stored in $path")
    }
  }

  private def assertParsesAsRdf(rdf: String): Unit =
    withClue(s"output did not parse as N-Triples:\n$rdf\n") {
      noException should be thrownBy Rio.parse(StringReader(rdf), RDFFormat.NTRIPLES)
    }

  private def assertParsesAsYaml(s: String): Unit = {
    withClue("output is empty: ") { s.trim should not be empty }
    parseYaml(s) match {
      case Right(_) => ()
      case Left(err) => fail(s"output did not parse as YAML: $err\n$s")
    }
  }

  if datasets.isEmpty then
    "The benchmark schema dataset" should {
      "be available for generation tests" in {
        cancel(
          s"linkml-benchmark-schemas checkout not found at '$modelsDir'. " +
            "Clone it next to this repo " +
            "(git clone https://github.com/NeverBlink-labs/linkml-benchmark-schemas.git) " +
            "or set the LINKML_BENCHMARK_SCHEMAS environment variable to its path.",
        )
      }
    }
  else
    "generators" should {
      for dataset <- datasets do {
        val name = dataset.last
        s"produce well-formed output for benchmark schema '$name'" when {
          lazy val sv: SchemaView =
            SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri((dataset / "main.yaml").toString))

          "JSON Schema output parses as JSON" in {
            assume(!skip.contains((name, "json-schema")), skip.getOrElse((name, "json-schema"), ""))
            assertParsesAsJson(name, JsonSchemaGenerator(using sv).serialize())
          }

          "SHACL output parses as RDF" in {
            assume(!skip.contains((name, "shacl")), skip.getOrElse((name, "shacl"), ""))
            assertParsesAsRdf(ShaclGenerator(using sv).serialize())
          }

          "RDFS output parses as RDF" in {
            assume(!skip.contains((name, "rdfs")), skip.getOrElse((name, "rdfs"), ""))
            assertParsesAsRdf(RdfsGenerator(using sv).serialize())
          }

          "data package output parses as JSON" in {
            assume(
              !skip.contains((name, "frictionless")),
              skip.getOrElse((name, "frictionless"), ""),
            )
            // Every class becomes a table, so unlike the other generators this one does not care
            // whether the schema declares a tree_root.
            assertParsesAsJson(name, FrictionlessGenerator(using sv).serialize())
          }

          "LinkML (YAML) output parses as YAML" in {
            assume(!skip.contains((name, "linkml-yaml")), skip.getOrElse((name, "linkml-yaml"), ""))
            assertParsesAsYaml(
              LinkMlGenerator(using sv).serialize(
                LinkMlGenerator.Options(outputFormat = LinkMlGenerator.OutputFormat.yaml),
              ),
            )
          }

          "LinkML (JSON) output parses as JSON" in {
            assume(!skip.contains((name, "linkml-json")), skip.getOrElse((name, "linkml-json"), ""))
            assertParsesAsJson(
              name,
              LinkMlGenerator(using sv).serialize(
                LinkMlGenerator.Options(outputFormat = LinkMlGenerator.OutputFormat.json),
              ),
            )
          }

          "ER diagram output is a well-formed Mermaid document" in {
            assume(!skip.contains((name, "er-diagram")), skip.getOrElse((name, "er-diagram"), ""))
            val diagram =
              ErDiagramGenerator(using sv).serialize(ErDiagramGenerator.Options(PruningMode.skip))
            diagram should include("erDiagram")
            withClue("output has no entities: ") {
              diagram.linesIterator.count(_.startsWith("  ")) should be > 0
            }
          }

          "Scala output is non-empty" in {
            assume(!skip.contains((name, "scala")), skip.getOrElse((name, "scala"), ""))
            val files = ScalaGenerator(using sv).generate(
              ScalaGenerator.Options("eu.neverblink.linkml.generated"),
            ).toSeq
            files should not be empty
            files.foreach { case (fileName, contents) =>
              withClue(s"generated Scala file '$fileName' is empty: ") {
                contents.trim should not be empty
              }
            }
          }
        }
      }
    }
}

object BenchmarkSchemaSpec {

  private val repoRoot: os.Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path(_)).getOrElse(os.pwd)

  /** Location of the linkml-benchmark-schemas checkout. */
  private val modelsDir: os.Path =
    Option(System.getenv("LINKML_BENCHMARK_SCHEMAS"))
      .filter(_.nonEmpty)
      .map(os.Path(_, os.pwd))
      .getOrElse(repoRoot / os.up / "linkml-benchmark-schemas")

  private val datasets: Seq[os.Path] =
    if os.exists(modelsDir) && os.isDir(modelsDir) then
      os.list(modelsDir)
        .filter(os.isDir)
        .filter(dir => os.exists(dir / "main.yaml"))
        .sortBy(_.last)
    else Seq.empty

  /** Map of (dataset name, generator id) -> reason, for skipping known-failing combinations.
    */
  private val skip: Map[(String, String), String] = Map(
    // A generated Scala file is empty.
    ("nmdc_microbiome", "scala") -> "Known bug: a generated Scala file is empty",
    "nmdc_microbiome" -> "json-schema" -> "TODO LNK-167",
    "nmdc_microbiome" -> "frictionless" -> "TODO LNK-167",
    "nmdc_microbiome" -> "shacl" -> "TODO LNK-167",
    "nmdc_microbiome" -> "rdfs" -> "TODO LNK-167",
    "nmdc_microbiome" -> "linkml-yaml" -> "TODO LNK-167",
    "nmdc_microbiome" -> "linkml-json" -> "TODO LNK-167",
    "nmdc_microbiome" -> "er-diagram" -> "TODO LNK-167",
  )
}
