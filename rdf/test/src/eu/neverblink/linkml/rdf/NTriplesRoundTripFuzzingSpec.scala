package eu.neverblink.linkml.rdf

import eu.neverblink.linkml.rdf.io.Utf8ByteSink
import org.scalacheck.Gen
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

import java.io.{ByteArrayInputStream, ByteArrayOutputStream}

/** Random triples through [[NTriplesWriter]] and back through [[NTriplesParser]]. */
class NTriplesRoundTripFuzzingSpec extends AnyWordSpec, Matchers, ScalaCheckPropertyChecks {
  import NTriplesRoundTripFuzzingSpec.*

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 500)

  "NTriplesParser" should {
    "read back exactly what NTriplesWriter writes, through any buffer size" in {
      forAll(Gen.listOf(triple), Gen.choose(16, 256)) { (triples, bufferSize) =>
        val out = new ByteArrayOutputStream
        val sink = new Utf8ByteSink(out)
        val writer = new NTriplesWriter(sink)
        triples.foreach(t => writer.triple(t.subj, t.pred, t.obj))
        sink.flush()

        val collected = new CollectingRdfSink
        NTriplesParser.parse(new ByteArrayInputStream(out.toByteArray), collected, bufferSize)
        collected.triples shouldBe triples
      }
    }
  }
}

object NTriplesRoundTripFuzzingSpec {

  /** Any UTF-16 string: ASCII, controls, BMP, astral pairs and lone surrogates. */
  private val text: Gen[String] = Gen.listOf(
    Gen.frequency(
      8 -> Gen.choose(0x20.toChar, 0x7e.toChar).map(_.toString),
      2 -> Gen.choose(0.toChar, 0x1f.toChar).map(_.toString),
      2 -> Gen.choose(0x80.toChar, 0xd7ff.toChar).map(_.toString),
      1 -> Gen.choose(0xd800.toChar, 0xdfff.toChar).map(_.toString),
      2 -> Gen.choose(0x10000, 0x10ffff).map(cp => new String(Character.toChars(cp))),
    ),
  ).map(_.mkString)

  private val iri: Gen[Iri] = for
    scheme <- Gen.oneOf("http", "urn", "a+b.c-d")
    rest <- text
  yield Iri(s"$scheme:$rest")

  private val blankNode: Gen[BlankNode] = for
    first <- Gen.alphaNumChar
    rest <- Gen.listOf(Gen.oneOf(Gen.alphaNumChar, Gen.oneOf('_', '-', '.')))
    last <- Gen.alphaNumChar
  yield BlankNode(s"$first${rest.mkString}$last")

  private val languageTag: Gen[String] = for
    primary <- Gen.nonEmptyListOf(Gen.alphaChar)
    subtags <- Gen.listOf(Gen.nonEmptyListOf(Gen.alphaNumChar))
  yield (primary.mkString +: subtags.map(_.mkString)).mkString("-")

  private val literal: Gen[Node] = Gen.oneOf(
    text.map(Literal(_)),
    for v <- text; dt <- iri yield Literal(v, dt),
    for v <- text; lang <- languageTag yield LanguageLiteral(v, lang),
  )

  val triple: Gen[Triple] = for
    subj <- Gen.oneOf(iri, blankNode)
    pred <- iri
    obj <- Gen.oneOf(iri, blankNode, literal)
  yield Triple(subj, pred, obj)
}
