package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.benchmark.BenchUtil.BlackholeOutputStream
import eu.neverblink.linkml.generator.DocumentGenerator
import eu.neverblink.linkml.generator.docs.DocsGenerator
import eu.neverblink.linkml.generator.erdiagram.ErDiagramGenerator
import eu.neverblink.linkml.generator.frictionless.FrictionlessGenerator
import eu.neverblink.linkml.generator.graphql.GraphQlGenerator
import eu.neverblink.linkml.generator.jsonschema.JsonSchemaGenerator
import eu.neverblink.linkml.generator.linkml.LinkMlGenerator
import eu.neverblink.linkml.generator.ossie.OssieGenerator
import eu.neverblink.linkml.generator.owl.OwlGenerator
import eu.neverblink.linkml.generator.pydantic.PydanticGenerator
import eu.neverblink.linkml.generator.rdfs.RdfsGenerator
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.stats.StatsGenerator
import eu.neverblink.linkml.generator.translation.TranslationGenerator
import eu.neverblink.linkml.generator.typescript.TypeScriptGenerator
import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.SchemaIssues
import eu.neverblink.linkml.schemaview.SchemaView
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Benchmarks the generators that write a single document, using a LinkML schema loaded from the
  * classpath.
  *
  * For each generator there are three variants:
  *   - `*FromYaml` parses the YAML source into a [[SchemaView]] on every invocation, measuring the
  *     end-to-end cost of a CLI call.
  *   - `*FromSchemas` builds a new [[SchemaView]] from schemas parsed once in [[setup]].
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
    writeJsonSchema(bh)(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml)))

  @Benchmark
  def jsonSchemaFromSchemas(bh: Blackhole): Unit =
    writeJsonSchema(bh)(using SchemaView(schemaView.schemas))

  @Benchmark
  def jsonSchemaFromSchemaView(bh: Blackhole): Unit =
    writeJsonSchema(bh)(using schemaView)

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

  @Benchmark
  def rdfsFromYaml(bh: Blackhole): Unit =
    write(bh, RdfsGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))))

  @Benchmark
  def rdfsFromSchemas(bh: Blackhole): Unit =
    write(bh, RdfsGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def rdfsFromSchemaView(bh: Blackhole): Unit =
    write(bh, RdfsGenerator(using schemaView))

  @Benchmark
  def owlFromYaml(bh: Blackhole): Unit =
    write(bh, OwlGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))))

  @Benchmark
  def owlFromSchemas(bh: Blackhole): Unit =
    write(bh, OwlGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def owlFromSchemaView(bh: Blackhole): Unit =
    write(bh, OwlGenerator(using schemaView))

  @Benchmark
  def graphQlFromYaml(bh: Blackhole): Unit =
    write(
      bh,
      GraphQlGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))),
    )

  @Benchmark
  def graphQlFromSchemas(bh: Blackhole): Unit =
    write(bh, GraphQlGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def graphQlFromSchemaView(bh: Blackhole): Unit =
    write(bh, GraphQlGenerator(using schemaView))

  @Benchmark
  def typeScriptFromYaml(bh: Blackhole): Unit =
    write(
      bh,
      TypeScriptGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))),
    )

  @Benchmark
  def typeScriptFromSchemas(bh: Blackhole): Unit =
    write(bh, TypeScriptGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def typeScriptFromSchemaView(bh: Blackhole): Unit =
    write(bh, TypeScriptGenerator(using schemaView))

  @Benchmark
  def pydanticFromYaml(bh: Blackhole): Unit =
    write(
      bh,
      PydanticGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))),
    )

  @Benchmark
  def pydanticFromSchemas(bh: Blackhole): Unit =
    write(bh, PydanticGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def pydanticFromSchemaView(bh: Blackhole): Unit =
    write(bh, PydanticGenerator(using schemaView))

  @Benchmark
  def ossieFromYaml(bh: Blackhole): Unit =
    write(bh, OssieGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))))

  @Benchmark
  def ossieFromSchemas(bh: Blackhole): Unit =
    write(bh, OssieGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def ossieFromSchemaView(bh: Blackhole): Unit =
    write(bh, OssieGenerator(using schemaView))

  @Benchmark
  def erDiagramFromYaml(bh: Blackhole): Unit =
    write(
      bh,
      ErDiagramGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))),
    )

  @Benchmark
  def erDiagramFromSchemas(bh: Blackhole): Unit =
    write(bh, ErDiagramGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def erDiagramFromSchemaView(bh: Blackhole): Unit =
    write(bh, ErDiagramGenerator(using schemaView))

  @Benchmark
  def translationFromYaml(bh: Blackhole): Unit =
    write(
      bh,
      TranslationGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))),
    )

  @Benchmark
  def translationFromSchemas(bh: Blackhole): Unit =
    write(bh, TranslationGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def translationFromSchemaView(bh: Blackhole): Unit =
    write(bh, TranslationGenerator(using schemaView))

  @Benchmark
  def frictionlessFromYaml(bh: Blackhole): Unit =
    write(
      bh,
      FrictionlessGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))),
    )

  @Benchmark
  def frictionlessFromSchemas(bh: Blackhole): Unit =
    write(bh, FrictionlessGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def frictionlessFromSchemaView(bh: Blackhole): Unit =
    write(bh, FrictionlessGenerator(using schemaView))

  @Benchmark
  def docsFromYaml(bh: Blackhole): Unit =
    write(bh, DocsGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))))

  @Benchmark
  def docsFromSchemas(bh: Blackhole): Unit =
    write(bh, DocsGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def docsFromSchemaView(bh: Blackhole): Unit =
    write(bh, DocsGenerator(using schemaView))

  @Benchmark
  def statsFromYaml(bh: Blackhole): Unit =
    write(bh, StatsGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))))

  @Benchmark
  def statsFromSchemas(bh: Blackhole): Unit =
    write(bh, StatsGenerator(using SchemaView(schemaView.schemas)))

  @Benchmark
  def statsFromSchemaView(bh: Blackhole): Unit =
    write(bh, StatsGenerator(using schemaView))

  /** Without pruning, which skips the reachability query. */
  @Benchmark
  def statsSkipFromSchemas(bh: Blackhole): Unit =
    StatsGenerator(using SchemaView(schemaView.schemas))
      .writeTo(
        new BlackholeOutputStream(bh),
        StatsGenerator.Options(pruningMode = PruningMode.skip),
      )

  /** Writes the document with default options to the blackhole, as the CLI writes it to a file. */
  private def write(bh: Blackhole, generator: DocumentGenerator[?]): Unit =
    generator.writeTo(new BlackholeOutputStream(bh))

  /** Same setup for JSON schema sinks as in the CLI.
    */
  private def writeJsonSchema(bh: Blackhole)(using SchemaView): Unit =
    JsonSchemaGenerator().writeTo(new BlackholeOutputStream(bh))

  /** Same setup for RDF sinks as in the CLI.
    */
  private def writeShacl(bh: Blackhole)(using SchemaView): Unit =
    ShaclGenerator().writeTo(new BlackholeOutputStream(bh))
}
