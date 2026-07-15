package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.rdf.RdfUtils
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.schemaview.SchemaView
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}

import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Benchmarks the two most common code-generator outputs (JSON Schema and SHACL) produced from a
  * LinkML schema loaded from the classpath.
  *
  * For each generator there are two variants:
  *   - `*FromYaml` parses the YAML source into a [[SchemaView]] on every invocation, measuring the
  *     end-to-end cost of a CLI call.
  *   - `*FromSchemaView` reuses a [[SchemaView]] parsed once in [[setup]].
  */
class GeneratorBench extends CommonParams {

  @Param(Array("dummy.yml", "cgmes-core.yml", "cgmes-dynamics.yml"))
  var schema: String = uninitialized

  private var yaml: String = uninitialized
  private var schemaView: SchemaView = uninitialized

  @Setup
  def setup(): Unit = {
    yaml = Using.resource(getClass.getResourceAsStream(s"/schemas/$schema")) { in =>
      Source.fromInputStream(in, "UTF-8").mkString
    }
    schemaView = SchemaView.loadSchemaViewFromString(yaml)
  }

  @Benchmark
  def jsonSchemaFromYaml: String = {
    given sv: SchemaView = SchemaView.loadSchemaViewFromString(yaml)
    JsonSchemaGenerator().serialize()
  }

  @Benchmark
  def jsonSchemaFromSchemaView: String = {
    given sv: SchemaView = schemaView
    JsonSchemaGenerator().serialize()
  }

  @Benchmark
  def shaclFromYaml: String = {
    given sv: SchemaView = SchemaView.loadSchemaViewFromString(yaml)
    RdfUtils.toTurtle(ShaclGenerator().generate())
  }

  @Benchmark
  def shaclFromSchemaView: String = {
    given sv: SchemaView = schemaView
    RdfUtils.toTurtle(ShaclGenerator().generate())
  }
}
