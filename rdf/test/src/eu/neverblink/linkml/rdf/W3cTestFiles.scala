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

  enum TurtleTestType:
    case Eval, PositiveSyntax, NegativeSyntax, NegativeEval

  /** One entry of the Turtle test suite's manifest.
    *
    * @param result
    *   the expected N-Triples, for an evaluation test
    */
  final case class TurtleTest(
      name: String,
      testType: TurtleTestType,
      input: String,
      result: Option[String],
      baseIri: String,
  )

  /** The Turtle test suite, as listed in its manifest. The manifest is itself Turtle, so this reads
    * it with [[TurtleParser]].
    */
  lazy val turtleTests: Seq[TurtleTest] = {
    val home = "http://www.w3.org/2013/TurtleTests/"
    val mf = "http://www.w3.org/2001/sw/DataAccess/tests/test-manifest#"
    val rdft = "http://www.w3.org/ns/rdftest#"
    val sink = new CollectingRdfSink
    TurtleParser.parse(read("turtle-w3c", "manifest.ttl"), sink, Some(home + "manifest.ttl"))
    val bySubject = sink.triples.groupBy(_.subj)

    def value(subj: Resource, pred: String): Option[String] =
      bySubject(subj).collectFirst {
        case Triple(_, Iri(`pred`), Iri(v)) => v
        case Triple(_, Iri(`pred`), Literal(v, _)) => v
      }

    def file(iri: String): String = iri.stripPrefix(home)

    bySubject.keys.toSeq
      .flatMap { subj =>
        value(subj, Rdf.`type`.value)
          .filter(_.startsWith(rdft + "TestTurtle"))
          .map(t => TurtleTestType.valueOf(t.stripPrefix(rdft + "TestTurtle")))
          .map { testType =>
            val action = value(subj, mf + "action").get
            TurtleTest(
              // Not mf:name, which the manifest gets wrong for one test.
              subj.asInstanceOf[Iri].value.stripPrefix(home + "manifest.ttl#"),
              testType,
              read("turtle-w3c", file(action)),
              value(subj, mf + "result").map(r => read("turtle-w3c", file(r))),
              action,
            )
          }
      }
      .sortBy(_.name)
  }

  private def read(dir: String, file: String): String =
    Option(Resources.map.get(s"/$dir/$file")).getOrElse(sys.error(s"No $file in $dir"))

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
