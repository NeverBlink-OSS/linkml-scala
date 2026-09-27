package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.rdf.io.Utf8ByteSink
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import org.apache.jena.graph.Triple as JenaTriple
import org.apache.jena.riot.system.StreamRDFBase
import org.apache.jena.riot.{Lang, RDFParser}
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.rio.helpers.{AbstractRDFHandler, BasicParserSettings}
import org.eclipse.rdf4j.rio.{RDFFormat as Rdf4jFormat, RDFParser as Rdf4jParser, Rio}
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.lang.Boolean as JBoolean
import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Compares the streaming N-Triples parsers of Jena, RDF4J, and ours.
  *
  * The comparison with base RDF4J and Jena parsers is not fair, because they do a lot of IRI
  * validation, blank node renaming and so on (we don't do any of that). The rdf4jNoChecks variant
  * should be a fair comparison.
  */
class RdfParsingBench extends CommonParams {

  @Param(Array("cgmes-core.yml", "cgmes-dynamics.yml", "TC57CIM.yml"))
  var schema: String = uninitialized

  private var document: Array[Byte] = uninitialized

  @Setup
  def setup(): Unit = {
    val yaml = Using.resource(getClass.getResourceAsStream(s"/schemas/$schema")) { in =>
      Source.fromInputStream(in, "UTF-8").mkString
    }
    val out = new ByteArrayOutputStream
    val sink = new Utf8ByteSink(out)
    val writer = new NTriplesWriter(sink)
    ShaclGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml)))
      .generate(writer)
    writer.finish()
    sink.flush()
    document = out.toByteArray
  }

  @Benchmark
  def linkml(bh: Blackhole): Unit =
    NTriplesParser.parse(
      new ByteArrayInputStream(document),
      new RdfSink {
        def namespace(prefix: String, name: String): Unit = ()
        def triple(subj: Resource, pred: Iri, obj: Node): Unit = {
          bh.consume(subj)
          bh.consume(pred)
          bh.consume(obj)
        }
      },
    )

  @Benchmark
  def jena(bh: Blackhole): Unit =
    RDFParser.source(new ByteArrayInputStream(document)).lang(Lang.NTRIPLES).parse(
      new StreamRDFBase {
        override def triple(triple: JenaTriple): Unit = bh.consume(triple)
      },
    )

  @Benchmark
  def rdf4j(bh: Blackhole): Unit =
    rdf4jParser(bh).parse(new ByteArrayInputStream(document), "")

  @Benchmark
  def rdf4jNoChecks(bh: Blackhole): Unit = {
    val parser = rdf4jParser(bh)
    val config = parser.getParserConfig
    config.set(BasicParserSettings.VERIFY_URI_SYNTAX, JBoolean.FALSE)
    config.set(BasicParserSettings.PRESERVE_BNODE_IDS, JBoolean.TRUE)
    config.set(BasicParserSettings.VERIFY_LANGUAGE_TAGS, JBoolean.FALSE)
    config.set(BasicParserSettings.NORMALIZE_LANGUAGE_TAGS, JBoolean.FALSE)
    config.set(BasicParserSettings.PROCESS_ENCODED_TRIPLE_TERMS, JBoolean.FALSE)
    parser.parse(new ByteArrayInputStream(document), "")
  }

  private def rdf4jParser(bh: Blackhole): Rdf4jParser = {
    val parser = Rio.createParser(Rdf4jFormat.NTRIPLES)
    parser.setRDFHandler(new AbstractRDFHandler {
      override def handleStatement(st: Statement): Unit = bh.consume(st)
    })
    parser
  }
}
