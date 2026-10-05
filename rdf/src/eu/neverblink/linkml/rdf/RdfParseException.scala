package eu.neverblink.linkml.rdf

/** A syntax error in an RDF document.
  *
  * @param line
  *   1-based line number
  * @param column
  *   1-based column, in code points
  */
final class RdfParseException(val reason: String, val line: Int, val column: Int)
    extends RuntimeException(s"$reason (line $line, column $column)")
