package eu.neverblink.linkml.schemaview

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Paths}
import scala.util.control.NonFatal

/** An Importer implementation that reads the schema text from a file path. This is the default
  * importer used by SchemaView.
  */
object FileSystemImporter extends StringImporter {
  def read(path: String): String = new String(Files.readAllBytes(Paths.get(path)), UTF_8)

  /** Identify the file by its device and inode, so symbolic and hard links to one file share a key.
    * Where the file system has no such key (Windows), use the real path, which still follows
    * symbolic links. A path that cannot be resolved is its own key, leaving the read to report it.
    */
  override def schemaKey(path: String): String =
    try {
      val file = Paths.get(path)
      val fileKey = Files.readAttributes(file, classOf[BasicFileAttributes]).fileKey()
      if (fileKey ne null) "file-key:".concat(fileKey.toString)
      else file.toRealPath().toString
    } catch {
      case ex if NonFatal(ex) => path
    }
}
