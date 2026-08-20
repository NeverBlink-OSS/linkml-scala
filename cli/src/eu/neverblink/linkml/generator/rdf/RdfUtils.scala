package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.util.StringSink
import org.eclipse.rdf4j.model.impl.SimpleValueFactory
import org.eclipse.rdf4j.model.util.ModelBuilder
import org.eclipse.rdf4j.model.{Value, ValueFactory, IRI as Rdf4jIri, Resource as Rdf4jResource}
import org.eclipse.rdf4j.rio.helpers.BasicWriterSettings
import org.eclipse.rdf4j.rio.{RDFFormat, Rio, WriterConfig}

import java.io.{FilterOutputStream, OutputStream, StringWriter}

/** An [[RdfSink]] that collects prefixes and triples into an RDF4J [[Model]] and serializes it as
  * pretty-printed Turtle. Building the model and running the RDF4J writer is substantially slower
  * than the streaming [[NTriplesRdfSink]], but produces prefixed, blank-node inlined output.
  *
  * Serialize either straight to an [[OutputStream]] via [[writeTo]] (preferred – no full-document
  * string is materialized) or as a [[String]] via [[result]].
  */
final class TurtleRdfSink(using vf: ValueFactory = SimpleValueFactory.getInstance())
    extends RdfSink {

  private val builder = new ModelBuilder

  def namespace(prefix: String, name: String): Unit = builder.setNamespace(prefix, name)

  def triple(subj: Resource, pred: Iri, obj: Node): Unit =
    builder.add(toRdf4jResource(subj), toRdf4jIri(pred), toRdf4jValue(obj))

  /** Serialize the collected model as Turtle straight to [[out]]. Flushes but does not close it. */
  def writeTo(out: OutputStream): Unit = {
    val buffered = new FastBufferedOutputStream(out)
    Rio.write(builder.build(), buffered, RDFFormat.TURTLE, TurtleRdfSink.config)
    buffered.flush()
  }

  /** The collected model as a Turtle string. */
  def result: String = {
    val out = new StringWriter
    Rio.write(builder.build(), out, RDFFormat.TURTLE, TurtleRdfSink.config)
    out.toString
  }

  private def toRdf4jValue(node: Node): Value = node match {
    case r: Resource => toRdf4jResource(r)
    case l: Literal => vf.createLiteral(l.value, toRdf4jIri(l.datatype))
  }

  private def toRdf4jResource(res: Resource): Rdf4jResource = res match {
    case i: Iri => toRdf4jIri(i)
    case b: BlankNode => vf.createBNode(b.id)
  }

  private def toRdf4jIri(iri: Iri): Rdf4jIri = vf.createIRI(iri.value)
}

object TurtleRdfSink {
  private val config: WriterConfig = {
    val c = new WriterConfig
    c.set(BasicWriterSettings.INLINE_BLANK_NODES, true)
    c
  }
}

object RdfUtils {

  /** Serialize into a Turtle string whatever [[write]] pushes into a [[TurtleRdfSink]]. Prefer
    * [[streamTurtle]] where an output stream is available – this materializes the whole document.
    */
  def toTurtle(write: RdfSink => Unit): String = {
    val sink = new TurtleRdfSink
    write(sink)
    sink.result
  }

  /** Serialize straight to [[out]] whatever [[write]] pushes into a [[TurtleRdfSink]], skipping the
    * intermediate string. Typically `RdfUtils.streamTurtle(out, generator.generate(_))`.
    */
  def streamTurtle(out: OutputStream, write: RdfSink => Unit): Unit = {
    val sink = new TurtleRdfSink
    write(sink)
    sink.writeTo(out)
  }

  /** Serialize into an N-Triples string whatever [[write]] pushes into a [[NTriplesRdfSink]].
    * Prefer [[streamNTriples]] where an output stream is available – this materializes the whole
    * document.
    */
  def toNTriples(write: RdfSink => Unit): String = {
    val charSink = StringSink()
    val sink = NTriplesRdfSink(charSink)
    write(sink)
    charSink.result
  }

  /** Serialize straight to [[out]] whatever [[write]] pushes into a [[NTriplesRdfSink]], skipping
    * the intermediate string. Typically `RdfUtils.streamNTriples(out, generator.generate(_))`.
    */
  def streamNTriples(out: OutputStream, write: RdfSink => Unit): Unit = {
    val charSink = new BufferedByteSink(out)
    val sink = NTriplesRdfSink(charSink)
    write(sink)
    charSink.flush()
  }
}

/** A fast replacement for java.io.BufferedOutputStream. Not thread-safe.
  */
class FastBufferedOutputStream(out: OutputStream) extends FilterOutputStream(out) {
  private inline val capacity = 32768
  private val buf = new Array[Byte](capacity)
  private var count = 0

  override def write(b: Int): Unit = {
    var i = count
    if (i >= capacity) i = flushBuf(i)
    buf(i) = b.toByte
    count = i + 1
  }

  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    var i = count
    if (len >= capacity) {
      count = flushBuf(i)
      out.write(b, off, len)
    } else {
      if (len > capacity - i) i = flushBuf(i)
      System.arraycopy(b, off, buf, i, len)
      count = i + len
    }
  }

  override def flush(): Unit = {
    val i = count
    if (i > 0) count = flushBuf(i)
    out.flush()
  }

  private def flushBuf(len: Int): Int = {
    out.write(buf, 0, len)
    0
  }
}
