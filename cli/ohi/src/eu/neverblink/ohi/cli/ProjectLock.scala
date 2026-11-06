package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.cli.generated.declaration.ProjectDependency
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import eu.neverblink.ohi.model.generated.{Artifact, Format, Publication}
import eu.neverblink.ohi.model.generated.lock
import scala.collection.mutable

private[cli] object ProjectLock {

  /** Reconstructs direct requirements in selection order from a validated lock, without metadata
    * requests. Hub identities must be canonical. Declaration validation remains the caller's job.
    */
  def requirements(complete: lock.Lock): Seq[ProjectDependency] = {
    val files = complete.files.map(file => (file.baseUrl, file.url) -> file).toMap
    complete.selections.map { selection =>
      val identity = HubArtifactPath.publication(selection.baseUrl.original, ".")
      ProjectDependency(
        format = ProjectFormat.toDeclaration(files((selection.baseUrl, selection.url)).format),
        ontology = s"${identity.namespace}/${identity.name}@${identity.version}",
      )
    }
  }

  /** Builds a version-2 lock for selections and same-format dependencies using cached metadata.
    * Propagates inspection failures. Throws [[ProjectException]] for invalid selections or missing
    * or ambiguous dependency artifacts, including invalid Hub URLs.
    */
  def build(
      selections: Seq[lock.ArtifactSelectionImpl],
      metadata: Inspection,
  ): lock.Lock = {
    val direct = metadata.selected(selections)
    direct.foreach { (result, _) =>
      result.dependencies.foreach(dependency => metadata.publication(dependency.baseUrl.original))
    }

    val files = direct.flatMap { (result, artifact) =>
      (result -> artifact) +: result.dependencies.map { dependency =>
        val required = metadata(dependency.baseUrl.original)
        val matches = required.artifacts.filter(_.format == artifact.format)
        if matches.size != 1 then
          throw new ProjectException(
            ProjectErrorCode.InvalidSelection,
            s"Dependency ${dependency.baseUrl.original} has ${matches.size} artifacts in format ${stringify(artifact.format)} required by ${artifact.url.original}. Choose another direct artifact format or use ohi get for explicit files.",
          )
        required -> matches.head
      }
    }.distinctBy { (result, artifact) =>
      (result.baseUrl.original, artifact.url.original)
    }.map { (result, artifact) =>
      file(result, artifact)
    }
    lock.Lock(files = files, formatVersion = 2, selections = selections)
  }

  /** Requires an artifact selected from `publication.artifacts`. Validates Hub identity and URL
    * ancestry, retaining its URL and checksum. Throws [[ProjectException]] for invalid URLs or
    * metadata disagreement, preserving URL failures.
    */
  def file(publication: Publication, artifact: Artifact): lock.LockedFile = try {
    val identity = HubArtifactPath.publication(publication.baseUrl.original, ".")
    if identity.namespace != publication.namespace || identity.name != publication.name ||
      identity.version != publication.version
    then
      throw new ProjectException(
        ProjectErrorCode.InvalidSelection,
        s"Publication '${publication.baseUrl.original}' does not match its namespace, name and version. Correct the publication metadata and retry.",
      )
    if HubArtifactPath.artifact(artifact.url.original, ".") != identity then
      throw new ProjectException(
        ProjectErrorCode.InvalidSelection,
        s"Artifact '${artifact.url.original}' is outside publication '${publication.baseUrl.original}'. Choose an advertised artifact beneath its publication URL.",
      )
    lock.LockedFile(
      publication.baseUrl,
      lockFormat(artifact.format),
      artifact.url,
      Some(artifact.sha256),
    )
  } catch {
    case cause: CliFileException =>
      throw new ProjectException(ProjectErrorCode.InvalidSelection, cause.getMessage, Some(cause))
  }

  /** Caches publication metadata and validates exact direct selections. Reuse one instance across
    * reference resolution and lock construction to avoid repeated HTTP requests.
    */
  final class Inspection(inspect: String => Publication) extends (String => Publication) {
    private val publications = mutable.Map.empty[String, Publication]

    /** Returns cached metadata without checking its identity, leaving reference diagnostics to the
      * caller.
      */
    override def apply(url: String): Publication = publications.getOrElse(
      url, {
        val result = inspect(url)
        publications(url) = result
        // A reference resolves to a base URL that lock construction inspects again.
        publications(result.baseUrl.original) = result
        result
      },
    )

    /** Requires the response's exact base URL to match the request or throws [[ProjectException]].
      */
    def publication(url: String): Publication = {
      val result = apply(url)
      if result.baseUrl.original != url then
        throw new ProjectException(
          ProjectErrorCode.InvalidSelection,
          s"Publication $url returned identity ${result.baseUrl.original}. Correct the publication metadata and retry.",
        )
      result
    }

    /** Resolves exact direct selections or throws [[ProjectException]] for inconsistent metadata.
      */
    def selected(selections: Seq[lock.ArtifactSelectionImpl]): Seq[(Publication, Artifact)] =
      selections.map { selection =>
        val result = publication(selection.baseUrl.original)
        val artifact = result.artifacts.find(_.url.original == selection.url.original).getOrElse {
          throw new ProjectException(
            ProjectErrorCode.InvalidSelection,
            s"Publication ${selection.baseUrl.original} does not advertise artifact ${selection.url.original}. Choose an advertised artifact URL and retry.",
          )
        }
        result -> artifact
      }
  }

  private def lockFormat(format: Format): lock.Format = format match {
    case Format.OwlTurtle => lock.Format.OwlTurtle
    case Format.OwlRdfxml => lock.Format.OwlRdfxml
    case Format.RdfsTurtle => lock.Format.RdfsTurtle
    case Format.LinkmlYaml => lock.Format.LinkmlYaml
    case Format.OssieYaml => lock.Format.OssieYaml
  }
}
