package eu.neverblink.ohi.model

import com.networknt.schema.{ExecutionConfig, ExecutionContext, SchemaRegistry}
import com.networknt.schema.dialect.Dialects
import eu.neverblink.linkml.runtime.{LinkmlDateTime, Uri, stringify}
import eu.neverblink.ohi.model.generated.*
import scala.jdk.CollectionConverters.*
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/** Validates and decodes publication metadata, discarding undeclared fields. */
object PublicationJson {
  private val mapper = JsonMapper.builder().build()
  private val schema = SchemaRegistry.withDialect(Dialects.getDraft202012).getSchema(
    mapper.readTree(getClass.getResourceAsStream("/package.schema.json")),
  )
  private val validationConfig = ExecutionConfig.builder().formatAssertionsEnabled(true).build()
  private val formats = Seq[Format](
    Format.OwlTurtle,
    Format.OwlRdfxml,
    Format.RdfsTurtle,
    Format.LinkmlYaml,
    Format.OssieYaml,
  ).map(value => stringify(value) -> value).toMap
  private val origins = Seq[ArtifactOrigin](ArtifactOrigin.Upstream, ArtifactOrigin.Generated)
    .map(value => stringify(value) -> value).toMap

  def decode(text: String): Publication = {
    val data = try mapper.readTree(text)
    catch {
      case cause: JacksonException =>
        throw new IllegalArgumentException(cause.getMessage, cause)
    }
    val errors = schema.validate(
      data,
      (context: ExecutionContext) => context.setExecutionConfig(validationConfig),
    )
    require(errors.isEmpty, errors.asScala.mkString(", "))

    val publication = Publication(
      artifacts = data.get("artifacts").iterator().asScala.map { artifact =>
        require(
          artifact.get("size_bytes").canConvertToInt,
          "size_bytes exceeds the Scala Int range",
        )
        Artifact(
          format = formats(artifact.get("format").stringValue()),
          origin = origins(artifact.get("origin").stringValue()),
          roots =
            artifact.get("roots").iterator().asScala.map(value => Uri(value.stringValue())).toSeq,
          sha256 = artifact.get("sha256").stringValue(),
          sizeBytes = artifact.get("size_bytes").intValue(),
          url = Uri(artifact.get("url").stringValue()),
        )
      }.toSeq,
      baseUrl = Uri(data.get("base_url").stringValue()),
      dependencies = data.get("dependencies").iterator().asScala.map { dependency =>
        Dependency(baseUrl = Uri(dependency.get("base_url").stringValue()))
      }.toSeq,
      description = data.get("description").stringValue(),
      keywords = data.get("keywords").iterator().asScala.map(_.stringValue()).toSeq,
      licenses = data.get("licenses").iterator().asScala.map(_.stringValue()).toSeq,
      name = data.get("name").stringValue(),
      namespace = data.get("namespace").stringValue(),
      namespaceIri = Option(data.get("namespace_iri")).map(value => Uri(value.stringValue())),
      preferredPrefix = Option(data.get("preferred_prefix")).map(_.stringValue()),
      publishedAt = LinkmlDateTime(data.get("published_at").stringValue()),
      publisher = data.get("publisher").stringValue(),
      schemaVersion = data.get("schema_version").intValue(),
      title = data.get("title").stringValue(),
      version = data.get("version").stringValue(),
    )
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
