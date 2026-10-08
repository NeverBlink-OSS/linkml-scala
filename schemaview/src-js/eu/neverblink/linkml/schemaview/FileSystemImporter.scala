package eu.neverblink.linkml.schemaview

/** An Importer implementation that reads the schema text from a file path with Node.js. This is the
  * default importer used by SchemaView.
  */
object FileSystemImporter extends StringImporter {
  def read(path: String): String =
    PlatformSpecificUtils.fs.readFileSync(path, "utf-8").asInstanceOf[String]
}
