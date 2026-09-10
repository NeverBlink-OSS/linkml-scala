package eu.neverblink.linkml.generator

import com.networknt.schema.InputFormat
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.ossie.{OssieGenerator, OssieSchema}
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.scala.ScalaGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.generator.util.JsonOutputFormat
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import io.circe.parser.parse as parseJson
import org.eclipse.rdf4j.rio.{RDFFormat, Rio}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.virtuslab.yaml.parseYaml
import sangria.parser.{ParserConfig, QueryParser}
import sangria.schema.Schema

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
    withClue(s"output did not parse as Turtle:\n$rdf\n") {
      noException should be thrownBy Rio.parse(StringReader(rdf), RDFFormat.TURTLE)
    }

  private def assertParsesAsYaml(s: String): Unit = {
    withClue("output is empty: ") { s.trim should not be empty }
    parseYaml(s) match {
      case Right(_) => ()
      case Left(err) => fail(s"output did not parse as YAML: $err\n$s")
    }
  }

  private def assertIsGraphQlSchema(name: String, s: String): Unit = {
    withClue("output is empty: ") { s.trim should not be empty }
    val document = "type Query {\n  test: String\n}\n" + s
    try {
      Schema.buildFromAst(QueryParser.parse(document, ParserConfig()).get)
      ()
    } catch {
      case err: Throwable =>
        val path = os.Path(s"$name.graphql", os.pwd)
        os.write.over(path, document)
        fail(s"output is not a valid GraphQL schema: ${err.getMessage}\noutput stored in $path")
    }
  }

  private def assertIsOssieOntology(s: String, format: InputFormat): Unit = {
    withClue("output is empty: ") { s.trim should not be empty }
    withClue("output is not a valid Ossie ontology:\n") {
      OssieSchema.validate(s, format) shouldBe empty
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
                LinkMlGenerator.Options(outputFormat = JsonOutputFormat.yaml),
              ),
            )
          }

          "LinkML (JSON) output parses as JSON" in {
            assume(!skip.contains((name, "linkml-json")), skip.getOrElse((name, "linkml-json"), ""))
            assertParsesAsJson(
              name,
              LinkMlGenerator(using sv).serialize(
                LinkMlGenerator.Options(outputFormat = JsonOutputFormat.json),
              ),
            )
          }

          "GraphQL output is a well-formed GraphQL schema" in {
            assume(!skip.contains((name, "graphql")), skip.getOrElse((name, "graphql"), ""))
            assertIsGraphQlSchema(name, GraphQlGenerator(using sv).serialize())
          }

          "Ossie ontology (YAML) output validates against the Ossie ontology schema" in {
            assume(!skip.contains((name, "ossie-yaml")), skip.getOrElse((name, "ossie-yaml"), ""))
            assertIsOssieOntology(
              OssieGenerator(using sv).serialize(
                OssieGenerator.Options(outputFormat = JsonOutputFormat.yaml),
              ),
              InputFormat.YAML,
            )
          }

          "Ossie ontology (JSON) output validates against the Ossie ontology schema" in {
            assume(!skip.contains((name, "ossie-json")), skip.getOrElse((name, "ossie-json"), ""))
            assertIsOssieOntology(
              OssieGenerator(using sv).serialize(
                OssieGenerator.Options(outputFormat = JsonOutputFormat.json),
              ),
              InputFormat.JSON,
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

          "validate" in {
            assume(!skip.contains((name, "validation")), skip.getOrElse((name, "validation"), ""))
            sv.validationProblems.map(_.infer().message) shouldBe empty
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
    "d3fend" -> "validation" -> "LNK-209: Time -> time renaming clash",
    "iso27001" -> "validation" -> "LNK-209: Vendored linkml:types?",
    "nmdc_microbiome" -> "validation" -> "LNK-208, LNK-209: '%'-named PV",
    "tc57cim" -> "validation" -> "LNK-208, LNK-209: '%'-named PV",
    "ai-atlas-nexus" -> "graphql" ->
      ("Bug: emits `enum X {}` for an enum with no permissible values, " +
        "which is a syntax error in GraphQL"),
    "tc57cim" -> "graphql" ->
      ("Bug: emits `enum X {}` for an enum with no permissible values, " +
        "which is a syntax error in GraphQL"),
    "iso27001" -> "graphql" ->
      ("Bug: two slots whose aliases collide become one duplicated field - " +
        "ManagementReview gets `review_date` twice, from the slots `review_date` and " +
        "`ManagementReview_review_date`"),
    "nmdc_microbiome" -> "graphql" ->
      ("Bug: a subclass narrowing a slot's range to an enum breaks GraphQL " +
        "field covariance - NucleotideSequencing.analyte_category is an enum where the " +
        "DataGeneration interface declares String"),
    "sssom" -> "graphql" ->
      ("A permissible value of \"1.0\" becomes the enum " +
        "value `1_0`, and a GraphQL name may not start with a digit."),
  )
}
