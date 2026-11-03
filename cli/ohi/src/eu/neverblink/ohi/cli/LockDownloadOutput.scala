package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.projectresult.{LockFileResult, LockFileStatus}
import java.nio.file.Path

private[cli] object LockDownloadOutput {
  def summary(
      files: Seq[LockFileResult],
      colored: Boolean = false,
      destination: Option[String] = None,
  ): String = {
    val downloaded = files.count(_.status == LockFileStatus.Downloaded)
    summary(downloaded, files.size - downloaded, colored, destination)
  }

  def summary(
      downloaded: Int,
      verified: Int,
      colored: Boolean,
      destination: Option[String],
  ): String = {
    def files(count: Int): String = if count == 1 then "file" else "files"
    def location(preposition: String): String = destination.fold("")(directory =>
      s" $preposition ${CliFormatting.styled(CliFormatting.displayPath(Path.of(directory)), "36", colored)}/",
    )
    val saved = Option.when(downloaded > 0)(
      s"${CliFormatting.styled("Downloaded", "32", colored)} $downloaded ontology ${files(downloaded)}${location("to")}.",
    )
    val checked = Option.when(verified > 0)(
      s"${CliFormatting.styled("Verified", "32", colored)} $verified existing ontology ${files(verified)}" +
        (if downloaded == 0 then location("in") else "") + ".",
    )
    (saved.toSeq ++ checked).mkString(" ") match {
      case "" => "No ontology files to download."
      case message => message
    }
  }
}
