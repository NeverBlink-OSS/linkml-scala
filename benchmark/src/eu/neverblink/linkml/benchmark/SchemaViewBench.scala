package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.metamodel.SchemaDefinition
import eu.neverblink.linkml.schemaview.SchemaView
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Benchmarks for SchemaView operations. These are not very meaningful on their own. Use them only
  * for development (perf regression testing).
  */
class SchemaViewBench extends CommonParams {

  @Param(Array("dummy.yml", "cgmes-core.yml", "cgmes-dynamics.yml"))
  var schema: String = uninitialized

  private var schemas: Seq[SchemaDefinition] = uninitialized

  @Setup
  def setup(): Unit = {
    val yaml = Using.resource(getClass.getResourceAsStream(s"/schemas/$schema")) { in =>
      Source.fromInputStream(in, "UTF-8").mkString
    }
    schemas = SchemaView.loadSchemaViewFromString(yaml).schemas
  }

  /** Construction: includes checking for fatal problems in the sv. */
  @Benchmark
  def construct: SchemaView =
    new SchemaView(schemas)

  /** Slot derivation: iterate every class and derive whether it has an identifier slot. */
  @Benchmark
  def identifierDerivation(bh: Blackhole): Unit = {
    val sv = new SchemaView(schemas)
    sv.classes.values.foreach(cls => bh.consume(cls.hasIdentifier))
  }
}
