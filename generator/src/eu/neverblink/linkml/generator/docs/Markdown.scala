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
final class Markdown private[docs] (sink: CharSink, headingOffset: Int) extends CharSink {

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

  /** A paragraph of Markdown, written as it is, without the whitespace around it.
    *
    * If the text opens a fenced code block but doesn't close it, it is closed after the text.
    * Otherwise the rest of the page, or of the whole document, would be part of the code block.
    */
  def paragraph(text: String): Unit = {
    lines(text, "")
    val fence = Markdown.openFence(text)
    if (fence ne null) {
      sink.append('\n')
      sink.append(fence)
    }
    endBlock()
  }

  /** A block quote that starts with a bold label, such as a deprecation notice. [[text]] is
    * Markdown, and is written as it is, without the whitespace around it. Each of its lines gets
    * `>` in front, so all of its paragraphs stay in the quote.
    */
  def note(label: String, text: String): Unit = {
    sink.append("> **")
    sink.append(label)
    sink.append(":** ")
    lines(text, ">")
    endBlock()
  }

  /** [[text]] without the whitespace around it, with [[prefix]] at the start of each line but the
    * first. A space separates the prefix from the line, except on an empty line, so that it has no
    * trailing whitespace.
    */
  private def lines(text: String, prefix: String): Unit = {
    var start = 0
    var end = text.length
    while (start < end && Character.isWhitespace(text.charAt(start))) start += 1
    while (end > start && Character.isWhitespace(text.charAt(end - 1))) end -= 1
    while (start < end) {
      val c = text.charAt(start)
      if (c == '\n') {
        sink.append('\n')
        sink.append(prefix)
        if (
          prefix.nonEmpty && start + 1 < end && text.charAt(start + 1) != '\n' &&
          text.charAt(start + 1) != '\r'
        ) sink.append(' ')
      } else if (c != '\r') sink.append(c)
      start += 1
    }
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
    *
    * Each level is indented by 4 spaces. MkDocs needs 4 spaces for a nested list, and GitHub and
    * other CommonMark tools read them the same way as 2.
    */
  def startNestedItem(depth: Int): Unit = {
    var i = depth
    while (i > 0) {
      sink.append("    ")
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

  /** A fenced code block of [[text]] in [[language]], such as `mermaid` for a diagram. The text is
    * written as it is. Its fence is longer than any run of backticks in it, so a line of the text
    * cannot end the block early.
    */
  def codeBlock(language: String, text: String): Unit = {
    val fence = Math.max(3, longestBacktickRun(text) + 1)
    backticks(fence)
    sink.append(language)
    sink.append('\n')
    sink.append(text)
    if (!text.endsWith("\n")) sink.append('\n')
    backticks(fence)
    endBlock()
  }

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
    * Only the first paragraph is written, for a summary of a text that is shown in full somewhere
    * else. Each run of whitespace, including line breaks, becomes one space, and whitespace at
    * either end is left out. Any Markdown in the text is kept.
    */
  def oneLine(text: String): Unit = writeOneLine(text, allParagraphs = false)

  /** Like [[oneLine]], but all paragraphs are written, with `<br><br>` between them. For a table
    * cell that is the only place a text is shown.
    */
  def allOnOneLine(text: String): Unit = writeOneLine(text, allParagraphs = true)

  private def writeOneLine(text: String, allParagraphs: Boolean): Unit = {
    var started = false
    var space = false
    var newlines = 0
    var i = 0
    while (i < text.length && (allParagraphs || newlines < 2)) {
      val c = text.charAt(i)
      if (c == '\n') {
        newlines += 1
        space = started
      } else if (Character.isWhitespace(c)) {
        // A line of spaces between two line breaks still ends the paragraph.
        space = started
      } else {
        if (space) append(if (newlines >= 2) "<br><br>" else " ")
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
    val longest = longestBacktickRun(text)
    // A space between the fence and a backtick at either end keeps them apart.
    val pad = text.startsWith("`") || text.endsWith("`")
    backticks(longest + 1)
    if (pad) append(' ')
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      // Markdown ends a line at `\n`, `\r` or `\r\n`, so each of them becomes one space.
      if (c == '\r') {
        if (i + 1 < text.length && text.charAt(i + 1) == '\n') i += 1
        append(' ')
      } else append(if (c == '\n') ' ' else c)
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

  /** The length of the longest run of backticks in [[text]]. */
  private def longestBacktickRun(text: String): Int = {
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
    longest
  }

  private def backticks(count: Int): Unit = {
    var i = count
    while (i > 0) {
      append('`')
      i -= 1
    }
  }
}

private object Markdown {

  /** The fence that closes the fenced code block that [[text]] opens but doesn't close, or `null`
    * if there is none.
    *
    * As in CommonMark, a fence is a line of at least 3 backticks or tildes, after at most 3 spaces.
    * A backtick fence can't have a backtick after it. A closing fence has the same character, at
    * least as many of it, and only whitespace after it.
    */
  private def openFence(text: String): String = {
    var fenceChar = ' '
    var fenceLength = 0
    // From the first character that isn't whitespace, as `lines` leaves out the whitespace before it
    var start = 0
    while (start < text.length && Character.isWhitespace(text.charAt(start))) start += 1
    while (start < text.length) {
      var end = text.indexOf('\n', start)
      if (end < 0) end = text.length
      var i = start
      while (i < end && i - start < 3 && text.charAt(i) == ' ') i += 1
      val c = if (i < end) text.charAt(i) else ' '
      var j = i
      while (j < end && text.charAt(j) == c) j += 1
      val length = j - i
      if ((c == '`' || c == '~') && length >= 3) {
        if (fenceLength == 0) {
          val info = text.indexOf('`', j)
          if (c == '~' || info < 0 || info >= end) {
            fenceChar = c
            fenceLength = length
          }
        } else if (c == fenceChar && length >= fenceLength && isBlank(text, j, end))
          fenceLength = 0
      }
      start = end + 1
    }
    if (fenceLength == 0) null else fenceChar.toString * fenceLength
  }

  private def isBlank(text: String, from: Int, to: Int): Boolean = {
    var i = from
    while (i < to && Character.isWhitespace(text.charAt(i))) i += 1
    i == to
  }
}
