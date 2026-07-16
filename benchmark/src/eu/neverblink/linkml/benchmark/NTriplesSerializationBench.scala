package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.generator.rdf.{
  BlankNode,
  CollectingRdfSink,
  Iri,
  Literal,
  Node,
  Resource,
  Triple,
}
import eu.neverblink.linkml.generator.rdf.NTriplesOutput
import eu.neverblink.linkml.benchmark.BenchUtil.BlackholeOutputStream
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

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets
import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Compares the N-Triples streaming serializers of Jena, RDF4J, and ours.
  *
  * This is mostly useful for development, for baseline comparisons with other RDF libs.
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
    val collector = new CollectingRdfSink
    ShaclGenerator().generate(collector)
    val triples = collector.triples

    linkmlTriples = triples.toArray
    jenaTriples = triples.iterator.map(toJena).toArray
    rdf4jStatements = {
      val vf = SimpleValueFactory.getInstance()
      triples.iterator.map(toRdf4j(_)(using vf)).toArray
    }
  }

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

  // element-wise conversion of the generator's RDF model into each library's native types

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
