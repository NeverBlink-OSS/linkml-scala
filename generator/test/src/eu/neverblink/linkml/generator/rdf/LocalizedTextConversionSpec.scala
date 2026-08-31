package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.runtime.{LocalizedText, MultilingualText, PlainText}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LocalizedTextConversionSpec extends AnyWordSpec, Matchers {
  import LocalizedTextConversionSpec.TextOptions

  val s: Iri = Iri("urn:subject")
  val p: Iri = Iri("urn:property")
  val testGenerator: RdfGenerator[TextOptions] = new RdfGenerator[TextOptions] {
    override def generate(sink: RdfSink, options: TextOptions): Unit = {
      langStringProperty(sink, s, p, options.text)
    }

    override protected def defaultOptions: Nothing = fail()
  }

  "LocalizedText" should {
    "convert to a single xsd:string rdf literal" in {
      val sink = CollectingRdfSink()
      testGenerator.generate(
        sink,
        TextOptions(PlainText("hello")),
      )
      sink.triples shouldBe Seq(Triple(s, p, Literal("hello")))
    }

    "convert to a single rdf:langString literal" in {
      val sink = CollectingRdfSink()
      testGenerator.generate(
        sink,
        TextOptions(MultilingualText(Map("en" -> "hello"))),
      )
      sink.triples shouldBe Seq(Triple(s, p, LanguageLiteral("hello", "en")))
    }

    "convert to multiple rdf:langString literals" in {
      val sink = CollectingRdfSink()
      testGenerator.generate(
        sink,
        TextOptions(MultilingualText(Map("en" -> "hello", "pl" -> "cześć"))),
      )
      sink.triples should contain theSameElementsAs Seq(
        Triple(s, p, LanguageLiteral("hello", "en")),
        Triple(s, p, LanguageLiteral("cześć", "pl")),
      )
    }
  }
}

object LocalizedTextConversionSpec {

  /** The text to convert, wrapped as the options an [[RdfGenerator]] takes. */
  private[rdf] final case class TextOptions(
      text: LocalizedText,
      format: RdfFormat = RdfFormat.nt,
  ) extends RdfOptions
}
