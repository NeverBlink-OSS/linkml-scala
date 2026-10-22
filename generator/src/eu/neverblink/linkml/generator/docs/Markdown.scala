package eu.neverblink.linkml.generator.docs

import eu.neverblink.linkml.rdf.io.CharSink

/** Writes Markdown that GitHub, Docusaurus, MkDocs and other CommonMark renderers show the same
  * way.
  *
  * Block methods write headings, lists, tables and code blocks. Inline methods write text into the
  * current block, and so does [[append]], which writes Markdown as it is.
  *
  * Headings are shifted down by [[headingOffset]] levels, so that the same page can be a file of
  * its own (offset 0) or a section of a single document (offset 1).
  */
private[docs] final class Markdown(sink: CharSink, headingOffset: Int) extends CharSink {

  /** Whether a table row is being written. A `|` in a cell would end the cell, even inside a code
    * span, so in a row each `|` that is not escaped yet gets a backslash.
    */
  private var inRow = false

  /** How many backslashes in a row were last written in the current cell. A `|` after an odd number
    * of them is escaped already.
    */
  private var backslashes = 0

  def append(c: Char): Unit = {
    if (inRow) {
      if (c == '|' && (backslashes & 1) == 0) sink.append('\\')
      backslashes = if (c == '\\') backslashes + 1 else 0
    }
    sink.append(c)
  }

  def append(s: String): Unit =
    if (inRow) {
      var i = 0
      while (i < s.length) {
        append(s.charAt(i))
        i += 1
      }
    } else sink.append(s)

  // Blocks

  /** A heading with [[title]], written as it is. */
  def heading(level: Int, title: String): Unit = {
    startHeading(level)
    sink.append(title)
    endBlock()
  }

  /** Start a heading. Write its text with the inline methods, then call [[endBlock]]. */
  def startHeading(level: Int): Unit = {
    var i = level + headingOffset
    while (i > 0) {
      sink.append('#')
      i -= 1
    }
    sink.append(' ')
  }

  /** End a heading, a paragraph or a note. */
  def endBlock(): Unit = sink.append("\n\n")

  /** A paragraph of Markdown, written as it is, without the whitespace around it. */
  def paragraph(text: String): Unit = {
    var start = 0
    var end = text.length
    while (start < end && Character.isWhitespace(text.charAt(start))) start += 1
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) end -= 1
    while (start < end) {
      sink.append(text.charAt(start))
      start += 1
    }
    endBlock()
  }

  /** A paragraph that starts with a bold label, such as a deprecation notice. */
  def note(label: String, text: String): Unit = {
    sink.append("> **")
    sink.append(label)
    sink.append(":** ")
    oneLine(text)
    endBlock()
  }

  /** One item of a bullet list, as a bold label and a value written as it is. Call [[endList]]
    * after the last item.
    */
  def item(label: String, value: String): Unit = {
    startItem(label)
    sink.append(value)
    endItem()
  }

  /** Start an item of a bullet list with a bold label. Write its value with the inline methods,
    * then call [[endItem]].
    */
  def startItem(label: String): Unit = {
    sink.append("- **")
    sink.append(label)
    sink.append(":** ")
  }

  /** Start an item of a bullet list, indented by [[depth]] levels. Write its text with the inline
    * methods, then call [[endItem]].
    */
  def startNestedItem(depth: Int): Unit = {
    var i = depth
    while (i > 0) {
      sink.append("  ")
      i -= 1
    }
    sink.append("- ")
  }

  def endItem(): Unit = sink.append('\n')

  /** End a bullet list, or a table. */
  def endList(): Unit = sink.append('\n')

  /** The header row of a table. Write the rows with [[startRow]] and end the table with
    * [[endList]].
    */
  def tableHeader(columns: String*): Unit = {
    sink.append('|')
    columns.foreach { column =>
      sink.append(' ')
      sink.append(column)
      sink.append(" |")
    }
    sink.append("\n|")
    columns.foreach(_ => sink.append(" --- |"))
    sink.append('\n')
  }

  /** Start a row of a table. Write each cell with the inline methods, separate the cells with
    * [[nextCell]], and end the row with [[endRow]]. A cell must fit on one line.
    */
  def startRow(): Unit = {
    sink.append("| ")
    inRow = true
    backslashes = 0
  }

  def nextCell(): Unit = {
    sink.append(" | ")
    backslashes = 0
  }

  def endRow(): Unit = {
    sink.append(" |\n")
    inRow = false
  }

  /** Start a fenced code block. Write its text with [[append]], ending with a newline, then call
    * [[endCodeBlock]].
    */
  def startCodeBlock(language: String): Unit = {
    sink.append("```")
    sink.append(language)
    sink.append('\n')
  }

  def endCodeBlock(): Unit = sink.append("```\n\n")

  /** Start an HTML anchor that a link can point to with `#id`. Write the id with [[append]], then
    * call [[endAnchor]].
    */
  def startAnchor(): Unit = sink.append("<a id=\"")

  def endAnchor(): Unit = sink.append("\"></a>\n\n")

  // Inline text

  /** [[value]] as inline Markdown that shows exactly as written.
    *
    * Characters that Markdown, GitHub or MDX could read as syntax get a backslash. An underscore
    * between two letters or digits is left alone, because it cannot start emphasis there, and names
    * like `has_part` stay readable in the Markdown source. Line breaks become spaces.
    */
  def text(value: String): Unit = {
    val len = value.length
    var i = 0
    while (i < len) {
      val c = value.charAt(i)
      c match {
        case '\\' | '`' | '*' | '[' | ']' | '<' | '>' | '{' | '}' | '#' | '~' | '&' =>
          append('\\')
          append(c)
        case '_' =>
          val inWord = i > 0 && i < len - 1 &&
            Character.isLetterOrDigit(value.charAt(i - 1)) &&
            Character.isLetterOrDigit(value.charAt(i + 1))
          if (!inWord) append('\\')
          append(c)
        case '\n' | '\r' =>
          append(' ')
        case _ =>
          append(c)
      }
      i += 1
    }
  }

  /** Markdown text from the schema, such as a description, written on one line, as a table cell or
    * a list item needs.
    *
    * Only the first paragraph is written. Each run of whitespace, including line breaks, becomes
    * one space, and whitespace at either end is left out. Any Markdown in the text is kept.
    */
  def oneLine(text: String): Unit = {
    var started = false
    var space = false
    var newlines = 0
    var i = 0
    while (i < text.length && newlines < 2) {
      val c = text.charAt(i)
      if (c == '\n') {
        newlines += 1
        space = started
      } else if (Character.isWhitespace(c)) {
        // A line of spaces between two line breaks still ends the paragraph.
        space = started
      } else {
        if (space) append(' ')
        append(c)
        started = true
        space = false
        newlines = 0
      }
      i += 1
    }
  }

  /** [[text]] as an inline code span, on one line. */
  def code(text: String): Unit = {
    // A code span cannot contain its own fence, so use a longer fence than any run of backticks in
    // the text.
    var longest = 0
    var run = 0
    var i = 0
    while (i < text.length) {
      if (text.charAt(i) == '`') {
        run += 1
        if (run > longest) longest = run
      } else run = 0
      i += 1
    }
    // A space between the fence and a backtick at either end keeps them apart.
    val pad = text.startsWith("`") || text.endsWith("`")
    backticks(longest + 1)
    if (pad) append(' ')
    i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      append(if (c == '\n') ' ' else c)
      i += 1
    }
    if (pad) append(' ')
    backticks(longest + 1)
  }

  /** [[n]] in decimal digits. */
  def number(n: Int): Unit =
    if (n < 0) {
      append('-')
      // Through Long, as -Int.MinValue does not fit in an Int.
      digits(-n.toLong)
    } else digits(n.toLong)

  private def digits(n: Long): Unit = {
    if (n >= 10) digits(n / 10)
    append(('0' + (n % 10)).toChar)
  }

  private def backticks(count: Int): Unit = {
    var i = count
    while (i > 0) {
      append('`')
      i -= 1
    }
  }
}
