package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import eu.neverblink.ohi.model.generated.{Format, Publication}
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
    val publications = mutable.Map.empty[String, Publication]
    def publication(url: String): Publication = publications.getOrElseUpdate(
      url, {
        val result = inspect(url)
        if result.baseUrl.original != url then
          throw new ProjectException(
            ProjectErrorCode.InvalidSelection,
            s"Publication $url returned identity ${result.baseUrl.original}. Correct the publication metadata and retry.",
          )
        result
      },
    )

    val direct = selections.map { selection =>
      val result = publication(selection.baseUrl.original)
      val artifact = result.artifacts.find(_.url.original == selection.url.original).getOrElse {
        throw new ProjectException(
          ProjectErrorCode.InvalidSelection,
          s"Publication ${selection.baseUrl.original} does not advertise artifact ${selection.url.original}. Choose an advertised artifact URL and retry.",
        )
      }
      result -> artifact
    }
    direct.foreach { (result, _) =>
      result.dependencies.foreach(dependency => publication(dependency.baseUrl.original))
    }

    val files = direct.flatMap { (result, artifact) =>
      (result -> artifact) +: result.dependencies.map { dependency =>
        val required = publications(dependency.baseUrl.original)
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

  private def lockFormat(format: Format): lock.Format = format match {
    case Format.OwlTurtle => lock.Format.OwlTurtle
    case Format.OwlRdfxml => lock.Format.OwlRdfxml
    case Format.RdfsTurtle => lock.Format.RdfsTurtle
    case Format.LinkmlYaml => lock.Format.LinkmlYaml
    case Format.OssieYaml => lock.Format.OssieYaml
  }
}
