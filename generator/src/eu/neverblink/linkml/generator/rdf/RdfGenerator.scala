package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.schemaview.SchemaView

/** Base utility class for common operations of generators that output RDF.
  */
abstract class RdfGenerator {
  private var blankNodeCounter = 0

  /** Create a new simple blank node.
    */
  protected def blankNode(): BlankNode = {
    blankNodeCounter += 1
    BlankNode(blankNodeCounter.toString)
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
  final def addList(sink: RdfSink, values: Seq[Node]): Resource = {
    if values.isEmpty then return Rdf.nil
    val start = blankNode()
    sink.triple(start, Rdf.first, values.head)
    var prev = start
    values.tail.foreach { value =>
      val cur = blankNode()
      sink.triple(prev, Rdf.rest, cur)
      sink.triple(cur, Rdf.first, value)
      prev = cur
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
    *   Schemaview to create the namespaces for
    */
  final def addNamespaces(sink: RdfSink, additional: Array[(String, String)])(using
      sv: SchemaView,
  ): Unit = {
    val toEmit = sv.root.emitPrefixes.toSet ++ sv.root.defaultPrefix
    sv.root.prefixes.values.toArray
      .collect {
        case p if toEmit(p.prefixPrefix) =>
          (p.prefixPrefix, p.prefixReference.value)
      }
      .appendedAll(additional)
      .distinct.sorted.foreach(sink.namespace)
  }
}
