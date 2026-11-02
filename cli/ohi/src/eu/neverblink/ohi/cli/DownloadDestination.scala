package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.declaration.Declaration
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode

import java.nio.file.{InvalidPathException, Path}

/** The selected download root and whether Ohi creates its missing ignore file. */
private[cli] final case class DownloadDestination(directory: String, managed: Boolean)

private[cli] object DownloadDestination {

  /** Validates the project-relative setting without accessing the filesystem. */
  def validate(value: String): Unit = {
    def invalid(reason: String): Nothing =
      throw new ProjectException(
        ProjectErrorCode.InvalidDeclaration,
        s"Invalid download_dir '$value' in ohi.yaml: $reason. Choose a dedicated directory beneath the project, such as .ohi.",
      )
    val path = try Path.of(value)
    catch { case cause: InvalidPathException => invalid(cause.getReason) }
    val normalized = path.normalize()
    if path.getRoot != null || normalized.toString.isEmpty || normalized.startsWith("..") then
      invalid("the directory must be relative and remain inside the project")
    // Windows may treat trailing dots/spaces and case variants as the same project record.
    val first = normalized.getName(0).toString.reverse.dropWhile(c => c == '.' || c == ' ').reverse
    if Seq("ohi.yaml", "ohi.lock.json").exists(_.equalsIgnoreCase(first)) then
      invalid("the directory conflicts with a project record")
  }

  /** Resolves a declaration whose download_dir has already passed validation. */
  def fromProject(project: Path, declaration: Declaration): DownloadDestination =
    DownloadDestination(
      project.resolve(declaration.downloadDir).toAbsolutePath.normalize().toString,
      managed = true,
    )

  def standalone(directory: Path): DownloadDestination =
    DownloadDestination(
      directory.resolve(".ohi").toAbsolutePath.normalize().toString,
      managed = true,
    )
}
