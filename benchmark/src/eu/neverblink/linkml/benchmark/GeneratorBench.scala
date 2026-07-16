package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.benchmark.BenchUtil.BlackholeOutputStream
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.rdf.{BufferedByteSink, NTriplesRdfSink}
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.schemaview.SchemaView
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

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
  def jsonSchemaFromYaml(bh: Blackhole): Unit = {
    given sv: SchemaView = SchemaView.loadSchemaViewFromString(yaml)
    bh.consume(JsonSchemaGenerator().serialize())
  }

  @Benchmark
  def jsonSchemaFromSchemaView(bh: Blackhole): Unit = {
    given sv: SchemaView = schemaView
    bh.consume(JsonSchemaGenerator().serialize())
  }

  @Benchmark
  def shaclFromYaml(bh: Blackhole): Unit = {
    given sv: SchemaView = SchemaView.loadSchemaViewFromString(yaml)
    writeShacl(bh)
  }

  @Benchmark
  def shaclFromSchemaView(bh: Blackhole): Unit = {
    given sv: SchemaView = schemaView
    writeShacl(bh)
  }

  /** Same setup for RDF sinks as in the CLI.
    */
  private def writeShacl(bh: Blackhole)(using SchemaView): Unit = {
    val byteSink = new BufferedByteSink(new BlackholeOutputStream(bh))
    ShaclGenerator().generate(NTriplesRdfSink(byteSink))
    byteSink.flush()
  }
}
