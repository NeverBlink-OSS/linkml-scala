package eu.neverblink.linkml.rdf

import scala.jdk.CollectionConverters.*

/** The vendored W3C test files, embedded as strings so that they can be read on every platform. See
  * the READMEs next to them.
  */
object W3cTestFiles {

  /** Result files of the Turtle test suite's evaluation tests, as `(file name, content)`. */
  def turtleResults: Seq[(String, String)] = list("turtle-w3c", ".nt")

  /** The RDF 1.1 N-Triples syntax tests, as `(file name, content)`. */
  def nTriplesSyntax: Seq[(String, String)] = list("ntriples-w3c", ".nt")

  private def list(dir: String, ext: String): Seq[(String, String)] = {
    val prefix = s"/$dir/"
    val files = Resources.map.asScala.toSeq
      .collect {
        case (path, content) if path.startsWith(prefix) && path.endsWith(ext) =>
          (path.stripPrefix(prefix), content)
      }
      .sortBy(_._1)
    if files.isEmpty then sys.error(s"No $ext files under $prefix")
    files
  }
}
