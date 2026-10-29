package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import eu.neverblink.ohi.model.generated.{Artifact, Format, Publication}
import eu.neverblink.ohi.model.generated.lock
import scala.collection.mutable

private[cli] object ProjectLock {

  /** Builds a version-2 lock for selections and same-format dependencies. Calls `inspect` once per
    * publication and propagates its failures. Throws [[ProjectException]] for invalid selections or
    * missing or ambiguous dependency artifacts.
    */
  def build(
      selections: Seq[lock.ArtifactSelectionImpl],
      inspect: String => Publication,
  ): lock.Lock = {
    val metadata = new Inspection(inspect)
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
      lock.LockedFile(
        result.baseUrl,
        lockFormat(artifact.format),
        artifact.url,
        Some(artifact.sha256),
      )
    }
    lock.Lock(files = files, formatVersion = 2, selections = selections)
  }

  /** Caches publication metadata and validates exact direct selections. Reuse one instance across
    * format inference, reference resolution and lock construction to avoid repeated HTTP requests.
    */
  final class Inspection(inspect: String => Publication) extends (String => Publication) {
    private val publications = mutable.Map.empty[String, Publication]

    /** Returns cached metadata without checking its identity, leaving reference diagnostics to the
      * caller.
      */
    override def apply(url: String): Publication = publications.getOrElseUpdate(url, inspect(url))

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
