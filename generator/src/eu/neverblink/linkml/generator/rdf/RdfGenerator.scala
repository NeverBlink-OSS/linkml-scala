package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.generator.DocumentGenerator
import eu.neverblink.linkml.generator.util.{StringSink, Utf8ByteSink}
import eu.neverblink.linkml.runtime.FastUtils.foreachFast
import eu.neverblink.linkml.runtime.{LocalizedText, MultilingualText, PlainText}
import eu.neverblink.linkml.schemaview.SchemaView

import java.io.OutputStream

/** Base class for the generators that output RDF.
  */
abstract class RdfGenerator[O] extends DocumentGenerator[O] {

  /** Push the generated triples into [[sink]]. */
  def generate(sink: RdfSink, options: O = defaultOptions): Unit

  final def writeTo(out: OutputStream, options: O = defaultOptions): Unit = {
    val sink = new Utf8ByteSink(out)
    generate(NTriplesRdfSink(sink), options)
    sink.flush()
  }

  final def serialize(options: O = defaultOptions): String = {
    val sink = new StringSink
    generate(NTriplesRdfSink(sink), options)
    sink.result
  }

  private var blankNodeCounter = 0

  /** Create a new simple blank node.
    */
  protected def blankNode(): BlankNode = {
    blankNodeCounter += 1
    new BlankNode(blankNodeCounter.toString)
  }

  /** Create an RDF list of the provided [[values]] and push it into the [[sink]].
    *
    * @param sink
    *   Sink to add the list to
    * @param values
    *   Values to include in the RDF list
    * @return
    *   Head node of the list or `rdf:nil` if [[values]] was empty
    */
  final def addList(sink: RdfSink, values: Seq[Node]): Resource =
    if (values.isEmpty) Rdf.nil
    else {
      val start = blankNode()
      var prev = start
      values.foreach {
        var i = 0
        value =>
          if (i == 0) {
            sink.triple(start, Rdf.first, values.head)
          } else {
            val cur = blankNode()
            sink.triple(prev, Rdf.rest, cur)
            sink.triple(cur, Rdf.first, value)
            prev = cur
          }
          i += 1
      }
      sink.triple(prev, Rdf.rest, Rdf.nil)
      start
    }

  /** Create namespace declarations for the root schema in the implicit [[SchemaView]] and push it
    * into the [[sink]].
    *
    * @param sink
    *   Sink to emit the prefixes to
    * @param additional
    *   Format-specific additional prefixes to emit
    * @param sv
    *   SchemaView to create the namespaces for
    */
  final def addNamespaces(sink: RdfSink, additional: Array[(String, String)])(using
      sv: SchemaView,
  ): Unit = {
    val root = sv.root
    // use tree-map for sorting prefixes in an alphabetic order
    val namespaces = new java.util.TreeMap[String, String]
    // add placeholder value for the default prefix
    root.defaultPrefix.foreachFast(namespaces.putIfAbsent(_, ""))
    // add placeholder values for allowed prefixes
    root.emitPrefixes.foreach(namespaces.putIfAbsent(_, ""))
    // fill in names for allowed prefixes only
    root.prefixes.foreach { kv =>
      namespaces.computeIfPresent(kv._1, (_, _) => kv._2.prefixReference.original)
    }
    // override format-specific prefixes
    additional.foreach(kv => namespaces.put(kv._1, kv._2))
    // emit all collected prefixes skipping place-holders
    namespaces.forEach((k, v) => if (v.nonEmpty) sink.namespace(k, v))
  }

  /** Create triples for a [[LocalizedText]]. Sinks triples with xsd:string literals for
    * [[PlainText]] or rdf:langString literals for [[MultilingualText]]
    */
  final def langStringProperty(
      sink: RdfSink,
      subject: Resource,
      predicate: Iri,
      localizedText: LocalizedText,
  ): Unit =
    localizedText match {
      case plain: PlainText => sink.triple(subject, predicate, Literal(plain.value))
      case lang: MultilingualText =>
        lang.mapping.foreach { (tag, value) =>
          sink.triple(subject, predicate, Literal(value, tag))
        }
    }

}
