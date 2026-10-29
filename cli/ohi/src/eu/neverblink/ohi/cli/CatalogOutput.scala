package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.cli.generated.delivery.DeliveryResult
import eu.neverblink.ohi.cli.generated.fileerror.CliFileError
import eu.neverblink.ohi.model.generated.{ArtifactOrigin, Publication}
import eu.neverblink.ohi.model.generated.search.SearchResult

import scala.collection.mutable.ArrayBuffer

import CliFormatting.{action, clean, styled}

private[cli] object CatalogOutput {
  private val searchCodec: LinkmlYamlCodec[SearchResult] = LinkmlYamlCodec.derived
  private val publicationCodec: LinkmlYamlCodec[Publication] = LinkmlYamlCodec.derived
  private val deliveryCodec: LinkmlYamlCodec[DeliveryResult] = LinkmlYamlCodec.derived
  private val fileErrorCodec: LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived

  def search(result: SearchResult, json: Boolean, width: Int, colored: Boolean): String =
    if json then JsonUtil.yamlToJson(searchCodec.encode(result))
    else SearchOutput.render(result, width, colored)

  /** Render JSON or normalized human release details with descriptions wrapped at 80 columns. */
  def publication(result: Publication, json: Boolean, colored: Boolean = false): String =
    if json then JsonUtil.yamlToJson(publicationCodec.encode(result))
    else {
      val output = ArrayBuffer.empty[String]
      def field(label: String, value: String, indent: String = "  ", code: String = "0"): Unit =
        output += styled(s"$indent$label: ", "2", colored) + styled(clean(value), code, colored)

      def section(title: String): Unit = {
        output += ""
        output += styled(title, "1", colored)
      }

      output += styled(clean(result.title), "1", colored)
      field("Ontology", s"${result.namespace}/${result.name}", code = "36")
      field("Version", result.version)
      SearchOutput.wrap(result.description, 80, "  Description: ", "    ").zipWithIndex.foreach {
        (line, index) =>
          val prefix = if index == 0 then 15 else 4
          output += styled(line.take(prefix), "2", colored) + line.drop(prefix)
      }
      field("Release URL", result.baseUrl.original, code = "36")

      section("Files")
      if result.artifacts.isEmpty then output += "  None"
      result.artifacts.zipWithIndex.foreach { (artifact, index) =>
        if index > 0 then output += ""
        val url = artifact.url.original
        val name = try FileDelivery.filename(url, "")
        catch { case _: CliFileException => url }
        output += "  " + styled(name, "1", colored)
        field(
          "Format",
          s"${CliFormatting.formats.toMap.apply(artifact.format)} · ${stringify(artifact.format)}",
          "    ",
        )
        field(
          "Size",
          s"${String.format(java.util.Locale.ROOT, "%,d", Int.box(artifact.sizeBytes))} bytes",
          "    ",
        )
        field(
          "Source",
          artifact.origin match {
            case ArtifactOrigin.Upstream => "Upstream"
            case ArtifactOrigin.Generated => "Generated"
          },
          "    ",
        )
        field("URL", url, "    ", "36")
        field("SHA-256", artifact.sha256, "    ")
        if artifact.roots.map(_.original) != Seq(url) then {
          output += styled("    Entry points", "2", colored)
          artifact.roots.foreach(root =>
            output += "      " + styled(clean(root.original), "36", colored),
          )
        }
      }

      section("Dependencies")
      if result.dependencies.isEmpty then output += "  None"
      result.dependencies.foreach(dependency =>
        output += "  " + styled(clean(dependency.baseUrl.original), "36", colored),
      )

      section("Release details")
      field("Published to Hub", stringify(result.publishedAt))
      field("Publisher", result.publisher)
      result.namespaceIri.foreach(value => field("RDF namespace", value.original, code = "36"))
      result.preferredPrefix.foreach(value => field("Preferred prefix", value))
      field("License", reported(result.licenses))
      field("Tags", reported(result.keywords))
      output.mkString("\n")
    }

  def delivery(result: DeliveryResult, json: Boolean, colored: Boolean = false): String =
    if json then
      JsonUtil.yamlToJson(
        deliveryCodec.encode(result),
      )
    else action("Saved", result.path, colored)

  def fileError(error: CliFileError): String =
    JsonUtil.yamlToJson(fileErrorCodec.encode(error))

  private def reported(values: Seq[String]): String =
    if values.isEmpty then "Not supplied" else values.mkString(", ")
}
