package eu.neverblink.linkml.generator.rdf

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** JVM-specific tests for [[NTriplesOutput]]: the optimized buffered byte path must produce exactly
  * the same bytes as the cross-platform [[NTriplesWriter.writeToString]] path (UTF-8 encoded),
  * regardless of buffer boundaries.
  */
class NTriplesOutputSpec extends AnyWordSpec, Matchers {

  private def cp(c: Int): String = new String(Character.toChars(c))

  private def bytes(triples: Seq[Triple], bufferSize: Int = 8 * 1024): Array[Byte] = {
    val out = new ByteArrayOutputStream
    NTriplesOutput.writeTo(out, triples, bufferSize)
    out.toByteArray
  }

  private def expected(triples: Seq[Triple]): Array[Byte] =
    NTriplesWriter.writeToString(triples).getBytes(UTF_8)

  private val s = Iri("http://example.org/s")
  private val p = Iri("http://example.org/p")

  "NTriplesOutput.writeTo" should {
    "match the string writer for plain ASCII triples" in {
      val triples = Seq(
        Triple(s, p, Iri("http://example.org/o")),
        Triple(s, Rdf.`type`, Literal("hello")),
        Triple(BlankNode("b0"), p, Literal("42", XmlSchema.integer)),
      )
      bytes(triples) shouldBe expected(triples)
    }

    "match the string writer when literals contain escaped non-ASCII" in {
      val triples = Seq(Triple(s, p, Literal("caf" + cp(0xe9) + " " + cp(0x1f600))))
      val out = bytes(triples)
      out shouldBe expected(triples)
      // Escaped to US-ASCII, so every byte is ASCII.
      out.forall(b => (b & 0xff) < 0x80) shouldBe true
    }

    "UTF-8 encode a non-ASCII blank-node label (the fallback path)" in {
      val triples = Seq(Triple(BlankNode("b" + cp(0xe9)), p, Literal("x")))
      bytes(triples) shouldBe expected(triples)
    }

    "produce identical output across buffer boundaries" in {
      val triples = (0 until 5000).map { i =>
        Triple(Iri(s"http://example.org/s$i"), p, Literal(s"value $i with a tab\tand quote\""))
      }
      val ref = expected(triples)
      for (bufferSize <- Seq(16, 17, 64, 1000, 8 * 1024)) {
        withClue(s"bufferSize=$bufferSize: ") {
          bytes(triples, bufferSize) shouldBe ref
        }
      }
    }

    "write nothing for no triples" in {
      bytes(Nil) shouldBe Array.emptyByteArray
    }
  }
}
