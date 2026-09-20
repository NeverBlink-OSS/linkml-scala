package eu.neverblink.linkml.optiongen

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.{Files, Path, StandardCopyOption}
import java.security.MessageDigest
import scala.collection.mutable
import scala.util.control.NonFatal

object RegionWriter {
  final case class PlannedFile(path: Path, before: Option[String], content: String)

  private final case class Region(start: Int, end: Int)
  private val marker =
    """[\t ]*(//|<!--) (BEGIN|END) GENERATED OPTIONS ([A-Za-z0-9_.-]+)(?:[\t ]+(-->))?[\t ]*\r?\n?""".r

  private def digest(bytes: Array[Byte]): String =
    java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

  def digest(content: String): String = digest(content.getBytes(UTF_8))

  def snapshot(path: Path): Option[String] =
    if Files.notExists(path) then None else Some(digest(Files.readAllBytes(path)))

  private def regions(source: String, allowed: Set[String]): Map[String, Region] = {
    val found = mutable.Map.empty[String, Region]
    var active = Option.empty[(String, Int, String)]
    var offset = 0
    source.split("(?<=\n)", -1).foreach { line =>
      line match {
        case marker(style, kind, id, closing) =>
          require(
            (style == "<!--") == (closing != null),
            s"Malformed generated marker ${line.trim}",
          )
          require(allowed(id), s"Unknown generated region $id")
          if kind == "BEGIN" then {
            require(active.isEmpty, s"Nested generated region $id")
            require(!found.contains(id), s"Duplicate generated region $id")
            active = Some((id, offset + line.length, style))
          } else {
            require(
              active.exists { case (openId, _, openStyle) =>
                openId == id && openStyle == style
              },
              s"Unmatched end marker for $id",
            )
            found(id) = Region(active.get._2, offset)
            active = None
          }
        case _ =>
          val trimmed = line.trim
          require(
            !trimmed.startsWith("// BEGIN GENERATED OPTIONS") &&
              !trimmed.startsWith("// END GENERATED OPTIONS") &&
              !trimmed.startsWith("<!-- BEGIN GENERATED OPTIONS") &&
              !trimmed.startsWith("<!-- END GENERATED OPTIONS"),
            s"Malformed generated marker $trimmed",
          )
      }
      offset += line.length
    }
    require(active.isEmpty, s"Unclosed generated region ${active.map(_._1).getOrElse("")}")
    require(
      found.keySet == allowed,
      s"Missing generated regions ${(allowed -- found.keySet).toVector.sorted.mkString(", ")}",
    )
    found.toMap
  }

  def compose(source: String, replacements: Map[String, String], allowed: Set[String]): String = {
    require(
      replacements.keySet.subsetOf(allowed),
      "Replacement for an unregistered generated region",
    )
    val locations = regions(source, allowed)
    replacements.toVector.sortBy { case (id, _) => -locations(id).start }.foldLeft(source) {
      case (result, (id, body)) =>
        val region = locations(id)
        val terminated = if body.isEmpty || body.endsWith("\n") then body else body + "\n"
        result.substring(0, region.start) + terminated + result.substring(region.end)
    }
  }

  def prepare(
      path: Path,
      replacements: Map[String, String],
      allowed: Set[String],
      format: (Path, String) => String,
  ): PlannedFile = {
    val source = Files.readString(path)
    def interiors(formatted: String): Map[String, String] = {
      val locations = regions(formatted, allowed)
      replacements.keysIterator.map { id =>
        val region = locations(id)
        id -> formatted.substring(region.start, region.end)
      }.toMap
    }
    val formatted = interiors(format(path, compose(source, replacements, allowed)))
    val content = compose(source, formatted, allowed)
    require(
      interiors(format(path, content)) == formatted,
      s"Unstable generated formatting in $path",
    )
    PlannedFile(path, Some(digest(source)), content)
  }

  def complete(path: Path, content: String): PlannedFile =
    PlannedFile(path, snapshot(path), content)

  private def validatePaths(files: Vector[PlannedFile]): Unit = {
    val paths = files.map(_.path.toAbsolutePath.normalize())
    require(paths.distinct.size == paths.size, "Duplicate generated destination")
  }

  def stale(files: Vector[PlannedFile]): Vector[Path] = {
    validatePaths(files)
    files.filter(file => snapshot(file.path) != Some(digest(file.content))).map(_.path)
  }

  private[optiongen] def publishWith(
      files: Vector[PlannedFile],
      replace: (Path, Path) => Unit,
  ): Vector[Path] = {
    validatePaths(files)
    val changed = files.filter(file => snapshot(file.path) != file.before).map(_.path)
    if changed.nonEmpty then
      throw new IllegalStateException(
        s"Destinations changed since rendering: ${changed.mkString(", ")}",
      )
    val written = mutable.ArrayBuffer.empty[Path]
    files.foreach { file =>
      if file.before != Some(digest(file.content)) then {
        try {
          val parent = file.path.toAbsolutePath.getParent
          Files.createDirectories(parent)
          val temporary =
            Files.createTempFile(parent, s".${file.path.getFileName}.optiongen-", ".tmp")
          try {
            Files.writeString(temporary, file.content)
            if file.before.nonEmpty then {
              val attributes =
                Files.getFileAttributeView(file.path, classOf[PosixFileAttributeView])
              if attributes != null then
                Files.setPosixFilePermissions(temporary, attributes.readAttributes().permissions())
            }
            if snapshot(file.path) != file.before then
              throw new IllegalStateException(
                s"Destination changed before replacement: ${file.path}",
              )
            replace(temporary, file.path)
            written += file.path
          } finally Files.deleteIfExists(temporary)
        } catch {
          case NonFatal(error) =>
            throw new IOException(
              s"Failed to publish ${file.path}. Already updated: ${written.mkString(", ")}",
              error,
            )
        }
      }
    }
    written.toVector
  }

  def publish(files: Vector[PlannedFile]): Vector[Path] =
    publishWith(
      files,
      (temporary, destination) => {
        Files.move(
          temporary,
          destination,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING,
        )
        ()
      },
    )
}
