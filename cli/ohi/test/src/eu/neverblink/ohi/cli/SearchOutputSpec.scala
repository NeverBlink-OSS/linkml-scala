package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.{LinkmlDateTime, Uri}
import eu.neverblink.ohi.model.generated.search.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class SearchOutputSpec extends AnyWordSpec, Matchers {
  private val collections = SearchHit(
    baseUrl = Uri("https://api.ohi.dev/files/packages/commons/collections/20221101/"),
    description = "Collections, bags, lists and their members.",
    formats = Seq(
      Format.LinkmlYaml,
      Format.OssieYaml,
      Format.OwlRdfxml,
      Format.OwlTurtle,
      Format.RdfsTurtle,
    ),
    keywords = Seq("collections"),
    licenses = Seq("MIT"),
    name = "collections",
    namespace = "commons",
    publishedAt = LinkmlDateTime("2026-10-27T00:00:00Z"),
    title = "OMG Commons Collections",
    version = "20221101",
  )
  private val result = SearchResult(
    hits = Seq(collections),
    page = 1,
    perPage = 10,
    searchCutoff = false,
    total = 1,
  )

  "search output" should {
    for (columns, expected) <- Seq(
        80 -> """1 matching ontology
                |
                |OMG Commons Collections
                |  Ontology: commons/collections | Version: 20221101
                |  Description: Collections, bags, lists and their members.
                |  Formats: linkml-yaml, ossie-yaml, owl-rdfxml, owl-turtle, rdfs-turtle
                |  License: MIT
                |  Tags: collections
                |  URL: https://api.ohi.dev/files/packages/commons/collections/20221101/""".stripMargin,
        60 -> """1 matching ontology
                |
                |OMG Commons Collections
                |  Ontology: commons/collections | Version: 20221101
                |  Description: Collections, bags, lists and their members.
                |  Formats: linkml-yaml, ossie-yaml, owl-rdfxml, owl-turtle,
                |    rdfs-turtle
                |  License: MIT
                |  Tags: collections
                |  URL: https://api.ohi.dev/files/packages/commons/collection
                |    s/20221101/""".stripMargin,
        40 -> """1 matching ontology
                |
                |OMG Commons Collections
                |  Ontology: commons/collections
                |  Version: 20221101
                |  Description: Collections, bags, lists
                |    and their members.
                |  Formats: linkml-yaml, ossie-yaml,
                |    owl-rdfxml, owl-turtle, rdfs-turtle
                |  License: MIT
                |  Tags: collections
                |  URL: https://api.ohi.dev/files/package
                |    s/commons/collections/20221101/""".stripMargin,
      )
    do
      s"match the selected Collections layout at $columns columns" in {
        SearchOutput.render(result, columns, colored = false) shouldBe expected
      }

    "move a word intact to the continuation when only the label fits" in {
      val input = result.copy(hits = Seq(collections.copy(description = "abcdefghij")))
      SearchOutput.render(input, 20, colored = false) should include(
        "  Description:\n    abcdefghij\n",
      )
    }

    "split overlong tokens by code point without losing supplementary characters" in {
      val input =
        result.copy(hits = Seq(collections.copy(description = "abcdefghijklmnopqrst😀uv")))
      val out = SearchOutput.render(input, 20, colored = false)
      out should include("  Description: abcde\n    fghijklmnopqrst😀\n    uv\n")
      out.linesIterator.foreach(line => line.codePointCount(0, line.length) should be <= 20)
    }

    "apply the selected ANSI roles without changing wrapping or leaking styles across lines" in {
      val out = SearchOutput.render(result, 60, colored = true)
      out.replaceAll("\u001b\\[[0-9;]*m", "") shouldBe
        SearchOutput.render(result, 60, colored = false)
      out should include("\u001b[1mOMG Commons Collections\u001b[0m")
      out should include("\u001b[2m  Ontology: \u001b[0m\u001b[36mcommons/collections\u001b[0m")
      out should include("\u001b[2m | Version: 20221101\u001b[0m")
      out should include("\u001b[0mCollections, bags, lists and their members.\u001b[0m")
      out should include("\u001b[32mrdfs-turtle\u001b[0m")
      out should include("\u001b[2mMIT\u001b[0m")
      out should include("\u001b[2mcollections\u001b[0m")
      out should include("\u001b[36mhttps://api.ohi.dev/files/packages/commons/collection\u001b[0m")
      out.linesIterator.filter(_.nonEmpty).foreach(_ should endWith("\u001b[0m"))
    }

    "flatten source whitespace and remove CSI sequences and other source controls" in {
      val input = result.copy(hits =
        Seq(
          collections.copy(
            title = " \u001b[31mOMG\u001b[0m\tCollections\u0007 \n",
            description = " Line\u0000 one\u00a0two\u2028three \u009b2J",
          ),
        ),
      )
      val out = SearchOutput.render(input, 80, colored = false)
      out should include("\nOMG Collections\n")
      out should include("  Description: Line one two three\n")
      out.filter(c => Character.isISOControl(c) && c != '\n') shouldBe empty
    }

    "report zero matches without an empty-page message" in {
      SearchOutput.render(result.copy(hits = Seq.empty, total = 0), 80, colored = false) shouldBe
        "No matching ontologies."
    }

    "retain the cutoff warning on an empty page" in {
      val input = result.copy(hits = Seq.empty, total = 7, page = 2, searchCutoff = true)
      SearchOutput.render(input, 80, colored = false) shouldBe
        "No results on page 2. 7 matches in total.\n" +
        "Search stopped early. Results and counts may be incomplete."
      SearchOutput.render(input, 80, colored = true) should include(
        "\u001b[33mSearch stopped early. Results and counts may be incomplete.\u001b[0m",
      )
    }

    "retain and wrap ordered facets after an empty page" in {
      val input = result.copy(
        hits = Seq.empty,
        total = 7,
        page = 2,
        facetCounts = Seq(
          FacetCount(
            Seq(FacetValueCount(2, "z long facet value"), FacetValueCount(5, "a")),
            "namespace",
          ),
          FacetCount(Seq(FacetValueCount(1, "owl-turtle")), "formats"),
        ),
      )
      SearchOutput.render(input, 20, colored = false) shouldBe
        """No results on page
          |2. 7 matches in
          |total.
          |
          |Facet: namespace
          |  z long facet
          |    value: 2
          |  a: 5
          |
          |Facet: formats
          |  owl-turtle: 1""".stripMargin
    }

    "use effective paging for positions and the next-page option" in {
      val out =
        SearchOutput.render(result.copy(page = 2, perPage = 3, total = 7), 80, colored = false)
      out.linesIterator.next() shouldBe "7 matches · Showing 4–4 · Page 2"
      out should endWith("\n\nNext page: use --page 3 with the same search options.")
    }

    "calculate large positions without overflowing or offering a nonexistent next page" in {
      val input = result.copy(page = Int.MaxValue, perPage = 250, total = Int.MaxValue)
      val out = SearchOutput.render(input, 100, colored = false)
      out.linesIterator.next() shouldBe
        "2147483647 matches · Showing 536870911501–536870911501 · Page 2147483647"
      out should not include "Next page:"
    }
  }

  "search width" should {
    for (columns, expected) <- Seq(
        None -> 80,
        Some(" 60 ") -> 60,
        Some("10") -> 20,
        Some("invalid") -> 80,
        Some("0") -> 80,
        Some("-1") -> 80,
        Some("2147483648") -> 80,
      )
    do
      s"resolve COLUMNS=$columns to $expected columns" in {
        SearchOutput.width(columns) shouldBe expected
      }
  }
}
