package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.generator.rdf.{
  BlankNode,
  Iri,
  Literal,
  Namespace,
  Node,
  Resource,
  Triple,
}
import eu.neverblink.linkml.generator.rdf.NTriplesOutput
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.schemaview.SchemaView
import org.apache.jena.datatypes.TypeMapper
import org.apache.jena.graph.{NodeFactory, Node as JenaNode, Triple as JenaTriple}
import org.apache.jena.riot.RDFFormat
import org.apache.jena.riot.system.StreamRDFWriter
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.{
  Statement,
  Value,
  IRI as Rdf4jIri,
  Resource as Rdf4jResource,
  ValueFactory,
}
import org.eclipse.rdf4j.rio.ntriples.NTriplesWriter
import org.eclipse.rdf4j.rio.{RDFFormat as Rdf4jFormat, Rio}
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import java.io.{BufferedWriter, OutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Compares the N-Triples **streaming** serializers of Apache Jena (RIOT `StreamRDF`) and Eclipse
  * RDF4J (Rio `RDFWriter`), serializing the same set of statements produced by the SHACL generator.
  *
  * Both writers:
  *   - consume a pre-materialized array of native statements built once in [[setup]] (Jena
  *     `graph.Triple`, RDF4J `Statement`), converted element-wise from the exact same generator
  *     triples so both serialize an identical statement list in identical order;
  *   - write to a fresh [[BlackholeOutputStream]] that feeds every byte to the JMH [[Blackhole]] —
  *     a sink with no I/O and no allocation growth, so the measurement is dominated by
  *     serialization/escaping work while dead-code elimination is still prevented;
  *   - are created and torn down inside the benchmark method, since both APIs are one-shot
  *     (`start`/`finish`, `startRDF`/`endRDF`).
  *
  * The `jena` and `rdf4j` methods add no extra buffering: each relies on its own internal output
  * path, which is part of what is being compared. `rdf4jBuffered` is a diagnostic variant that adds
  * a `BufferedWriter` to show how much of the gap is RDF4J's missing output buffer.
  */
class NTriplesSerializationBench extends CommonParams {

  @Param(Array("dummy.yml", "cgmes-core.yml", "cgmes-dynamics.yml"))
  var schema: String = uninitialized

  private var linkmlTriples: Array[Triple] = uninitialized
  private var jenaTriples: Array[JenaTriple] = uninitialized
  private var rdf4jStatements: Array[Statement] = uninitialized

  @Setup
  def setup(): Unit = {
    val yaml = Using.resource(getClass.getResourceAsStream(s"/schemas/$schema")) { in =>
      Source.fromInputStream(in, "UTF-8").mkString
    }
    given sv: SchemaView = SchemaView.loadSchemaViewFromString(yaml)
    val (_, triples): (Seq[Namespace], Seq[Triple]) = ShaclGenerator().generate()

    linkmlTriples = triples.toArray
    jenaTriples = triples.iterator.map(toJena).toArray
    rdf4jStatements = {
      val vf = SimpleValueFactory.getInstance()
      triples.iterator.map(toRdf4j(_)(using vf)).toArray
    }
  }

  /** Our own [[NTriplesOutput]] writer, serializing the [[Triple]] model directly — no conversion
    * to a foreign statement type is needed, unlike the Jena and RDF4J variants.
    */
  @Benchmark
  def linkml(bh: Blackhole): Unit =
    NTriplesOutput.writeTo(new BlackholeOutputStream(bh), linkmlTriples)

  @Benchmark
  def jena(bh: Blackhole): Unit = {
    val out = new BlackholeOutputStream(bh)
    val stream = StreamRDFWriter.getWriterStream(out, RDFFormat.NTRIPLES)
    stream.start()
    var i = 0
    while (i < jenaTriples.length) {
      stream.triple(jenaTriples(i))
      i += 1
    }
    stream.finish()
  }

  @Benchmark
  def rdf4j(bh: Blackhole): Unit = {
    val out = new BlackholeOutputStream(bh)
    val writer = Rio.createWriter(Rdf4jFormat.NTRIPLES, out)
    writer.startRDF()
    var i = 0
    while (i < rdf4jStatements.length) {
      writer.handleStatement(rdf4jStatements(i))
      i += 1
    }
    writer.endRDF()
  }

  /** Diagnostic: RDF4J's `NTriplesWriter(OutputStream)` wraps the stream in a bare
    * `OutputStreamWriter` (no buffering), so every per-character/per-token `append` hits the
    * `StreamEncoder` — which is `synchronized` per call. Feeding it a `BufferedWriter` instead
    * isolates how much of the Jena gap is purely the missing output buffer.
    */
  @Benchmark
  def rdf4jBuffered(bh: Blackhole): Unit = {
    val out = new BlackholeOutputStream(bh)
    val writer =
      new NTriplesWriter(new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8)))
    writer.startRDF()
    var i = 0
    while (i < rdf4jStatements.length) {
      writer.handleStatement(rdf4jStatements(i))
      i += 1
    }
    writer.endRDF()
  }

  // -- element-wise conversion of the generator's RDF model into each library's native types --

  private def toJena(t: Triple): JenaTriple =
    JenaTriple.create(toJenaNode(t.subj), toJenaNode(t.pred), toJenaNode(t.obj))

  private def toJenaNode(node: Node): JenaNode = node match {
    case i: Iri => NodeFactory.createURI(i.value)
    case b: BlankNode => NodeFactory.createBlankNode(b.id)
    case l: Literal =>
      NodeFactory.createLiteralDT(
        l.value,
        TypeMapper.getInstance().getSafeTypeByName(l.datatype.value),
      )
  }

  private def toRdf4j(t: Triple)(using vf: ValueFactory): Statement =
    vf.createStatement(toRdf4jResource(t.subj), toRdf4jIri(t.pred), toRdf4jValue(t.obj))

  private def toRdf4jValue(node: Node)(using vf: ValueFactory): Value = node match {
    case r: Resource => toRdf4jResource(r)
    case l: Literal => vf.createLiteral(l.value, toRdf4jIri(l.datatype))
  }

  private def toRdf4jResource(res: Resource)(using vf: ValueFactory): Rdf4jResource = res match {
    case i: Iri => toRdf4jIri(i)
    case b: BlankNode => vf.createBNode(b.id)
  }

  private def toRdf4jIri(iri: Iri)(using vf: ValueFactory): Rdf4jIri = vf.createIRI(iri.value)
}

/** An [[OutputStream]] that feeds everything written to it into a JMH [[Blackhole]]: a free,
  * allocation-stable sink that removes I/O from the measurement while still forcing the writer to
  * produce every byte (the serialized bytes flow into the blackhole, so nothing can be elided).
  */
private final class BlackholeOutputStream(bh: Blackhole) extends OutputStream {
  override def write(b: Int): Unit = bh.consume(b)
  override def write(b: Array[Byte]): Unit = bh.consume(b)
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    bh.consume(len)
    bh.consume(b)
  }
}
