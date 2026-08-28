package eu.neverblink.linkml.generator.rdf

import eu.neverblink.linkml.runtime.{LocalizedText, MultilingualText, PlainText}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class LocalizedTextConversionSpec extends AnyWordSpec, Matchers {
  val s: Iri = Iri("urn:subject")
  val p: Iri = Iri("urn:property")
  val testGenerator: RdfGenerator[LocalizedText] = new RdfGenerator[LocalizedText] {
    override def generate(sink: RdfSink, text: LocalizedText): Unit = {
      langStringProperty(sink, s, p, text)
    }

    override protected def defaultOptions: Nothing = fail()
  }

  "LocalizedText" should {
    "convert to a single xsd:string rdf literal" in {
      val sink = CollectingRdfSink()
      testGenerator.generate(
        sink,
        PlainText("hello"),
      )
      sink.triples shouldBe Seq(Triple(s, p, Literal("hello")))
    }

    "convert to a single rdf:langString literal" in {
      val sink = CollectingRdfSink()
      testGenerator.generate(
        sink,
        MultilingualText(Map("en" -> "hello")),
      )
      sink.triples shouldBe Seq(Triple(s, p, LanguageLiteral("hello", "en")))
    }

    "convert to multiple rdf:langString literals" in {
      val sink = CollectingRdfSink()
      testGenerator.generate(
        sink,
        MultilingualText(Map("en" -> "hello", "pl" -> "cześć")),
      )
      sink.triples should contain theSameElementsAs Seq(
        Triple(s, p, LanguageLiteral("hello", "en")),
        Triple(s, p, LanguageLiteral("cześć", "pl")),
      )
    }
  }
}
