package eu.neverblink.linkml.generator.owl

import eu.neverblink.linkml.generator.RdfGeneratorBase.RdfFormat
import eu.neverblink.linkml.generator.util.{JsonOutputFormat, JsonUtil}
import eu.neverblink.linkml.metamodel.Codec
import eu.neverblink.linkml.schemaview.SchemaView
import eu.neverblink.linkml.tests.ModelCatalogue
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets.UTF_8

class OwlImporterSpec extends AnyWordSpec, Matchers, OwlFixtures {
  import OwlImporterSpec.*

  "round-tripping" should {
    "give the same ontology again (linkml -> owl -> linkml -> owl)" when {
      for entry <- ModelCatalogue.all; format <- RdfFormat.values do
        s"model '${entry.name}' goes through $format" in {
          val first = OwlGenerator(using entry.model).ontology()
          val (sv, warnings) = reimport(entry.model, format)
          val second = OwlGenerator(using sv).ontology()
          withClue(s"Warnings:\n${warnings.mkString("\n")}\n") {
            difference(first, second) shouldBe empty
          }
        }
    }
  }

  "reading a document" should {
    "read Turtle, taking its prefix names" in {
      val ttl =
        """@prefix bk: <https://example.org/library/> .
          |@prefix owl: <http://www.w3.org/2002/07/owl#> .
          |@prefix : <https://example.org/other/> .
          |bk:Book a owl:Class ; owl:equivalentClass :Volume .
          |:Volume a owl:Class .
          |""".stripMargin
      val schema = OwlImporter().importSchema(stream(ttl))
      schema.defaultPrefix shouldBe Some("bk")
      schema.classes.keySet shouldBe Set("Book", "Volume")
      // LinkML doesn't allow an empty prefix.
      schema.prefixes.keySet should not contain ""
    }

    "read N-Triples with either parser" in {
      val nt = "<https://example.org/library/Book> " +
        "<http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/2002/07/owl#Class> .\n"
      for format <- RdfFormat.values do
        OwlImporter().importSchema(stream(nt), OwlImporter.Options(inputFormat = format))
          .classes.keySet shouldBe Set("Book")
    }
  }
}

object OwlImporterSpec {

  private def stream(text: String) = ByteArrayInputStream(text.getBytes(UTF_8))

  /** Generates OWL, imports it back and loads the schema. Also returns the import warnings. */
  def reimport(sv: SchemaView, format: RdfFormat = RdfFormat.ttl): (SchemaView, Seq[String]) = {
    val text = OwlGenerator(using sv).serialize(OwlGenerator.Options(format = format))
    val result =
      OwlImporter().importWithWarnings(stream(text), OwlImporter.Options(inputFormat = format))
    val yaml = JsonUtil.write(Codec.codec.encode(result.schema), JsonOutputFormat.yaml)
    val loaded = SchemaView.loadSchemaViewFromString(yaml) match {
      case Right(view) => view
      case Left(issues) => throw AssertionError(s"$issues\n$yaml")
    }
    (loaded, result.warnings)
  }

  /** One line per difference between the two ontologies, ignoring prefixes. */
  def difference(a: Ontology, b: Ontology): Seq[String] = {
    val header = Seq(
      Option.when(a.iri != b.iri)(s"iri: ${a.iri} vs ${b.iri}"),
      Option.when(a.versionIri != b.versionIri)(s"version: ${a.versionIri} vs ${b.versionIri}"),
      Option.when(a.imports.toSet != b.imports.toSet)(s"imports: ${a.imports} vs ${b.imports}"),
    ).flatten
    val annotations = (a.annotations.toSet diff b.annotations.toSet).map(x => s"- header $x") ++
      (b.annotations.toSet diff a.annotations.toSet).map(x => s"+ header $x")
    val axioms = (a.axioms.toSet diff b.axioms.toSet).map(x => s"- $x") ++
      (b.axioms.toSet diff a.axioms.toSet).map(x => s"+ $x")
    header ++ annotations.toSeq.sorted ++ axioms.toSeq.sorted
  }
}
