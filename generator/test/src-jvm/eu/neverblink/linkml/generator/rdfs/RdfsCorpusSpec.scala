package eu.neverblink.linkml.generator.rdfs

import eu.neverblink.linkml.generator.owl.OwlCorpusSpec.{configOf, text, triples, yamlOf}
import eu.neverblink.linkml.generator.owl.OwlImporter
import eu.neverblink.linkml.metamodel.SchemaDefinition
import eu.neverblink.linkml.rdf.*
import eu.neverblink.linkml.schemaview.SchemaView
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Imports the vocabularies in the `rdfs` folder of linkml-benchmark-schemas and writes them back
  * with [[RdfsGenerator]], checking what comes back of their classes, properties and documentation.
  */
class RdfsCorpusSpec extends AnyWordSpec, Matchers {
  import RdfsCorpusSpec.*

  private def rdfsOf(schema: SchemaDefinition): Set[Triple] = {
    val view = SchemaView.loadSchemaViewFromString(yamlOf(schema)) match {
      case Right(sv) => sv
      case Left(issues) => fail(s"The schema does not load: $issues")
    }
    val sink = CollectingRdfSink()
    RdfsGenerator(using view).generate(sink)
    sink.triples.toSet
  }

  for (name, minimumKept) <- vocabularies do
    s"The vocabulary $name" when {
      lazy val graph = triples(text(name))
      lazy val rdfs = rdfsOf(OwlImporter().importTriples(graph, configOf(name)).schema)

      "imported and written back as RDFS" should {
        "give the same RDFS when that RDFS is imported and written back" in {
          unstable.get(name).foreach(reason => assume(false, reason))
          val again = rdfsOf(OwlImporter().importTriples(rdfs.toSeq, configOf(name)).schema)
          val (missing, added) = (rdfs.diff(again), again.diff(rdfs))
          if missing.nonEmpty || added.nonEmpty then
            fail(
              (missing.take(5).map(t => s"- $t") ++ added.take(5).map(t => s"+ $t")).mkString("\n"),
            )
        }

        s"keep at least $minimumKept% of its classes, properties and documentation" in {
          val original = graph.filter(isRdfsStatement).toSet
          assume(original.nonEmpty, "The vocabulary has no classes or properties")
          val kept = 100 * original.count(rdfs) / original.size
          info(s"Kept $kept% of ${original.size} statements")
          if kept < minimumKept then
            fail(s"Kept $kept%. Lost, for example:\n${original.diff(rdfs).take(10).mkString("\n")}")
        }
      }
    }
}

object RdfsCorpusSpec {

  /** Each vocabulary with the minimum percent of its statements that must survive the round trip,
    * set a little below the current result.
    */
  val vocabularies: Seq[(String, Int)] = Seq(
    "shacl" -> 85,
    "reproschema" -> 96,
    "schemaorg" -> 92,
    "dcterms" -> 93,
    "dcelements" -> 100,
    "dcmitype" -> 100,
    "geo-wgs84" -> 88,
    "web-annotation" -> 95,
    "hydra" -> 94,
  )

  /** Vocabularies whose RDFS changes on a second trip, and why. */
  private val unstable: Map[String, String] = Map(
    "schemaorg" ->
      ("A property with several ranges gets none in RDFS, so the enums used only there come back " +
        "as classes"),
  )

  /** Whether the triple `t` is an RDFS statement (axiom). */
  private def isRdfsStatement(t: Triple): Boolean = t match {
    case Triple(_, Rdf.`type`, o) => o == Rdfs.Class || o == Rdf.Property
    case Triple(_, Rdfs.range, o) => o != Rdfs.Literal && o != Rdfs.Resource
    case Triple(_, p, _) =>
      Set(Rdfs.subClassOf, Rdfs.subPropertyOf, Rdfs.domain, Rdfs.label, Rdfs.comment)(p)
  }
}
