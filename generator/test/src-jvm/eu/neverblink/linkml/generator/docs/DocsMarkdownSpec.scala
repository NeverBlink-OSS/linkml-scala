package eu.neverblink.linkml.generator.docs

import eu.neverblink.linkml.generator.util.PruningMode
import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}
import eu.neverblink.linkml.tests.ModelCatalogue
import io.github.edadma.markdown.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.mutable

/** Parses the generated documentation with a CommonMark parser, with GitHub tables on, and checks
  * what a reader would see: that the links lead to the right pages, that names come out exactly as
  * written in the schema, and that tables keep their columns.
  *
  * JVM-only: the generator is the same code on every platform, and the parser is slow on JS and
  * Native. DocsGeneratorSpec runs on all three.
  */
class DocsMarkdownSpec extends AnyWordSpec, Matchers {
  import DocsMarkdownSpec.*

  /** Names that Markdown, MDX or a file system could get wrong. */
  private val awkward: SchemaView = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromString(
      """id: https://neverblink.eu/test/awkward/
        |name: awkward
        |title: "*Awkward* | names"
        |default_range: string
        |types:
        |  string:
        |    base: str
        |classes:
        |  Person:
        |    tree_root: true
        |    description: "Has a | pipe and `code | with pipe`."
        |    attributes:
        |      friend:
        |        range: person
        |      "*star*":
        |        range: "<html>"
        |      "a|pipe":
        |        range: "_under_"
        |  person:
        |    attributes:
        |      back:
        |        range: "[brackets]"
        |  "_under_":
        |    slots:
        |      - "{curly}"
        |  "[brackets]":
        |  "Größe":
        |  "name with spaces":
        |  ".dot":
        |  "?":
        |slots:
        |  "{curly}":
        |    range: "`tick`"
        |enums:
        |  "<html>":
        |    permissible_values:
        |      "a|b":
        |        description: "x | y"
        |      "`c`":
        |  "`tick`":
        |    permissible_values:
        |      v:
        |""".stripMargin,
    ),
  )

  private lazy val schemas: Seq[(String, SchemaView)] =
    ModelCatalogue.all.map(entry => (entry.name, entry.model)) ++ Seq(
      ("awkward", awkward),
      ("metamodel", SchemaIssues.orThrow(SchemaView.loadSchemaViewFromUri("linkml:meta"))),
    )

  /** Each kind of pruning documents a different set of elements, so check them all. */
  private val modes = Seq(PruningMode.skip, PruningMode.schemaRoot, PruningMode.treeRoot(None))

  "the documentation written as files" should {
    "only link to pages that exist" in {
      forEachSite { (files, pages) =>
        for (path, doc) <- pages; link <- linksOf(doc) if isLocal(link.destination) do {
          withClue(s"$path links to ${link.destination}: ") {
            files.keySet should contain(resolve(path, link.destination))
          }
        }
      }
    }

    "link every page from the index or from another page" in {
      forEachSite { (files, pages) =>
        val reached = mutable.HashSet("index.md")
        val queue = mutable.Queue("index.md")
        while (queue.nonEmpty) {
          val path = queue.dequeue()
          linksOf(pages(path)).foreach { link =>
            if (isLocal(link.destination)) {
              val target = resolve(path, link.destination)
              if (reached.add(target)) queue.enqueue(target)
            }
          }
        }
        reached.toSet shouldBe files.keySet
      }
    }

    "name each link after the element whose page it leads to" in {
      forEachSite { (_, pages) =>
        val names = pages.collect {
          case (path, doc) if path != "index.md" => (path, elementName(doc))
        }
        for (path, doc) <- pages; link <- linksOf(doc) if isLocal(link.destination) do {
          withClue(s"in $path: ") {
            plain(link.inlines) shouldBe names(resolve(path, link.destination))
          }
        }
      }
    }

    "keep the columns of every table" in {
      forEachSite { (files, pages) =>
        for (path, doc) <- pages; table <- tablesOf(doc) do {
          withClue(s"in $path: ") {
            table.rows.foreach(_.cells.size shouldBe table.headerRow.cells.size)
          }
        }
        // A parser drops the cells past the header's count, so count the cells in the source too.
        for (path, text) <- files; block <- text.split("\n\n") if block.startsWith("|") do {
          val rows = block.linesIterator.toSeq
          rows.foreach { row =>
            withClue(s"in $path: $row: ") {
              cellCount(row) shouldBe cellCount(rows.head)
            }
          }
        }
      }
    }
  }

  "the documentation written as one document" should {
    "only link to anchors that exist, and have each anchor once" in {
      schemas.foreach { (name, sv) =>
        withClue(s"$name: ") {
          val text = DocsGenerator(using sv).serialize()
          val doc = parse(text)
          val anchors = anchorsOf(doc)
          anchors.distinct shouldBe anchors
          linksOf(doc).foreach { link =>
            if (link.destination.startsWith("#")) anchors should contain(link.destination.drop(1))
            else isLocal(link.destination) shouldBe false
          }
        }
      }
    }
  }

  "awkward names" should {
    "come out as written" in {
      val pages = DocsGenerator(using awkward).generateFiles().map(_.toTuple).toMap.map((k, v) =>
        (k, parse(v)),
      )
      pages.keySet should contain allOf (
        "classes/person.md",
        "classes/person_2.md",
        "classes/gr_e.md",
        "classes/name_with_spaces.md",
        "classes/dot.md",
        "classes/_.md",
      )
      Seq("*star*", "a|pipe", "friend").foreach { name =>
        plainCells(pages("classes/person.md")) should contain(name)
      }
      plainCells(pages("enums/html.md")) should contain allOf ("a|b", "`c`", "x | y")
      headingsOf(pages("index.md")).head shouldBe "*Awkward* | names"
    }
  }

  /** The files of every schema in every pruning mode, with each page parsed. Generated once and
    * shared by the tests, as parsing them all takes a while.
    */
  private lazy val sites: Seq[(String, Map[String, String], Map[String, Document])] =
    for (name, sv) <- schemas; mode <- modes yield {
      val files =
        DocsGenerator(using sv).generateFiles(DocsGenerator.Options(mode)).map(_.toTuple).toMap
      (s"$name, $mode: ", files, files.map((path, text) => (path, parse(text))))
    }

  /** Run [[check]] on the files of every schema, in every pruning mode, with each page parsed. */
  private def forEachSite(check: (Map[String, String], Map[String, Document]) => Unit): Unit =
    for (clue, files, pages) <- sites do withClue(clue)(check(files, pages))
}

