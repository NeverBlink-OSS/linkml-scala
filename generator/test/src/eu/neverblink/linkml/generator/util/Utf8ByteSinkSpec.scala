package eu.neverblink.linkml.generator.util

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** [[Utf8ByteSink]] has to agree with `String.getBytes(UTF_8)` byte for byte to ensure good
  * handling of weird Unicode and stuff like emojis.
  */
class Utf8ByteSinkSpec extends AnyWordSpec, Matchers {

  private def cp(c: Int): String = new String(Character.toChars(c))

  /** Write via `append(String)`. */
  private def viaString(s: String, bufferSize: Int = 8 * 1024): Array[Byte] =
    written(bufferSize)(_.append(s))

  /** Write via `append(Char)`, one code unit at a time, which is where a pair can be split. */
  private def viaChars(s: String, bufferSize: Int = 8 * 1024): Array[Byte] =
    written(bufferSize)(sink => s.foreach(sink.append))

  private def written(bufferSize: Int)(write: Utf8ByteSink => Unit): Array[Byte] = {
    val out = new ByteArrayOutputStream
    val sink = new Utf8ByteSink(out, bufferSize)
    write(sink)
    sink.flush()
    out.toByteArray
  }

  private val samples = Map(
    "ASCII" -> "hello, world",
    "two-byte (U+00E9)" -> ("caf" + cp(0xe9)),
    "two-byte Polish" -> "wąż",
    "three-byte (U+20AC)" -> ("price " + cp(0x20ac)),
    "four-byte (U+1F3B8)" -> ("guitar " + cp(0x1f3b8)),
    "four-byte at the very start" -> (cp(0x1f600) + " smiles"),
    "four-byte at the very end" -> ("snake " + cp(0x1f40d)),
    "mixed widths" -> ("a" + cp(0xe9) + cp(0x20ac) + cp(0x1f389) + "z"),
    "adjacent supplementary" -> (cp(0x1f3b8) + cp(0x1f40d) + cp(0x1f389)),
    "empty" -> "",
  )

  "Utf8ByteSink" should {
    for ((name, s) <- samples) {
      s"match String.getBytes(UTF_8) for $name via append(String)" in {
        viaString(s) shouldBe s.getBytes(UTF_8)
      }

      s"match String.getBytes(UTF_8) for $name via append(Char)" in {
        // A surrogate pair split across two append(Char) calls must still encode as one code point.
        viaChars(s) shouldBe s.getBytes(UTF_8)
      }
    }

    "replace an unpaired high surrogate with '?'" in {
      val s = "a\ud83cz"
      viaString(s) shouldBe s.getBytes(UTF_8)
      viaChars(s) shouldBe s.getBytes(UTF_8)
      viaString(s) shouldBe Array[Byte]('a', '?', 'z')
    }

    "replace an unpaired low surrogate with '?'" in {
      val s = "a\udf89z"
      viaString(s) shouldBe s.getBytes(UTF_8)
      viaChars(s) shouldBe s.getBytes(UTF_8)
      viaString(s) shouldBe Array[Byte]('a', '?', 'z')
    }

    "replace a high surrogate left dangling at the end of the document" in {
      // flush() is the only place that can know the partner is never coming.
      val s = "ab\ud83c"
      viaString(s) shouldBe s.getBytes(UTF_8)
      viaChars(s) shouldBe s.getBytes(UTF_8)
      viaString(s) shouldBe Array[Byte]('a', 'b', '?')
    }

    "replace two high surrogates in a row" in {
      val s = "\ud83c\ud83c"
      viaString(s) shouldBe s.getBytes(UTF_8)
      viaChars(s) shouldBe s.getBytes(UTF_8)
    }

    "join a pair handed over in two separate append(String) calls" in {
      val out = new ByteArrayOutputStream
      val sink = new Utf8ByteSink(out)
      sink.append("x\ud83c")
      sink.append("\udfb8y")
      sink.flush()
      out.toByteArray shouldBe ("x" + cp(0x1f3b8) + "y").getBytes(UTF_8)
    }

    "produce identical bytes no matter where the buffer boundary lands" in {
      // Repeat a mixed-width string so multi-byte characters straddle every possible offset.
      val s = ("a" + cp(0xe9) + cp(0x20ac) + cp(0x1f3b8)) * 500
      val ref = s.getBytes(UTF_8)
      for (bufferSize <- Seq(16, 17, 19, 23, 64, 1000, 8 * 1024)) {
        withClue(s"bufferSize=$bufferSize: ") {
          viaString(s, bufferSize) shouldBe ref
          viaChars(s, bufferSize) shouldBe ref
        }
      }
    }

    "write nothing when nothing is appended" in {
      written(64)(_ => ()) shouldBe Array.emptyByteArray
    }
  }

  "Utf8ByteSink.appendAscii" should {
    "match append for every ASCII character" in {
      val ascii = (0 until 0x80).map(_.toChar).mkString
      written(8 * 1024)(sink => ascii.foreach(sink.appendAscii)) shouldBe ascii.getBytes(UTF_8)
    }

    "keep working across a buffer flush" in {
      val ascii = ("abcdefghij" * 500)
      for (bufferSize <- Seq(16, 17, 64, 1000)) {
        withClue(s"bufferSize=$bufferSize: ") {
          written(bufferSize)(sink => ascii.foreach(sink.appendAscii)) shouldBe ascii.getBytes(
            UTF_8,
          )
        }
      }
    }

    "interleave with the checked methods" in {
      val out = new ByteArrayOutputStream
      val sink = new Utf8ByteSink(out)
      sink.appendAscii('<')
      sink.append("caf" + cp(0xe9))
      sink.appendAscii('>')
      sink.append(cp(0x1f3b8))
      sink.appendAscii('.')
      sink.flush()
      out.toByteArray shouldBe ("<caf" + cp(0xe9) + ">" + cp(0x1f3b8) + ".").getBytes(UTF_8)
    }
  }
}
