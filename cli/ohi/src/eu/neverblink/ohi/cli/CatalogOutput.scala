package eu.neverblink.ohi.cli

import eu.neverblink.linkml.generator.util.JsonUtil
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.cli.generated.delivery.DeliveryResult
import eu.neverblink.ohi.cli.generated.fileerror.CliFileError
import eu.neverblink.ohi.model.generated.Publication
import eu.neverblink.ohi.model.generated.search.SearchResult

private[cli] object CatalogOutput {
  private val searchCodec: LinkmlYamlCodec[SearchResult] = LinkmlYamlCodec.derived
  private val publicationCodec: LinkmlYamlCodec[Publication] = LinkmlYamlCodec.derived
  private val deliveryCodec: LinkmlYamlCodec[DeliveryResult] = LinkmlYamlCodec.derived
  private val fileErrorCodec: LinkmlYamlCodec[CliFileError] = LinkmlYamlCodec.derived

  def search(result: SearchResult, json: Boolean, width: Int, colored: Boolean): String =
    if json then JsonUtil.yamlToJson(searchCodec.encode(result))
    else SearchOutput.render(result, width, colored)

  def publication(result: Publication, json: Boolean): String =
    if json then JsonUtil.yamlToJson(publicationCodec.encode(result))
    else {
      val metadata = Seq(
        s"${result.namespace}/${result.name} ${result.version}",
        s"URL: ${result.baseUrl.original}",
        s"Title: ${result.title}",
        s"Description: ${result.description}",
        s"Published: ${stringify(result.publishedAt)}",
        s"Publisher: ${result.publisher}",
        s"Licenses: ${reported(result.licenses)}",
        s"Keywords: ${reported(result.keywords)}",
      ) ++ result.namespaceIri.map(value => s"RDF namespace: ${value.original}") ++
        result.preferredPrefix.map(value => s"Preferred prefix: $value")
      val artifacts = result.artifacts.map { artifact =>
        Seq(
          s"Artifact: ${artifact.url.original}",
          s"Format: ${stringify(artifact.format)}",
          s"Origin: ${stringify(artifact.origin)}",
          s"Roots: ${artifact.roots.map(_.original).mkString(", ")}",
          s"Bytes: ${artifact.sizeBytes}",
          s"SHA-256: ${artifact.sha256}",
        ).mkString("\n")
      }
      (Seq(metadata.mkString("\n")) ++ artifacts :+
        s"Dependencies: ${result.dependencies.map(_.baseUrl.original).mkString(", ")}")
        .mkString("\n\n")
    }

  def delivery(result: DeliveryResult, json: Boolean): String =
    if json then
      JsonUtil.yamlToJson(
        deliveryCodec.encode(result),
      )
    else s"Saved ${result.path}"

  def fileError(error: CliFileError): String =
    JsonUtil.yamlToJson(fileErrorCodec.encode(error))

  private def reported(values: Seq[String]): String =
    if values.isEmpty then "Not supplied" else values.mkString(", ")
}
