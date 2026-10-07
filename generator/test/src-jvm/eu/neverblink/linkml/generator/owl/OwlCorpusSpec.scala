package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.owl.Axiom.*
import eu.neverblink.linkml.generator.owl.config.{
  ImportMappingImpl,
  OwlImportConfigImpl,
  OwlImportConfigs,
}
import eu.neverblink.linkml.generator.rdf.RdfFormat
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, JsonUtil}
import eu.neverblink.linkml.metamodel.Codec
import eu.neverblink.linkml.metamodel.SchemaDefinitionImpl
import eu.neverblink.linkml.rdf.{CollectingRdfSink, NTriplesParser, Triple}
import eu.neverblink.linkml.schemaview.{MapImporter, SchemaView}
import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.GZIPInputStream

import scala.collection.mutable

/** Imports real ontologies from the `owl` and `rdfs` folders of linkml-benchmark-schemas and checks
  * the schemas and the OWL generated back from them. Ontologies that import others from the corpus
  * are also tested with those imports mapped to their schemas.
  */
class OwlCorpusSpec extends AnyWordSpec, Matchers {
  import OwlCorpusSpec.*

  /** For each ontology: its own IRIs, version IRIs included, and the IRIs it imports. */
  private val headers: Map[String, (Set[String], Seq[String])] =
    ontologies.map(_._1).filter(n => fileOf(n).isDefined).map { name =>
      val t = text(name)
      // Vocabularies without an `owl:Ontology`, like DC terms, are known by their namespace.
      val iris = Some(ontologyPattern.findAllMatchIn(t).map(_.group(1)).toSet).filter(_.nonEmpty)
        .getOrElse(namespacePattern.findAllMatchIn(t).map(_.group(1)).toSet) ++
        versionPattern.findAllMatchIn(t).map(_.group(1))
      name -> (iris, importPattern.findAllMatchIn(t).map(_.group(1)).toSeq.distinct)
    }.toMap

  private def importsOf(name: String): Seq[String] =
    headers.get(name).toSeq.flatMap(_._2).flatMap(i =>
      headers.collectFirst { case (other, (iris, _)) if iris(i) => other },
    ).distinct

  /** Maps each import to its schema file. `files` holds the YAML of those schemas by file name. */
  private def mappedTo(
      name: String,
      imports: Seq[String],
      files: Map[String, String],
  ): OwlImporter.Options =
    OwlImporter.Options(
      configOf(name).copy(imports =
        imports.flatMap(d => headers(d)._1.map(i => i -> ImportMappingImpl(i, s"$d.yaml"))).toMap,
      ),
      schemas = MapImporter(files.toSeq*),
    )

  private val importedWithImports =
    mutable.Map.empty[String, (OwlImporter.Result, String, Map[String, String])]

  /** Imports an ontology after its imports, recursively. Returns the result, its YAML, and the YAML
    * of every schema it imports (directly or not) by file name.
    */
  private def importWithImports(name: String): (OwlImporter.Result, String, Map[String, String]) =
    importedWithImports.getOrElse(
      name, {
        val imports = importsOf(name)
        val files = imports.flatMap { d =>
          val (_, yaml, theirs) = importWithImports(d)
          theirs + (s"$d.yaml" -> yaml)
        }.toMap
        val result =
          OwlImporter().importTriples(triples(text(name)), mappedTo(name, imports, files))
        val imported = (result, yamlOf(result.schema), files)
        importedWithImports(name) = imported
        imported
      },
    )

