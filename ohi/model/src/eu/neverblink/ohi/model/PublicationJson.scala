package eu.neverblink.ohi.model

import eu.neverblink.linkml.yaml.LinkmlYamlCodec
import eu.neverblink.ohi.model.generated.Publication

/** Decodes publication metadata into the generated class, discarding undeclared fields. */
object PublicationJson {
  private val codec: LinkmlYamlCodec[Publication] = LinkmlYamlCodec.derived

  /** @throws IllegalArgumentException
    *   if `text` does not decode as a publication or breaks a rule between its fields
    */
  def decode(text: String): Publication = {
    val publication = ModelJson.decode(codec, text)
    require(
      publication.preferredPrefix.isEmpty || publication.namespaceIri.nonEmpty,
      "preferred_prefix requires namespace_iri",
    )
    val artifactUrls = publication.artifacts.map(_.url)
    require(artifactUrls.distinct.size == artifactUrls.size, "artifacts must have unique URLs")
    val dependencyUrls = publication.dependencies.map(_.baseUrl)
    require(
      dependencyUrls.distinct.size == dependencyUrls.size,
      "dependencies must have unique base_url values",
    )
    require(
      !dependencyUrls.contains(publication.baseUrl),
      "dependencies must not contain the publication itself",
    )
    publication
  }
}
