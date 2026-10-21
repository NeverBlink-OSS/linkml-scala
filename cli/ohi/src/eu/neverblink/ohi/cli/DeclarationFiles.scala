package eu.neverblink.ohi.cli

import eu.neverblink.ohi.cli.generated.declaration.Declaration
import eu.neverblink.ohi.cli.generated.projecterror.ProjectErrorCode
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.{ATOMIC_MOVE, REPLACE_EXISTING}
import java.nio.file.StandardOpenOption.{CREATE_NEW, WRITE}
import scala.util.Using
import scala.util.control.NonFatal

private[cli] object DeclarationFiles {

  /** Reads the declaration in the UTF-8 YAML file at `path`, following symbolic links.
    *
    * @return
    *   the declaration stored in the file
    * @throws ProjectException
    *   with `InvalidDeclaration` when the file is not UTF-8 or not a valid declaration, or with
    *   `StorageError` when the file cannot be read
    */
  def read(path: Path): Declaration = {
    val text = try Files.readString(path, UTF_8)
    catch {
      case cause: CharacterCodingException =>
        throw new ProjectException(
          ProjectErrorCode.InvalidDeclaration,
          s"Cannot decode '$path' as UTF-8. Save the declaration as UTF-8 YAML.",
          Some(cause),
        )
      case NonFatal(cause) =>
        throw storage(path, "read", cause, "Check the file path and read permissions.")
    }
    try DeclarationYaml.decode(text)
    catch {
      case failure: ProjectException =>
        throw new ProjectException(
          failure.code,
          s"Invalid declaration '$path'. ${failure.getMessage}",
          Some(failure),
        )
    }
  }

  /** Writes `declaration` as YAML to a new file at `path`. Removes the new file if writing fails.
    *
    * @throws ProjectException
    *   with `InvalidDeclaration` when `declaration` is invalid, or with `StorageError` when `path`
    *   already exists, its parent directory is missing or writing fails
    */
  def create(path: Path, declaration: Declaration): Unit = create(
    path,
    declaration,
    target => Files.newOutputStream(target, CREATE_NEW, WRITE),
    target => Files.delete(target),
  )

  private[cli] def create(
      path: Path,
      declaration: Declaration,
      open: Path => OutputStream,
      delete: Path => Unit,
  ): Unit = {
    val bytes = DeclarationYaml.encode(declaration).getBytes(UTF_8)
    val stream = try open(path)
    catch {
      case NonFatal(cause) =>
        throw storage(
          path,
          "create",
          cause,
          "Check that the parent is writable and the target is absent.",
        )
    }
    try Using.resource(stream)(_.write(bytes))
    catch {
      case NonFatal(cause) =>
        throw storage(
          path,
          "finish writing",
          cause,
          cleanup(path, cause, delete, "incomplete declaration"),
        )
    }
  }

  /** Writes `declaration` as YAML to `path`, replacing any existing file atomically. On failure the
    * file keeps its previous contents.
    *
    * @throws ProjectException
    *   with `InvalidDeclaration` when `declaration` is invalid, with `InvalidProject` when `path`
    *   is a symbolic link or a directory, or with `StorageError` when the parent directory is
    *   missing, writing fails or the file cannot be replaced atomically
    */
  def write(path: Path, declaration: Declaration): Unit = write(
    path,
    declaration,
    stage => Files.newOutputStream(stage),
    (stage, destination) => {
      Files.move(stage, destination, ATOMIC_MOVE, REPLACE_EXISTING)
      ()
    },
    stage => Files.delete(stage),
  )

  private[cli] def write(
      path: Path,
      declaration: Declaration,
      open: Path => OutputStream,
      replace: (Path, Path) => Unit,
      delete: Path => Unit,
  ): Unit = {
    val bytes = DeclarationYaml.encode(declaration).getBytes(UTF_8)
    val stage = try Files.createTempFile(path.toAbsolutePath.getParent, ".ohi-declaration-", ".tmp")
    catch {
      case NonFatal(cause) =>
        throw storage(path, "stage", cause, "Check that the parent exists and is writable.")
    }
    try {
      Using.resource(open(stage))(_.write(bytes))
      if Files.isSymbolicLink(path) || Files.isDirectory(path, NOFOLLOW_LINKS) then
        throw new ProjectException(
          ProjectErrorCode.InvalidProject,
          s"Declaration '$path' is a symlink or directory. Supply a regular declaration file.",
        )
      replace(stage, path)
    } catch {
      case NonFatal(cause) =>
        val correction = cleanup(stage, cause, delete, "temporary file")
        cause match {
          case failure: ProjectException =>
            throw new ProjectException(
              failure.code,
              s"${failure.getMessage} $correction",
              Some(failure),
            )
          case _ => throw storage(path, "replace", cause, correction)
        }
    }
  }

  private def cleanup(
      path: Path,
      cause: Throwable,
      delete: Path => Unit,
      description: String,
  ): String =
    try {
      delete(path)
      "Check write permissions and available space."
    } catch {
      case NonFatal(failure) =>
        cause.addSuppressed(failure)
        s"Inspect or remove the $description '$path' before retrying."
    }

  private def storage(
      path: Path,
      action: String,
      cause: Throwable,
      correction: String,
  ): ProjectException =
    new ProjectException(
      ProjectErrorCode.StorageError,
      s"Cannot $action '$path': ${cause.getMessage}. $correction",
      Some(cause),
    )
}