  for (name, minimumKept) <- ontologies do
    s"The ontology $name" when {
      lazy val source = text(name)
      lazy val graph = triples(source)
      lazy val imported = OwlImporter().importTriples(graph, configOf(name))
      lazy val yaml = yamlOf(imported.schema)
      lazy val view = SchemaView.loadSchemaViewFromString(yaml) match {
        case Right(sv) => sv
        case Left(issues) => fail(s"The schema does not load: $issues")
      }
      lazy val regenerated = OwlGenerator(using view).ontology()

      "imported" should {
        "give a schema without validation problems" in {
          val problems = view.validationProblems
          if problems.nonEmpty then fail(problems.take(10).mkString("\n"))
        }

        "give the same schema when the OWL generated from it is imported" in {
          val again = OwlImporter().importOntology(regenerated, configOf(name)).schema
          sameSchema(imported.schema, again)
        }

        // Many published ontologies aren't in OWL 2 DL. So only fail on problems about terms
        // that had no problem in the original.
        "give OWL with no OWL 2 DL problems that the ontology does not have" in {
          val nt = OwlGenerator(using view).serialize(OwlGenerator.Options(format = RdfFormat.nt))
          val known = OwlApi.dlViolationsAbout(OwlApi.load(source)).flatMap(_._2).toSet
          val added = OwlApi.dlViolationsAbout(OwlApi.load(nt))
            .collect { case (v, about) if !about.exists(known) => v }
          if added.nonEmpty then fail(added.take(10).mkString("\n"))
        }

        s"keep at least $minimumKept% of the logical axioms on the way back to OWL" in {
          val original = normalize(OwlRdfReader.read(graph).ontology.axioms).filter(logical)
          val back = normalize(regenerated.axioms)
          assume(original.nonEmpty, "The ontology has no logical axioms")
          val kept = 100 * original.count(back) / original.size
          info(s"Kept $kept% of ${original.size} logical axioms")
          if kept < minimumKept then
            fail(s"Kept $kept%. Warnings:\n${imported.warnings.mkString("\n")}")
        }
      }

      val imports = importsOf(name)
      if imports.nonEmpty then
        s"imported with ${imports.mkString(", ")} mapped to their schemas" should {
          lazy val (result, yaml, files) = importWithImports(name)
          lazy val view =
            SchemaView.loadSchemaViewFromString(yaml, MapImporter(files.toSeq*)) match {
              case Right(sv) => sv
              case Left(issues) => fail(s"The schema does not load with its imports: $issues")
            }

          "read the schemas of its imports, and import them" in {
            val unread =
              result.warnings.filter(_.startsWith("Imported LinkML schema that could not"))
            unread shouldBe empty
            result.schema.imports.map(_.original) should contain allElementsOf
              imports.map(d => s"$d.yaml")
          }

          "give a schema that loads with them without problems" in {
            val problems = view.validationProblems
            if problems.nonEmpty then fail(problems.take(10).mkString("\n"))
          }

          "define none of the terms they define" in {
            // Skip types: several, including LinkML's own, can share one datatype URI.
            val elements =
              view.classes.values.toSeq ++ view.slotDefinitions.values ++ view.enums.values
            val (own, theirs) = elements.partition(_.definingSchema eq view.root)
            own.map(_.uriStr).toSet.intersect(theirs.map(_.uriStr).toSet) shouldBe empty
          }

          "give the same schema when its own OWL, which imports theirs, is imported" in {
            val owl = OwlGenerator(using view).ontology(OwlGenerator.Options(onlyRootSchema = true))
            val again = OwlImporter().importOntology(owl, mappedTo(name, imports, files), Nil)
            sameSchema(result.schema, again.schema)
          }
        }
    }

  /** Reports only the fields that differ: a diff of whole schemas takes too long to print. */
  private def sameSchema(first: SchemaDefinitionImpl, again: SchemaDefinitionImpl): Unit =
    if again != first then fail(differences("schema", first, again).take(8).mkString("\n"))

  private def differences(path: String, a: Any, b: Any): Seq[String] = (a, b) match {
    case _ if a == b => Nil
    case (x: collection.Map[?, ?], y: collection.Map[?, ?]) =>
      val keys = (x.keySet ++ y.keySet).toSeq.map(_.toString).sorted
      val (xs, ys) = (x.map((k, v) => k.toString -> v), y.map((k, v) => k.toString -> v))
      keys.flatMap(k => differences(s"$path.$k", xs.get(k), ys.get(k)))
    case (Some(x), Some(y)) => differences(path, x, y)
    case (x: Seq[?], y: Seq[?]) if x.size == y.size =>
      x.zip(y).zipWithIndex.flatMap { case ((u, v), i) => differences(s"$path[$i]", u, v) }
    case (x: Product, y: Product) if x.getClass == y.getClass && x.productArity > 0 =>
      x.productElementNames.zip(x.productIterator.zip(y.productIterator)).toSeq
        .flatMap { case (field, (u, v)) => differences(s"$path.$field", u, v) }
    case _ => Seq(s"$path:\n  ${a.toString.take(300)}\n  ${b.toString.take(300)}")
  }
}

object OwlCorpusSpec {

  /** The `owl` and `rdfs` folders of linkml-benchmark-schemas. */
  private val corpora: Seq[os.Path] =
    Seq("LINKML_OWL_CORPUS", "LINKML_RDFS_CORPUS").flatMap(v =>
      Option(System.getenv(v)).filter(_.nonEmpty).map(os.Path(_, os.pwd)),
    )

  private val inCi: Boolean = Option(System.getenv("CI")).exists(_.nonEmpty)

  def fileOf(name: String): Option[os.Path] =
    corpora.map(_ / name / "main.nt.gz").find(os.exists)

  /** The N-Triples of an ontology. Cancels the test if the corpus is missing, and fails it in CI.
    */
  def text(name: String): String = {
    val file = fileOf(name).getOrElse {
      val hint = "Set LINKML_OWL_CORPUS and LINKML_RDFS_CORPUS to the `owl` and `rdfs` folders " +
        "of linkml-benchmark-schemas"
      if inCi then Assertions.fail(s"$name is missing. $hint") else Assertions.cancel(hint)
    }
    val in = GZIPInputStream(os.read.inputStream(file))
    try String(in.readAllBytes(), UTF_8)
    finally in.close()
  }

