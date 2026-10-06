package eu.neverblink.linkml.runtime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StringUtilsSpec extends AnyWordSpec, Matchers {
  "splitLines" should {
    "split at every kind of line break" in {
      StringUtils.splitLines("a\nb\rc\r\nd") shouldBe List("a", "b", "c", "d")
    }

    "remove trailing whitespace from each line" in {
      StringUtils.splitLines("a \t\n b  ") shouldBe List("a", " b")
    }

    "keep empty lines in the middle, but not after a final line break" in {
      StringUtils.splitLines("a\n\nb\n") shouldBe List("a", "", "b")
    }

    "match linesIterator" in {
      for text <- Seq("", "a", "\n", "\r\n\r", "a\r\n\nb \r", "x\n\ry") do
        StringUtils.splitLines(text) shouldBe text.linesIterator.map(_.stripTrailing).toList
    }
  }

  "hexDigit" should {
    "return the lowercase hex digit of the lowest 4 bits" in {
      (0 until 16).map(StringUtils.hexDigit).mkString shouldBe "0123456789abcdef"
      StringUtils.hexDigit(0x2028) shouldBe '8'
      StringUtils.hexDigit(0x2028 >> 4) shouldBe '2'
    }
  }
}
