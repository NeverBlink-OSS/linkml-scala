package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.rdf.RdfUtils
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.metamodel.SchemaDefinition
import eu.neverblink.linkml.schemaview.SchemaView
import org.openjdk.jmh.annotations.*
import os.Path

import scala.compiletime.uninitialized

transparent inline def models: Array[String] = Array(
  "ai-atlas-nexus",
  "brigde2ai_model_card",
  "cdm",
  "chem-dcat-ap",
  "crdch",
  "d3fend",
  "fluxnova-bpm",
  "include",
  "iso27001",
  "nmdc_microbiome",
  "sssom",
  "tc57cim",
)

@Warmup(iterations = 5)
@Fork(
  value = 5,
  jvmArgs = jvmArgs,
)
@Measurement(iterations = 10)
class WarmBench extends CommonParams {
  val repoRoot: Path =
    Option(System.getenv("MILL_WORKSPACE_ROOT")).map(os.Path.apply).getOrElse(os.pwd)

  val modelsDir: Path = repoRoot / ".." / "linkml-benchmark-schemas"

  assume(
    os.exists(modelsDir),
    "Did not find the linkml-benchmark-schemas directory in the expected location.",
  )

  @Param(models)
  var model: String = uninitialized

  var schemaDefs: Seq[SchemaDefinition] = uninitialized

  @Setup
  def setup(): Unit = {
    schemaDefs = SchemaView.loadSchemas((modelsDir / model / "main.yaml").toString)
  }

  @Benchmark
  def jsonSchema(): Unit = {
    // create SchemaView in the benchmark to not use the class cache
    given SchemaView = SchemaView(schemaDefs)
    JsonSchemaGenerator().serialize()
  }

  @Benchmark
  def shacl(): Unit = {
    // create SchemaView in the benchmark to not use the class cache
    given SchemaView = SchemaView(schemaDefs)

    RdfUtils.toTurtle(ShaclGenerator().generate(_))
  }
}
