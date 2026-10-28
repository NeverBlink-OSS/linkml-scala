package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.model.generated.search.SearchResult

import scala.collection.mutable.ArrayBuffer

import CliFormatting.{clean, styled}

private[cli] object SearchOutput {

  /** Resolve exported COLUMNS, using 80 for invalid values and a minimum of 20. */
  def width(columns: Option[String]): Int =
    columns.flatMap(_.trim.toIntOption).filter(_ > 0).map(_.max(20)).getOrElse(80)

  /** Render normalized human text at a width of at least 20 Unicode code points. */
  def render(result: SearchResult, width: Int, colored: Boolean): String = {
    val output = ArrayBuffer.empty[String]

    def text(value: String, code: String, first: String = "", next: String = ""): Unit =
      output ++= wrap(value, width, first, next).map(styled(_, code, colored))

    def field(label: String, value: String, code: String): Unit =
      wrap(value, width, s"  $label: ", "    ").zipWithIndex.foreach { (row, index) =>
        val prefixLength = if index == 0 then label.length + 4 else 4
        output += styled(row.take(prefixLength), "2", colored) +
          styled(row.drop(prefixLength), code, colored)
      }

    val count = result.hits.size
    val start = (result.page.toLong - 1) * result.perPage + 1
    val summary =
      if result.total == 0 then "No matching ontologies."
      else if count == 0 then
        s"No results on page ${result.page}. ${result.total} matches in total."
      else if count == result.total then
        s"${result.total} matching ${if count == 1 then "ontology" else "ontologies"}"
      else s"${result.total} matches · Showing $start–${start + count - 1} · Page ${result.page}"
    text(summary, "1")
    if result.searchCutoff then
      text("Warning: Search stopped early. Results and counts may be incomplete.", "33")

    result.hits.foreach { hit =>
      output += ""
      text(hit.title, "1")
      val identity = clean(s"${hit.namespace}/${hit.name}")
      val version = clean(hit.version)
      val heading = s"  Ontology: $identity | Version: $version"
      if length(heading) <= width then
        output += styled("  Ontology: ", "2", colored) + styled(identity, "36", colored) +
          styled(s" | Version: $version", "2", colored)
      else {
        field("Ontology", identity, "36")
        field("Version", version, "2")
      }
      field("Description", hit.description, "0")
      field("Formats", hit.formats.map(stringify(_)).mkString(", "), "32")
      if hit.licenses.nonEmpty then field("License", hit.licenses.mkString(", "), "2")
      if hit.keywords.nonEmpty then field("Tags", hit.keywords.mkString(", "), "2")
      output += styled("  URL: ", "2", colored) + styled(clean(hit.baseUrl.original), "36", colored)
    }

    result.facetCounts.foreach { facet =>
      output += ""
      text(s"Facet: ${facet.fieldName}", "2")
      facet.counts.foreach(value => text(s"${value.value}: ${value.count}", "0", "  ", "    "))
    }
    if result.page.toLong * result.perPage < result.total then {
      output += ""
      text(s"Next page: use --page ${result.page.toLong + 1} with the same search options.", "2")
    }
    output.mkString("\n")
  }

  private def length(value: String): Int = value.codePointCount(0, value.length)

  /** Normalize text and wrap at Unicode code-point width, including the supplied line prefixes.
    * `width` must exceed both prefix widths.
    */
  def wrap(value: String, width: Int, first: String, next: String): Seq[String] = {
    val rows = ArrayBuffer.empty[String]
    var remaining = clean(value)
    var prefix = first
    while remaining.nonEmpty do {
      val available = width - length(prefix)
      if length(remaining) <= available then {
        rows += prefix + remaining
        remaining = ""
      } else {
        val end = remaining.offsetByCodePoints(0, available)
        val space = remaining.lastIndexOf(' ', end)
        if space >= 0 then {
          rows += prefix + remaining.substring(0, space)
          remaining = remaining.substring(space + 1)
        } else if length(remaining.takeWhile(_ != ' ')) <= width - length(next) then
          rows += prefix.stripTrailing()
        else {
          rows += prefix + remaining.substring(0, end)
          remaining = remaining.substring(end)
        }
      }
      prefix = next
    }
    if rows.isEmpty then Seq(first.stripTrailing()) else rows.toSeq
  }
}
