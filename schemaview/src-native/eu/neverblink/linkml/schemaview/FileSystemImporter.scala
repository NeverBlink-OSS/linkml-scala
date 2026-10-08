package eu.neverblink.linkml.schemaview

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.sys.stat
import scala.scalanative.posix.sys.statOps.*
import scala.scalanative.unsafe.*
import scala.util.control.NonFatal

/** An Importer implementation that reads the schema text from a file path. This is the default
  * importer used by SchemaView.
  */
object FileSystemImporter extends StringImporter {
  def read(path: String): String = new String(Files.readAllBytes(Paths.get(path)), UTF_8)

  /** Identify the file by its device and inode, so symbolic and hard links to one file share a key.
    * It calls `stat` directly, because the file keys of Scala Native's `java.nio` compare only hash
    * codes, so two different files could share one. On Windows it uses the real path, which still
    * follows symbolic links. A path that cannot be resolved is its own key, leaving the read to
    * report it.
    */
  override def schemaKey(path: String): String =
    try {
      if (LinktimeInfo.isWindows) Paths.get(path).toRealPath().toString
      else
        // `stat` needs C memory that the GC does not manage: a C string for the path and a
        // `struct stat` for the result. `Zone` frees everything allocated in it (`alloc`,
        // `toCString`) when the block ends, even on an exception. So no pointer may leave the
        // block: the device and inode are copied into the returned `String` first.
        Zone {
          val buf = alloc[stat.stat]()
          if (stat.stat(toCString(path), buf) != 0) path
          else s"file-key:(dev=${buf.st_dev},ino=${buf.st_ino})"
        }
    } catch {
      case ex if NonFatal(ex) => path
    }
}