  def triples(source: String): Seq[Triple] = {
    val sink = CollectingRdfSink()
    NTriplesParser.parse(source, sink)
    sink.triples
  }

  def yamlOf(schema: SchemaDefinitionImpl): String =
    JsonUtil.write(Codec.codec.encode(schema), JsonOutputFormat.yaml)

  /** Each ontology with the minimum percent of logical axioms that must survive the round trip, set
    * a little below the current result. Most losses are things LinkML can't express: individuals,
    * property chains, defined classes and restrictions on inverse properties.
    */
  val ontologies: Seq[(String, Int)] = Seq(
    "sosa" -> 100,
    "ssn" -> 80,
    "prov-o" -> 82,
    "shacl" -> 46,
    "reproschema" -> 33,
    "foaf-snippet" -> 100,
    "foaf" -> 85,
    "skos" -> 92,
    "dcat3" -> 90,
    "org" -> 92,
    "time" -> 74,
    "saref" -> 85,
    "saref4ener" -> 93,
    "gist" -> 75,
    "d3fend" -> 61,
    "fabio" -> 85,
    "frbr" -> 88,
    "cito" -> 95,
    "fibo-agents" -> 0,
    "fibo-annotation-vocabulary" -> 20,
    "commons-annotation-vocabulary" -> 10,
    "commons-classifiers" -> 80,
    "commons-collections" -> 80,
    "commons-designators" -> 80,
    "commons-text-datatype" -> 0,
    "bfo" -> 90,
    "ro-core" -> 85,
    "schemaorg" -> 60,
    "dcterms" -> 78,
    "odrl" -> 30,
    "dcelements" -> 0,
    "dcmitype" -> 100,
    "geo-wgs84" -> 100,
    "web-annotation" -> 100,
    "hydra" -> 94,
  )

  /** PROV-O and DC terms reuse names from SKOS and from each other. DCAT imports all of them, and
    * LinkML names must be unique across schemas, so some terms are renamed.
    */
  def configOf(name: String): OwlImportConfigImpl = name match {
    case "prov-o" =>
      OwlImportConfigs.parse(
        """renames:
          |  prov:Collection: ProvCollection
          |  prov:definition: prov_definition
          |  prov:editorialNote: prov_editorial_note
          |""".stripMargin,
      )
    case "dcterms" =>
      OwlImportConfigs.parse(
        """renames:
          |  dcterms:Agent: DctermsAgent
          |  dcterms:Location: DctermsLocation
          |  http://purl.org/dc/dcmitype/Collection: DcmiCollection
          |""".stripMargin,
      )
    case _ => OwlImportConfigImpl()
  }

  private val ontologyPattern =
    "<([^>]+)> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Ontology>".r
  private val namespacePattern = "(?m)^<([^>]+[/#])> ".r
  private val versionPattern = "<http://www.w3.org/2002/07/owl#versionIRI> <([^>]+)>".r
  private val importPattern = "<http://www.w3.org/2002/07/owl#imports> <([^>]+)>".r

  private val logical: Axiom => Boolean = {
    case _: Declaration | _: AnnotationAssertion => false
    case _ => true
  }

  private val vacuous: Set[Any] = Set(
    ClassExpr.Thing,
    ClassExpr.Named("http://www.w3.org/2000/01/rdf-schema#Resource"),
    DataRange.Literal,
  )

  /** Puts axioms into one form, so that the same statement compares equal however it was written.
    * Drops axioms that are always true, such as a subclass of `owl:Thing`.
    */
  def normalize(axioms: Seq[Axiom]): Set[Axiom] = axioms.flatMap {
    case InverseProperties(a, b, anns) => Seq(InverseProperties(Seq(a, b).min, Seq(a, b).max, anns))
    case DisjointClasses(ops, anns) =>
      ops.combinations(2).map(pair => DisjointClasses(pair.sortBy(_.toString), anns)).toSeq
    case DisjointProperties(ops, anns) =>
      ops.combinations(2).map(pair => DisjointProperties(pair.sorted, anns)).toSeq
    case EquivalentClasses(ops, anns) => Seq(EquivalentClasses(ops.sortBy(_.toString), anns))
    case EquivalentProperties(ops, anns) => Seq(EquivalentProperties(ops.sorted, anns))
    case SubClassOf(sub, ClassExpr.Cardinality(ClassExpr.Bound.Min, 1, p, Some(f)), anns) =>
      Seq(SubClassOf(sub, ClassExpr.SomeValuesFrom(p, f), anns))
    case SubClassOf(_, sup, _) if vacuous(sup) => Nil
    case Range(_, r, _) if vacuous(r) => Nil
    case Domain(_, d, _) if vacuous(d) => Nil
    case other => Seq(other)
  }.toSet
}
