package eu.neverblink.linkml.schemaview

/** An Importer implementation that reads the schema text from a file path with Node.js. This is the
  * default importer used by SchemaView.
  */
object FileSystemImporter extends StringImporter {
  def read(path: String): String =
    PlatformSpecificUtils.fs.readFileSync(filePath(path), "utf-8").asInstanceOf[String]

  /** The path of the file to read, with `\` separators on Windows. */
  private def filePath(path: String): String =
    if (PlatformSpecificUtils.isWindows) Importer.windowsFilePath(path) else path
}
