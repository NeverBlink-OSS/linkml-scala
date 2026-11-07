package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.benchmark.BenchUtil.BlackholeOutputStream
import eu.neverblink.linkml.generator.ossie.OssieImporter
import eu.neverblink.linkml.metamodel.SchemaDefinition
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import java.io.ByteArrayInputStream

import scala.compiletime.uninitialized
import scala.util.Using

/** Benchmarks the Apache Ossie importer, using an ontology in YAML loaded from the classpath.
  *
  * There are two variants:
  *   - `fromYaml` parses the YAML, imports the ontology and writes the schema as YAML on every
  *     invocation, measuring the end-to-end cost of a CLI call.
  *   - `schemaFromYaml` parses the YAML and imports the ontology, without writing the schema.
  */
class OssieImporterBench extends CommonParams {
  @Param(Array("flights.yaml", "cgmes-core.ossie.yaml"))
  var ontology: String = uninitialized

  private val importer = OssieImporter()
  private var bytes: Array[Byte] = uninitialized

  @Setup
  def setup(): Unit =
    bytes = Using.resource(getClass.getResourceAsStream(s"/ontologies/$ontology"))(_.readAllBytes())

  @Benchmark
  def fromYaml(bh: Blackhole): Unit =
    importer.writeTo(ByteArrayInputStream(bytes), new BlackholeOutputStream(bh))

  @Benchmark
  def schemaFromYaml: SchemaDefinition =
    importer.importSchema(ByteArrayInputStream(bytes))
}
