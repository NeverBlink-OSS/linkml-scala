package eu.neverblink.linkml.generator.rdf

abstract class RdfGenerator {
  private var blankNodeCounter = 0

  protected def blankNode(): BlankNode = {
    blankNodeCounter += 1
    BlankNode(blankNodeCounter.toString)
  }

  protected def tripleIfDefined(
      sink: RdfSink,
      value: Option[String],
      datatype: Iri = ???,
  ): Unit = {}
}
