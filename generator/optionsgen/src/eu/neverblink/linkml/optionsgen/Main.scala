package eu.neverblink.linkml.optionsgen

import eu.neverblink.linkml.schemaview.{FileSystemImporter, SchemaIssues, SchemaView}

/** Writes the code generated from `model/generator-options.yaml`.
  *
  * @param root
  *   the root of the repository
  * @param part
  *   `cli` for the command line options, `bindings` for the C and Python bindings
  */
@main def generateOptions(root: String, part: String): Unit = {
  val repo = os.Path(root)
  given SchemaView = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromUri(
      (repo / "model" / "generator-options.yaml").toString,
      FileSystemImporter,
    ),
  )
  val schema = OptionsSchema()

  part match {
    case "cli" =>
      val dir = repo / "cli" / "options" / "src" / os.RelPath(CliGen.pkg.replace('.', '/'))
      os.remove.all(dir)
      CliGen(schema).foreach((file, content) => os.write(dir / file, content, createFolders = true))
    case "bindings" =>
      os.write.over(repo / "python" / "linkml_scala" / "_generated.py", PyBindingsGen(schema))
      os.write.over(
        repo / "nativelib" / "src" / "eu" / "neverblink" / "linkml" / "nativelib" /
          "LinkMlCGenerators.scala",
        CApiGen(schema),
      )
    case other => sys.error(s"Unknown part '$other', expected cli or bindings")
  }
}
