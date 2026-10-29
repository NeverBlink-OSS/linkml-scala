package eu.neverblink.ohi.cli

import eu.neverblink.ohi.model.generated.Format
import eu.neverblink.ohi.model.generated.lock
import java.nio.file.{Files, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS

private[cli] object ProjectFormat {

  /** Returns the common format of the declaration's exact direct selections, or no preference for a
    * missing, empty or mixed declaration. Invalid declarations and metadata failures propagate.
    */
  def infer(directory: Path, metadata: ProjectLock.Inspection): Option[Format] = {
    val path = directory.resolve("ohi.yaml")
    if Files.notExists(path, NOFOLLOW_LINKS) then None
    else {
      val selections = DeclarationFiles.read(path).selections.map(value =>
        lock.ArtifactSelectionImpl(value.baseUrl, value.url),
      )
      metadata.selected(selections).map(_._2.format).distinct match {
        case Seq(format) => Some(format)
        case _ => None
      }
    }
  }
}