object DocsMarkdownSpec {

  /** CommonMark with GitHub tables, which is what GitHub and most site tools read. */
  private val config = MarkdownConfig(tables = true)

  def parse(text: String): Document = parseDocumentContent(text, config)

  /** Whether [[destination]] is a link to another generated file. */
  def isLocal(destination: String): Boolean =
    !destination.startsWith("http://") && !destination.startsWith("https://") &&
      !destination.startsWith("#")

  /** [[destination]] as a path from the output folder, for a link in the file at [[from]]. */
  def resolve(from: String, destination: String): String = {
    val segments = mutable.ListBuffer.from(from.split('/').dropRight(1))
    destination.split('/').foreach {
      case ".." => segments.dropRightInPlace(1)
      case "." =>
      case s => segments.addOne(s)
    }
    segments.mkString("/")
  }

  /** The name of the element a page is about, from its `Kind: name` heading. */
  def elementName(doc: Document): String = {
    val heading = headingsOf(doc).head
    heading.substring(heading.indexOf(": ") + 2)
  }

  def headingsOf(doc: Document): Seq[String] =
    doc.children.collect { case h: Heading => plain(h.inlines) }

  def tablesOf(doc: Document): Seq[Table] = doc.children.collect { case t: Table => t }

  /** The plain text of each table cell of [[doc]].
    *
    * GitHub tables read `\|` as `|` even inside a code span. The parser keeps the backslash there,
    * so it is removed here.
    */
  def plainCells(doc: Document): Seq[String] =
    tablesOf(doc).flatMap { table =>
      (table.headerRow :: table.rows).flatMap(_.cells.map { cell =>
        plain(cell.content.map {
          case CodeSpan(code) => CodeSpan(code.replace("\\|", "|"))
          case other => other
        })
      })
    }

  /** The `id` of each `<a id="...">` anchor. An anchor on a line of its own is a paragraph of
    * inline HTML, as CommonMark only starts an HTML block at a line holding the opening tag alone.
    */
  def anchorsOf(doc: Document): Seq[String] =
    doc.children.collect { case Paragraph(inlines) => inlines }.flatten
      .collect { case RawHTML(html) => html }
      .flatMap(html => "id=\"([^\"]+)\"".r.findAllMatchIn(html).map(_.group(1)))

  def linksOf(doc: Document): Seq[Link] = blockLinks(doc.children)

  private def blockLinks(blocks: List[Block]): List[Link] = blocks.flatMap {
    case Paragraph(inlines) => inlineLinks(inlines)
    case Heading(_, inlines, _) => inlineLinks(inlines)
    case BlockQuote(children) => blockLinks(children)
    case ListBlock(_, items) => items.flatMap(item => blockLinks(item.content))
    case Table(header, rows, _) =>
      (header :: rows).flatMap(_.cells.flatMap(cell => inlineLinks(cell.content)))
    case _ => Nil
  }

  private def inlineLinks(inlines: List[Inline]): List[Link] = inlines.flatMap {
    case link: Link => link :: inlineLinks(link.inlines)
    case Emphasis(inner) => inlineLinks(inner)
    case Strong(inner) => inlineLinks(inner)
    case _ => Nil
  }

  /** The text that [[inlines]] show, without formatting. */
  def plain(inlines: List[Inline]): String = inlines.map {
    case Text(content) => content
    case CodeSpan(content) => content
    case Emphasis(inner) => plain(inner)
    case Strong(inner) => plain(inner)
    case Link(_, _, inner) => plain(inner)
    case SoftLineBreak() | HardLineBreak() => " "
    case _ => ""
  }.mkString

  /** The number of cells in a table row of the source: its `|` that are not escaped, minus one. */
  def cellCount(row: String): Int = {
    var pipes = 0
    var backslashes = 0
    row.foreach { c =>
      if (c == '|' && (backslashes & 1) == 0) pipes += 1
      backslashes = if (c == '\\') backslashes + 1 else 0
    }
    pipes - 1
  }
}
