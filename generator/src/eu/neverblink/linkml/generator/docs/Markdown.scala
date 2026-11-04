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
  *
  * In Markdown from the schema, a zero-width space after `<` or `]` turns tags other than
  * [[Markdown.safeTags]], and links with a scheme other than `http`, `https` or `mailto`, into
  * text. So the playground can show the HTML as it is.
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
      val len = s.length
      var n = backslashes
      var i = 0
      while (i < len) {
        val c = s.charAt(i)
        if (c == '|' && (n & 1) == 0) sink.append('\\')
        n = if (c == '\\') n + 1 else 0
        sink.append(c)
        i += 1
      }
      backslashes = n
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

  /** A paragraph of Markdown from the schema, without the whitespace around it.
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
    * Markdown from the schema, written without the whitespace around it. Each of its lines gets `>`
    * in front, so all of its paragraphs stay in the quote.
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
      } else if (c != '\r') schemaChar(text, start, end)
      start += 1
    }
  }

  /** Write the character at [[i]] of [[text]], Markdown from the schema that ends at [[end]], with
    * a zero-width space after it if it starts a tag, an autolink or a link that is not safe.
    */
  private def schemaChar(text: String, i: Int, end: Int): Unit = {
    val c = text.charAt(i)
    sink.append(c)
    if (i + 1 < end) {
      val next = text.charAt(i + 1)
      if (c == '<') {
        if (
          (Markdown.isAsciiLetter(next) || next == '/' || next == '!' || next == '?') &&
          !Markdown.isSafeTag(text, i, end)
        ) sink.append(Markdown.zeroWidthSpace)
      } else if (c == ']' && (next == '(' || next == ':')) {
        if (!Markdown.isSafeUrl(text, i + 2, end)) sink.append(Markdown.zeroWidthSpace)
      }
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
    // The backslashes in a row at the end of what is written, as [[append]] counts them
    var n = backslashes
    var i = 0
    while (i < len) {
      val c = value.charAt(i)
      c match {
        case '\\' | '`' | '*' | '[' | ']' | '<' | '>' | '{' | '}' | '#' | '~' | '&' =>
          sink.append('\\')
          sink.append(c)
          n = if (c == '\\') n + 2 else 0
        case '_' =>
          val inWord = i > 0 && i < len - 1 &&
            Character.isLetterOrDigit(value.charAt(i - 1)) &&
            Character.isLetterOrDigit(value.charAt(i + 1))
          if (!inWord) sink.append('\\')
          sink.append(c)
          n = 0
        case '\n' | '\r' =>
          sink.append(' ')
          n = 0
        case _ =>
          if (c == '|' && inRow && (n & 1) == 0) sink.append('\\')
          sink.append(c)
          n = 0
      }
      i += 1
    }
    backslashes = n
  }

  /** Markdown from the schema, such as a description, written on one line, as a table cell or a
    * list item needs.
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
    // The backslashes in a row at the end of what is written, as [[append]] counts them
    var n = backslashes
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
        if (space) {
          sink.append(if (newlines >= 2) "<br><br>" else " ")
          n = 0
        }
        if (c == '|' && inRow && (n & 1) == 0) sink.append('\\')
        n = if (c == '\\') n + 1 else 0
        schemaChar(text, i, text.length)
        started = true
        space = false
        newlines = 0
      }
      i += 1
    }
    backslashes = n
  }

  /** [[text]] as an inline code span, on one line. */
  def code(text: String): Unit = {
    // A code span cannot contain its own fence, so use a longer fence than any run of backticks in
    // the text.
    val longest = longestBacktickRun(text)
    // A space between the fence and a backtick at either end keeps them apart.
    val pad = text.startsWith("`") || text.endsWith("`")
    backticks(longest + 1)
    if (pad) sink.append(' ')
    // The backslashes in a row at the end of what is written, as [[append]] counts them
    var n = 0
    var i = 0
    while (i < text.length) {
      val c = text.charAt(i)
      // Markdown ends a line at `\n`, `\r` or `\r\n`, so each of them becomes one space.
      if (c == '\r') {
        if (i + 1 < text.length && text.charAt(i + 1) == '\n') i += 1
        sink.append(' ')
        n = 0
      } else if (c == '\n') {
        sink.append(' ')
        n = 0
      } else {
        if (c == '|' && inRow && (n & 1) == 0) sink.append('\\')
        n = if (c == '\\') n + 1 else 0
        sink.append(c)
      }
      i += 1
    }
    if (pad) sink.append(' ')
    backticks(longest + 1)
  }

  /** [[n]] in decimal digits. */
  def number(n: Int): Unit = {
    if (n < 0) {
      sink.append('-')
      // Through Long, as -Int.MinValue does not fit in an Int.
      digits(-n.toLong)
    } else digits(n.toLong)
    backslashes = 0
  }

  private def digits(n: Long): Unit = {
    if (n >= 10) digits(n / 10)
    sink.append(('0' + (n % 10)).toChar)
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

  /** Write [[count]] backticks. */
  private def backticks(count: Int): Unit = {
    var i = count
    while (i > 0) {
      sink.append('`')
      i -= 1
    }
    backslashes = 0
  }
}

private object Markdown {

  /** Written after a `<` or a `]` to keep it from starting a tag or a link. Readers don't see it.
    */
  private val zeroWidthSpace = '\u200b'

  /** The HTML tags that Markdown from the schema can have, for plain formatting. Schemas use them,
    * for example `<i>` and `<sub>` in names of electrical quantities.
    */
  private val safeTags = Array(
    "b",
    "i",
    "em",
    "strong",
    "code",
    "sub",
    "sup",
    "s",
    "del",
    "u",
    "small",
    "mark",
    "kbd",
    "br",
    "p",
    "ul",
    "ol",
    "li",
  )

  private def isAsciiLetter(c: Char): Boolean = (c | 0x20) >= 'a' && (c | 0x20) <= 'z'

  private def isAsciiLetterOrDigit(c: Char): Boolean = isAsciiLetter(c) || (c >= '0' && c <= '9')

  /** Whether the `<` at [[from]] of [[text]] starts one of [[safeTags]] without attributes, such as
    * `<b>`, `</b>` or `<br />`, or an autolink with a safe scheme (see [[isSafeScheme]]), such as
    * `<https://w3id.org/linkml>`. The text ends at [[end]].
    */
  private def isSafeTag(text: String, from: Int, end: Int): Boolean = {
    var i = from + 1
    val closing = i < end && text.charAt(i) == '/'
    if (closing) i += 1
    val nameStart = i
    while (i < end && isAsciiLetterOrDigit(text.charAt(i))) i += 1
    val nameEnd = i
    if (i < end && text.charAt(i) == ':') {
      // An autolink: a scheme, `:`, and then anything but spaces, control characters, `<` and `>`
      if (closing || !isSafeScheme(text, nameStart, nameEnd)) return false
      i += 1
      while (i < end && text.charAt(i) > ' ' && text.charAt(i) != '<' && text.charAt(i) != '>')
        i += 1
      return i < end && text.charAt(i) == '>'
    }
    var known = false
    var t = 0
    while (!known && t < safeTags.length) {
      val tag = safeTags(t)
      known =
        tag.length == nameEnd - nameStart && text.regionMatches(true, nameStart, tag, 0, tag.length)
      t += 1
    }
    if (!known) return false
    while (i < end && text.charAt(i) == ' ') i += 1
    if (!closing && i < end && text.charAt(i) == '/') i += 1
    i < end && text.charAt(i) == '>'
  }

  /** Whether the characters from [[from]] to [[to]] of [[text]] are a scheme that a link can have:
    * `http`, `https` or `mailto`, in any case.
    */
  private def isSafeScheme(text: String, from: Int, to: Int): Boolean = {
    val length = to - from
    (length == 4 && text.regionMatches(true, from, "http", 0, 4)) ||
    (length == 5 && text.regionMatches(true, from, "https", 0, 5)) ||
    (length == 6 && text.regionMatches(true, from, "mailto", 0, 6))
  }

  /** Like the other [[isSafeScheme]], for the first [[length]] characters of [[scheme]], which
    * holds at most 6.
    */
  private def isSafeScheme(scheme: Array[Char], length: Int): Boolean =
    is(scheme, length, "http") || is(scheme, length, "https") || is(scheme, length, "mailto")

  /** Whether the first [[length]] characters of [[scheme]] are [[name]], in any case. */
  private def is(scheme: Array[Char], length: Int, name: String): Boolean = {
    var same = length == name.length
    var i = 0
    while (same && i < length) {
      same = (scheme(i) | 0x20) == name.charAt(i)
      i += 1
    }
    same
  }

  /** The value of [[c]] as an ASCII digit in base [[radix]], 10 or 16, or -1 if it is not one. */
  private def digit(c: Char, radix: Int): Int =
    if (c >= '0' && c <= '9') c - '0'
    else if (radix == 16 && (c | 0x20) >= 'a' && (c | 0x20) <= 'f') (c | 0x20) - 'a' + 10
    else -1

  /** Whether the URL of a link that starts at [[from]] of [[text]] has no scheme, as `#part` or
    * `spec.md`, or a safe one (see [[isSafeScheme]]). The text ends at [[end]].
    *
    * The scheme is read as a browser reads it, after the Markdown renderer has written the URL:
    *   - Markdown leaves out a backslash before punctuation, as in `javascript\:`.
    *   - The browser decodes character references, such as `&#106;` for `j`. Any other `&`, as in
    *     `&colon;`, could stand for a `:`, so the URL is not safe.
    *   - The browser leaves out spaces, tabs, line breaks and other control characters.
    *   - The URL can be in `<` and `>`.
    *   - A scheme starts with a letter, and has only letters, digits, `+`, `-` and `.` before its
    *     `:`. Any other character, such as `/`, `?` or `#`, means that the URL has no scheme.
    */
  private def isSafeUrl(text: String, from: Int, end: Int): Boolean = {
    // The scheme so far, up to 6 characters, as no safe scheme is longer
    val scheme = new Array[Char](6)
    var length = 0
    var i = from
    while (i < end) {
      var c = text.charAt(i)
      i += 1
      if (c == '&') {
        if (i >= end || text.charAt(i) != '#') return false
        i += 1
        val hex = i < end && (text.charAt(i) | 0x20) == 'x'
        if (hex) i += 1
        var code = 0
        var digits = 0
        var done = false
        while (!done && i < end) {
          val d = digit(text.charAt(i), if (hex) 16 else 10)
          if (d < 0) done = true
          else {
            // Codes above U+10FFFF stay above it, which is all that matters below.
            if (code <= 0x10ffff) code = code * (if (hex) 16 else 10) + d
            digits += 1
            i += 1
          }
        }
        if (digits == 0) return false
        if (i < end && text.charAt(i) == ';') i += 1
        c = if (code > 0 && code < 0x80) code.toChar else '\u00ff'
      }
      if (c == '\\' || c <= ' ' || c == '\u007f' || (c == '<' && length == 0)) {
        // Left out, as the renderer and the browser leave it out
      } else if (c == ':') {
        return isSafeScheme(scheme, length)
      } else if (
        isAsciiLetter(c) ||
        (length > 0 && (isAsciiLetterOrDigit(c) || c == '+' || c == '-' || c == '.'))
      ) {
        if (length < scheme.length) scheme(length) = c
        length += 1
      } else return true
    }
    true
  }

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
