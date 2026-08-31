package eu.neverblink.linkml.benchmark

import eu.neverblink.linkml.benchmark.BenchUtil.BlackholeOutputStream
import eu.neverblink.linkml.generator.rdf.*
import eu.neverblink.linkml.generator.rdf.NTriplesWriter as LinkMlNTriplesWriter
import eu.neverblink.linkml.generator.rdf.TurtleWriter as LinkMlTurtleWriter
import eu.neverblink.linkml.generator.shacl.ShaclGenerator
import eu.neverblink.linkml.generator.util.Utf8ByteSink
import eu.neverblink.linkml.schemaview.SchemaIssues
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
import org.eclipse.rdf4j.rio.RDFWriter
import org.eclipse.rdf4j.rio.ntriples.NTriplesWriter
import org.eclipse.rdf4j.rio.turtle.TurtleWriter
import org.eclipse.rdf4j.rio.{RDFFormat as Rdf4jFormat, Rio}
import org.openjdk.jmh.annotations.{Benchmark, Param, Setup}
import org.openjdk.jmh.infra.Blackhole

import java.io.{BufferedWriter, OutputStream, OutputStreamWriter, Writer}
import java.nio.charset.StandardCharsets
import scala.compiletime.uninitialized
import scala.io.Source
import scala.util.Using

/** Compares the streaming RDF serializers of Jena, RDF4J, and ours, in both formats we write.
  *
  * This is mostly useful for development, for baseline comparisons with other RDF libs.
  *
  * Every library is given the same flat list of triples, so what is measured is serialization and
  * nothing else. That means that our Turtle writer cannot use two inlining optimizations:
  *
  *   - RDF lists arrive already expanded into `rdf:first` / `rdf:rest` triples, because
  *     [[CollectingRdfSink]] has no collection support, so nothing is written as `( ... )`.
  *   - Blank nodes are all labeled. An [[InlineBlankNode]] promises that its triples directly
  *     follow the triple referencing it, and flattening breaks that promise.
  */
class RdfSerializationBench extends CommonParams {

  @Param(Array("cgmes-core.yml", "cgmes-dynamics.yml", "TC57CIM.yml"))
  var schema: String = uninitialized

  @Param(Array("nt", "ttl"))
  var format: String = uninitialized

  private var linkmlTriples: Array[Triple] = uninitialized
  private var jenaTriples: Array[JenaTriple] = uninitialized
  private var rdf4jStatements: Array[Statement] = uninitialized

  /** The format constants, resolved once so that the measured loop does not re-match a string. */
  private var jenaFormat: RDFFormat = uninitialized
  private var rdf4jFormat: Rdf4jFormat = uninitialized

  @Setup
  def setup(): Unit = {
    val yaml = Using.resource(getClass.getResourceAsStream(s"/schemas/$schema")) { in =>
      Source.fromInputStream(in, "UTF-8").mkString
    }
    val collector = new CollectingRdfSink
    ShaclGenerator(using SchemaIssues.orThrow(SchemaView.loadSchemaViewFromString(yaml))).generate(
      collector,
    )
    val triples = collector.triples.map(t => Triple(labeled(t.subj), t.pred, labeled(t.obj)))

    linkmlTriples = triples.toArray
    jenaTriples = triples.iterator.map(toJena).toArray
    rdf4jStatements = {
      val vf = SimpleValueFactory.getInstance()
      triples.iterator.map(toRdf4j(_)(using vf)).toArray
    }

    // Jena's plain TURTLE is the pretty-printing writer, which buffers the whole graph. Its
    // streaming Turtle is TURTLE_BLOCKS, which groups by subject the way ours does.
    jenaFormat = format match {
      case "nt" => RDFFormat.NTRIPLES
      case "ttl" => RDFFormat.TURTLE_BLOCKS
      case other => throw new IllegalArgumentException(s"unknown format '$other'")
    }
    rdf4jFormat = format match {
      case "nt" => Rdf4jFormat.NTRIPLES
      case "ttl" => Rdf4jFormat.TURTLE
      case other => throw new IllegalArgumentException(s"unknown format '$other'")
    }
  }

  @Benchmark
  def linkml(bh: Blackhole): Unit = {
    val sink = new Utf8ByteSink(new BlackholeOutputStream(bh))
    val writer: RdfSink =
      if (format == "nt") new LinkMlNTriplesWriter(sink) else new LinkMlTurtleWriter(sink)
    var i = 0
    while (i < linkmlTriples.length) {
      val triple = linkmlTriples(i)
      writer.triple(triple.subj, triple.pred, triple.obj)
      i += 1
    }
    writer.finish()
    sink.flush()
  }

  @Benchmark
  def jena(bh: Blackhole): Unit = {
    val out = new BlackholeOutputStream(bh)
    val stream = StreamRDFWriter.getWriterStream(out, jenaFormat)
    stream.start()
    var i = 0
    while (i < jenaTriples.length) {
      stream.triple(jenaTriples(i))
      i += 1
    }
    stream.finish()
  }

  @Benchmark
  def rdf4j(bh: Blackhole): Unit =
    writeRdf4j(Rio.createWriter(rdf4jFormat, new BlackholeOutputStream(bh)))

  @Benchmark
  def rdf4jBuffered(bh: Blackhole): Unit = {
    val writer = bufferedWriter(new BlackholeOutputStream(bh))
    writeRdf4j(if (format == "nt") new NTriplesWriter(writer) else new TurtleWriter(writer))
  }

  private def bufferedWriter(out: OutputStream): Writer =
    new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))

  private def writeRdf4j(writer: RDFWriter): Unit = {
    writer.startRDF()
    var i = 0
    while (i < rdf4jStatements.length) {
      writer.handleStatement(rdf4jStatements(i))
      i += 1
    }
    writer.endRDF()
  }

  /** Label an inline blank node, so that the triples can be replayed in any order. */
  private def labeled(res: Resource): Resource = res match {
    case b: InlineBlankNode => BlankNode(b.id)
    case other => other
  }

  private def labeled(node: Node): Node = node match {
    case res: Resource => labeled(res)
    case other => other
  }

  // element-wise conversion of the generator's RDF model into each library's native types

  private def toJena(t: Triple): JenaTriple =
    JenaTriple.create(toJenaNode(t.subj), toJenaNode(t.pred), toJenaNode(t.obj))

  private def toJenaNode(node: Node): JenaNode = node match {
    case i: Iri => NodeFactory.createURI(i.value)
    case b: AnyBlankNode => NodeFactory.createBlankNode(b.id)
    case l: LanguageLiteral => NodeFactory.createLiteralLang(l.value, l.language)
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
    case l: LanguageLiteral => vf.createLiteral(l.value, l.language)
    case l: Literal => vf.createLiteral(l.value, toRdf4jIri(l.datatype))
  }

  private def toRdf4jResource(res: Resource)(using vf: ValueFactory): Rdf4jResource = res match {
    case i: Iri => toRdf4jIri(i)
    case b: AnyBlankNode => vf.createBNode(b.id)
  }

  private def toRdf4jIri(iri: Iri)(using vf: ValueFactory): Rdf4jIri = vf.createIRI(iri.value)
}
