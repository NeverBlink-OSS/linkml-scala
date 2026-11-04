package eu.neverblink.linkml.generator.docs

import eu.neverblink.linkml.rdf.io.StringSink
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MarkdownSpec extends AnyWordSpec, Matchers {

  private def written(write: Markdown => Unit): String = {
    val sink = new StringSink
    write(new Markdown(sink, 0))
    sink.result
  }

  /** [[write]] as the only cell of a table row, without the row's own `| ` and ` |`. */
  private def inCell(write: Markdown => Unit): String = {
    val row = written { md =>
      md.startRow()
      write(md)
      md.endRow()
    }
    row.stripPrefix("| ").stripSuffix(" |\n")
  }

  "Markdown.text" should {
    "leave plain names alone" in {
      written(_.text("Person")) shouldBe "Person"
      written(_.text("has_part")) shouldBe "has_part"
      written(_.text("a name with spaces")) shouldBe "a name with spaces"
    }

    "escape characters that Markdown or MDX read as syntax" in {
      written(_.text("*a*")) shouldBe "\\*a\\*"
      written(_.text("[x](y)")) shouldBe "\\[x\\](y)"
      written(_.text("<b>")) shouldBe "\\<b\\>"
      written(_.text("{x}")) shouldBe "\\{x\\}"
      written(_.text("`c`")) shouldBe "\\`c\\`"
      written(_.text("a\\b")) shouldBe "a\\\\b"
      written(_.text("a&b#c~d")) shouldBe "a\\&b\\#c\\~d"
    }

    "escape an underscore only where it can start or end emphasis" in {
      written(_.text("_private_")) shouldBe "\\_private\\_"
      written(_.text("a_b")) shouldBe "a_b"
      written(_.text("a _b")) shouldBe "a \\_b"
    }

    "keep a name on one line" in {
      written(_.text("a\nb")) shouldBe "a b"
      written(_.text("a\rb")) shouldBe "a b"
    }
  }

  "Markdown.oneLine" should {
    "keep only the first paragraph, on one line" in {
      written(_.oneLine("First line\nsecond line.\n\nSecond paragraph.")) shouldBe
        "First line second line."
    }

    "treat a line of spaces as a paragraph break" in {
      written(_.oneLine("One.\n   \nTwo.")) shouldBe "One."
    }

    "leave out the whitespace at either end" in {
      written(_.oneLine("  \n Text. \n")) shouldBe "Text."
    }

    "write a text on one line as it is" in {
      written(_.oneLine("A plain description.")) shouldBe "A plain description."
    }

    "join a run of spaces or tabs into one space" in {
      written(_.oneLine("a  b")) shouldBe "a b"
      written(_.oneLine("a  b\tc \t d")) shouldBe "a b c d"
    }

    "keep Markdown in the text" in {
      written(_.oneLine("Use `x` or **y**.")) shouldBe "Use `x` or **y**."
    }
  }

  "Markdown.allOnOneLine" should {
    "keep all paragraphs, with a break between them" in {
      written(
        _.allOnOneLine("First line\nsecond line.\n   \nSecond paragraph.\n\n\nThird.\n"),
      ) shouldBe
        "First line second line.<br><br>Second paragraph.<br><br>Third."
    }
  }

  "Markdown.note" should {
    "keep all lines of the text in the quote" in {
      written(_.note("Deprecated", "\nUse New.\r\nIt is better.\n\nSee **New**.\n")) shouldBe
        "> **Deprecated:** Use New.\n> It is better.\n>\n> See **New**.\n\n"
    }
  }

  "Markdown.code" should {
    "use a fence longer than any backtick run in the text" in {
      written(_.code("a")) shouldBe "`a`"
      written(_.code("a`b")) shouldBe "``a`b``"
      written(_.code("`a`")) shouldBe "`` `a` ``"
    }

    "keep the text on one line, whatever its line breaks" in {
      written(_.code("a\nb")) shouldBe "`a b`"
      written(_.code("a\r\nb")) shouldBe "`a b`"
      written(_.code("a\rb")) shouldBe "`a b`"
    }
  }

  "Markdown.codeBlock" should {
    "end the text with a line break" in {
      written(_.codeBlock("text", "x")) shouldBe "```text\nx\n```\n\n"
      written(_.codeBlock("text", "x\n")) shouldBe "```text\nx\n```\n\n"
    }

    "use a fence longer than any backtick run in the text" in {
      written(_.codeBlock("text", "a\n```\nb")) shouldBe "````text\na\n```\nb\n````\n\n"
    }
  }

  "Markdown.number" should {
    "write the decimal digits" in {
      written(_.number(0)) shouldBe "0"
      written(_.number(1234)) shouldBe "1234"
      written(_.number(-7)) shouldBe "-7"
      written(_.number(Int.MinValue)) shouldBe Int.MinValue.toString
    }
  }

  "a table row" should {
    "escape each pipe in a cell, including in code spans" in {
      inCell(_.append("a|b")) shouldBe "a\\|b"
      inCell(_.code("x|y")) shouldBe "`x\\|y`"
      inCell(_.oneLine("x | y")) shouldBe "x \\| y"
      inCell(_.text("a|b")) shouldBe "a\\|b"
    }

    "not escape a pipe twice" in {
      inCell(_.append("a\\|b")) shouldBe "a\\|b"
      inCell(_.oneLine("a\\|b")) shouldBe "a\\|b"
      inCell(_.code("a\\|b")) shouldBe "`a\\|b`"
    }

    "count the backslashes before a pipe across writes" in {
      inCell { md =>
        md.append("a\\")
        md.append("|b")
      } shouldBe "a\\|b"
      inCell { md =>
        md.append("a\\\\")
        md.append("|b")
      } shouldBe "a\\\\\\|b"
      inCell { md =>
        md.append("\\")
        md.append("x")
        md.append("|")
      } shouldBe "\\x\\|"
    }

    "escape a pipe that follows an escaped backslash" in {
      inCell(_.text("a\\|b")) shouldBe "a\\\\\\|b"
    }

    "separate the cells" in {
      written { md =>
        md.startRow()
        md.append("a")
        md.nextCell()
        md.append("b")
        md.endRow()
      } shouldBe "| a | b |\n"
    }

    "leave pipes outside a row alone" in {
      written(_.append("a|b")) shouldBe "a|b"
    }
  }

  "Markdown.heading" should {
    "shift the level by the offset" in {
      val sink = new StringSink
      new Markdown(sink, 1).heading(1, "Title")
      sink.result shouldBe "## Title\n\n"
    }
  }

  "Markdown.paragraph" should {
    "leave out the whitespace around the text" in {
      written(_.paragraph("\n  Text\nmore.  \n")) shouldBe "Text\nmore.\n\n"
    }

    "drop carriage returns" in {
      written(_.paragraph("a\r\nb")) shouldBe "a\nb\n\n"
    }

    "close a code block that the text opens but doesn't close" in {
      // Otherwise the rest of the page would be part of the code block
      written(_.paragraph("a\n```scala\nval x = 1")) shouldBe "a\n```scala\nval x = 1\n```\n\n"
      written(_.paragraph("~~~~\ncode\n~~~")) shouldBe "~~~~\ncode\n~~~\n~~~~\n\n"
      written(_.paragraph("  ```\ncode")) shouldBe "```\ncode\n```\n\n"
    }

    "leave a closed code block alone" in {
      written(_.paragraph("```\ncode\n```\nb")) shouldBe "```\ncode\n```\nb\n\n"
      written(_.paragraph("````\n```\n````")) shouldBe "````\n```\n````\n\n"
      // Not a fence: a backtick in the info string, or fewer than 3 backticks
      written(_.paragraph("``` a`b\nc")) shouldBe "``` a`b\nc\n\n"
      written(_.paragraph("``code``")) shouldBe "``code``\n\n"
    }
  }

  "Markdown from the schema" should {
    // What the reader doesn't see, written to keep a tag or a link from being one
    val z = "\u200b"

    "keep the tags for plain formatting, without attributes" in {
      written(_.oneLine("I<sub>a</sub> and <i>U</i><br><BR/><br />")) shouldBe
        "I<sub>a</sub> and <i>U</i><br><BR/><br />"
      written(_.paragraph("<ul>\n<li>a</li>\n</ul>")) shouldBe "<ul>\n<li>a</li>\n</ul>\n\n"
    }

    "keep other tags from being tags" in {
      written(_.oneLine("<script>alert(1)</script>")) shouldBe
        s"<${z}script>alert(1)<${z}/script>"
      written(
        _.oneLine("<img src=x onerror=alert(1)>"),
      ) shouldBe s"<${z}img src=x onerror=alert(1)>"
      written(_.oneLine("""<b onclick="alert(1)">x</b>""")) shouldBe
        s"""<${z}b onclick="alert(1)">x</b>"""
      written(_.oneLine("""<font color="red">x</font>""")) shouldBe
        s"""<${z}font color="red">x<${z}/font>"""
      written(_.oneLine("<!-- x --> <?php ?>")) shouldBe s"<${z}!-- x --> <${z}?php ?>"
      written(_.oneLine("List<String>")) shouldBe s"List<${z}String>"
    }

    "leave a < that cannot start a tag alone" in {
      written(_.oneLine("a < b, 1<2, <>, a<")) shouldBe "a < b, 1<2, <>, a<"
    }

    "keep autolinks with a safe scheme" in {
      written(_.oneLine("<https://w3id.org/linkml> <HTTP://x> <mailto:a@b.c>")) shouldBe
        "<https://w3id.org/linkml> <HTTP://x> <mailto:a@b.c>"
      written(_.oneLine("<javascript:alert(1)> <data:text/html,x>")) shouldBe
        s"<${z}javascript:alert(1)> <${z}data:text/html,x>"
      written(_.oneLine("<https://x")) shouldBe s"<${z}https://x"
    }

    "keep links and images with a safe URL" in {
      val links = "[a](#a) [b](spec.md) [c](https://x.org/a:b) ![d](d.png) [e](mailto:a@b.c) " +
        "[f](../a/b.md?x=1:2) [g](a/b:c) [h](<https://x>)"
      written(_.oneLine(links)) shouldBe links
      written(_.paragraph("[r]: https://x.org")) shouldBe "[r]: https://x.org\n\n"
    }

    "keep links and images with an unsafe URL from being links" in {
      for (
        url <- Seq(
          "javascript:alert(1)",
          "JaVaScRiPt:alert(1)",
          " javascript:alert(1)",
          "java\tscript:alert(1)",
          "javascript\\:alert(1)",
          "&#106;avascript:alert(1)",
          "&#x6A;avascript:alert(1)",
          "&#0000106avascript:alert(1)",
          "javascript&#58;alert(1)",
          "javascript&colon;alert(1)",
          "data:text/html,x",
          "vbscript:x",
          "httpsx:x",
        )
      ) {
        // A tab is one space on one line.
        val shown = url.replace('\t', ' ')
        written(_.oneLine(s"[a]($url)")) shouldBe s"[a]$z($shown)"
        written(_.oneLine(s"![a]($url)")) shouldBe s"![a]$z($shown)"
      }
      written(_.paragraph("[r]: javascript:alert(1)")) shouldBe s"[r]$z: javascript:alert(1)\n\n"
    }

    "read a reference to a code above U+10FFFF as no scheme" in {
      written(_.oneLine("[a](&#99999999999;:x)")) shouldBe "[a](&#99999999999;:x)"
    }

    "keep tags and links from being ones in a note, a table cell and all paragraphs" in {
      written(_.note("Deprecated", "<script>\n[a](javascript:x)")) shouldBe
        s"> **Deprecated:** <${z}script>\n> [a]$z(javascript:x)\n\n"
      inCell(_.allOnOneLine("<script>\n\n[a](javascript:x)")) shouldBe
        s"<${z}script><br><br>[a]$z(javascript:x)"
    }
  }
}
