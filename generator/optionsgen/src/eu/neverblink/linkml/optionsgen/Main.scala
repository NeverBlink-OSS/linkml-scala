package eu.neverblink.linkml.optionsgen

import eu.neverblink.linkml.schemaview.{FileSystemImporter, SchemaIssues, SchemaView}

/** Writes the code generated from `model/converter/all_converters.yaml`.
  *
  * @param root
  *   the root of the repository
  * @param part
  *   `cli` for the command line options, `bindings` for the C and Python bindings, `docs` for the
  *   data of the generator and importer pages of the docs, and the tests of their examples
  */
@main def generateOptions(root: String, part: String): Unit = {
  val repo = os.Path(root)
  given SchemaView = SchemaIssues.orThrow(
    SchemaView.loadSchemaViewFromUri(
      (repo / "model" / "converter" / "all_converters.yaml").toString,
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
      os.write.over(repo / "python" / "ohi_linkml" / "_generated.py", PyBindingsGen(schema))
      os.write.over(
        repo / "nativelib" / "src" / "eu" / "neverblink" / "linkml" / "nativelib" /
          "LinkMlCGenerators.scala",
        CApiGen(schema),
      )
    case "docs" =>
      os.write.over(
        repo / "website" / "src" / "generated" / "converters.json",
        DocsGen(schema),
        createFolders = true,
      )
      os.write.over(repo / "python" / "test_snippets.py", SnippetTests.python(schema))
      os.write.over(
        repo / "generator" / "npm" / "snippets.test.mjs",
        SnippetTests.javaScript(schema),
      )
      os.write.over(
        repo / "cli" / "test" / "src" / "eu" / "neverblink" / "linkml" / "cli" / "DocSnippetsSpec.scala",
        SnippetTests.scala(schema),
      )
    case other => sys.error(s"Unknown part '$other', expected cli, bindings or docs")
  }
}
