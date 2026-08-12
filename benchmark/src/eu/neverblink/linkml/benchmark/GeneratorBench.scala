package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.benchmark.BenchUtil.BlackholeOutputStream
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.rdf.{BufferedByteSink, NTriplesRdfSink}
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Benchmarks the common code-generator outputs (JSON Schema, SHACL and LinkML) produced from a
  * LinkML schema loaded from the classpath.
  *
  * For each generator there are two variants:
  *   - `*FromYaml` parses the YAML source into a [[SchemaView]] on every invocation, measuring the
  *     end-to-end cost of a CLI call.
  *   - `*FromSchemaView` reuses a [[SchemaView]] parsed once in [[setup]].
  */
class GeneratorBench extends CommonParams {
  @Param(Array("cgmes-core.yml", "cgmes-dynamics.yml", "TC57CIM.yml"))
  var schema: String = uninitialized

  private var yaml: String = uninitialized
  private var schemaView: SchemaView = uninitialized

  @Setup
  def setup(): Unit = {
    yaml = Using.resource(getClass.getResourceAsStream(s"/schemas/$schema")) { in =>
      Source.fromInputStream(in, "UTF-8").mkString
    }
    schemaView = SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))
  }

  @Benchmark
  def jsonSchemaFromYaml(bh: Blackhole): Unit =
    bh.consume(
      JsonSchemaGenerator(using
        SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml)),
      ).serialize(),
    )

  @Benchmark
  def jsonSchemaFromSchemas(bh: Blackhole): Unit =
    bh.consume(JsonSchemaGenerator(using SchemaView(schemaView.schemas)).serialize())

  @Benchmark
  def jsonSchemaFromSchemaView(bh: Blackhole): Unit =
    bh.consume(JsonSchemaGenerator(using schemaView).serialize())

  @Benchmark
  def shaclFromYaml(bh: Blackhole): Unit =
    writeShacl(bh)(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml)))

  @Benchmark
  def shaclFromSchemas(bh: Blackhole): Unit =
    writeShacl(bh)(using SchemaView(schemaView.schemas))

  @Benchmark
  def shaclFromSchemaView(bh: Blackhole): Unit =
    writeShacl(bh)(using schemaView)

  @Benchmark
  def linkmlFromSchemas(bh: Blackhole): Unit =
    bh.consume(LinkMlGenerator(using SchemaView(schemaView.schemas)).serialize())

  @Benchmark
  def linkmlFromSchemaView(bh: Blackhole): Unit =
    bh.consume(LinkMlGenerator(using schemaView).serialize())

  @Benchmark
  def linkmlFromYaml(bh: Blackhole): Unit =
    bh.consume(
      LinkMlGenerator(using
        SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml)),
      ).serialize(),
    )

  /** Same setup for RDF sinks as in the CLI.
    */
  private def writeShacl(bh: Blackhole)(using SchemaView): Unit = {
    val byteSink = new BufferedByteSink(new BlackholeOutputStream(bh))
    ShaclGenerator().generate(NTriplesRdfSink(byteSink))
    byteSink.flush()
  }
}
