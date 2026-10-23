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
    }

    "escape an underscore only where it can start or end emphasis" in {
      written(_.text("_private_")) shouldBe "\\_private\\_"
      written(_.text("a_b")) shouldBe "a_b"
      written(_.text("a _b")) shouldBe "a \\_b"
    }

    "keep a name on one line" in {
      written(_.text("a\nb")) shouldBe "a b"
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
    }

    "not escape a pipe twice" in {
      inCell(_.append("a\\|b")) shouldBe "a\\|b"
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
  }

  "DocsSite.fileStem" should {
    "keep letters, digits, underscores, hyphens and dots" in {
      DocsSite.fileStem("Person") shouldBe "Person"
      DocsSite.fileStem("has_part-2.0") shouldBe "has_part-2.0"
      DocsSite.fileStem("Größe") shouldBe "Größe"
    }

    "replace anything else with an underscore" in {
      DocsSite.fileStem("a name/with:stuff") shouldBe "a_name_with_stuff"
    }

    "avoid names that are hidden or not allowed on Windows" in {
      DocsSite.fileStem(".hidden") shouldBe "_.hidden"
      DocsSite.fileStem("") shouldBe "_"
      DocsSite.fileStem("CON") shouldBe "_CON"
      DocsSite.fileStem("com1") shouldBe "_com1"
    }
  }
}
