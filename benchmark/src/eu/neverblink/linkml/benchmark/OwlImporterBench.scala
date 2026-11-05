package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.benchmark.BenchUtil.BlackholeOutputStream
import eu.neverblink.linkml.generator.options.{OwlImporterOptions, RdfFormat}
import eu.neverblink.linkml.generator.owl.{OwlImporter, OwlRdfReader}
import eu.neverblink.linkml.metamodel.SchemaDefinition
import eu.neverblink.linkml.rdf.{CollectingRdfSink, NTriplesParser, Triple}
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import java.io.ByteArrayInputStream

import scala.compiletime.uninitialized
import scala.util.Using

/** Benchmarks the OWL importer, using a real-world ontology in N-Triples loaded from the classpath.
  *
  * There are three variants:
  *   - `fromNTriples` parses the N-Triples, imports the ontology and writes the schema as YAML on
  *     every invocation, measuring the end-to-end cost of a CLI call.
  *   - `fromTriples` imports from triples parsed once in [[setup]].
  *   - `fromOntology` imports from an ontology read from the triples once in [[setup]].
  */
class OwlImporterBench extends CommonParams {
  @Param(Array("prov-o.nt", "saref.nt"))
  var ontology: String = uninitialized

  private val options = OwlImporterOptions(inputFormat = RdfFormat.Nt)
  private val importer = OwlImporter()
  private var bytes: Array[Byte] = uninitialized
  private var triples: IndexedSeq[Triple] = uninitialized
  private var read: OwlRdfReader.Result = uninitialized

  @Setup
  def setup(): Unit = {
    bytes = Using.resource(getClass.getResourceAsStream(s"/ontologies/$ontology"))(_.readAllBytes())
    val sink = CollectingRdfSink()
    NTriplesParser.parse(ByteArrayInputStream(bytes), sink)
    triples = sink.triples.toIndexedSeq
    read = OwlRdfReader.read(triples)
  }

  @Benchmark
  def fromNTriples(bh: Blackhole): Unit = {
    val result = importer.importWithWarnings(ByteArrayInputStream(bytes), options)
    importer.writeSchema(
      result.schema,
      new BlackholeOutputStream(bh),
      options.format,
      options.fullUris,
    )
  }

  @Benchmark
  def fromTriples: SchemaDefinition =
    importer.importTriples(triples, options).schema

  @Benchmark
  def fromOntology: SchemaDefinition =
    importer.importOntology(read.ontology, options, read.unparsed).schema
}
