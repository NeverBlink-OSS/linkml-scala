package eu.neverblink.linkml.generator.typescript

import eu.neverblink.linkml.schemaview.{
  ClassView,
  CollectionForm,
  InlineType,
  SchemaIssues,
  SchemaView,
}
import eu.neverblink.linkml.tests.{ModelCatalogue, ModelCatalogueSpec}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.util.Try

/** Type-checks the generated TypeScript of every catalogue model with the real `tsc`, and checks
  * the catalogue's JSON data against it: valid data must type-check, invalid data must not.
  *
  * Each data file is the generated model followed by `export const data: <tree root type> = <the
  * JSON>`, so `tsc` checks the JSON as an object literal (which includes excess property checks).
  * The compiler is installed by `./mill generator.jvm.test.typescriptCompiler`.
  */
class TypeScriptCompileSpec extends AnyWordSpec, Matchers, ModelCatalogueSpec {
  import TypeScriptCompileSpec.*

  /** Directory with `node_modules/typescript`, prepared by the mill task. */
  val compiler: Option[os.Path] =
    Option(System.getenv("LINKML_TYPESCRIPT_COMPILER"))
      .filter(_.nonEmpty)
      .map(os.Path(_, os.pwd))
      .filter(dir => os.exists(dir / "node_modules" / "typescript"))

  val inCi: Boolean = Option(System.getenv("CI")).exists(_.nonEmpty)

  override val skipModels: Map[String, String] = Map(
    "unionRange" -> "Not yet implemented: LNK-100",
  )

  private val bounds = "TypeScript types cannot express minimum_value/maximum_value"
  private val pattern = "TypeScript types cannot express patterns"

  /** Invalid instances whose problem the types cannot catch. Only applies to invalid instances,
    * because valid and invalid instances share names.
    */
  val inexpressible: Map[(String, String), String] = Map(
    "constraints" -> "example1" -> bounds,
    "constraints" -> "example2" -> bounds,
    "constraints" -> "example3" -> pattern,
    "constraintsOnTypes" -> "example1" -> bounds,
    "constraintsOnTypes" -> "example2" -> bounds,
    "constraintsOnTypes" -> "example3" -> pattern,
    "typeDerivation" -> "excessiveCount" -> bounds,
    "typeDerivation" -> "negativeCount" -> bounds,
    "typeDerivation" -> "lowercaseCode" -> pattern,
    "typed" -> "notDate" -> "TypeScript types cannot express string formats such as dates",
  )

  /** A model to check, with its valid and invalid documents by name. */
  private case class Model(
      name: String,
      sv: SchemaView,
      valid: Seq[(String, String)],
      invalid: Seq[(String, String)],
  )

  private val catalogueModels: Seq[Model] = ModelCatalogue.all.map { entry =>
    def jsons(instances: Seq[ModelCatalogue.InstanceInFormats]) =
      instances.distinct.flatMap(i => i.json.map(i.name -> _))
    Model(entry.name, entry.model, jsons(entry.validInstances), jsons(entry.invalidInstances))
  }

  /** No catalogue model has a tree root in another form than `plain`, so these cover the rest. */
  private val documentForms: Seq[Model] = {
    def model(name: String, form: String, attributes: String) = SchemaIssues.orThrow(
      SchemaView.loadSchemaViewFromString(
        s"""id: https://neverblink.eu/linkml/typescript/$name/
           |name: $name
           |imports:
           |  - linkml:types
           |classes:
           |  Item:
           |    tree_root: true
           |    extensions:
           |      tree_root_as: $form
           |    attributes:
           |$attributes""".stripMargin,
      ),
    )
    val plainAttributes =
      """      name:
        |        required: true
        |      count:
        |        range: integer
        |""".stripMargin
    Seq(
      Model(
        "optionalDocument",
        model("optionalDocument", "optional", plainAttributes),
        Seq("null" -> "null", "object" -> """{"name": "a"}"""),
        Seq("list" -> """[{"name": "a"}]"""),
      ),
      Model(
        "listDocument",
        model("listDocument", "list", plainAttributes),
        Seq("empty" -> "[]", "two" -> """[{"name": "a"}, {"name": "b", "count": 2}]"""),
        Seq("object" -> """{"name": "a"}""", "wrongType" -> """[{"name": "a", "count": "2"}]"""),
      ),
      Model(
        "compactDictDocument",
        model(
          "compactDictDocument",
          "compact_dict",
          """      id:
            |        identifier: true
            |      name:
            |      count:
            |        range: integer
            |""".stripMargin,
        ),
        Seq(
          "entries" -> """{"a": {"name": "A", "count": 1}, "b": {"id": "b"}, "c": {}}""",
        ),
        Seq(
          "number" -> """{"a": 1}""",
          "unknownKey" -> """{"a": {"colour": "red"}}""",
          "wrongType" -> """{"a": {"count": "1"}}""",
        ),
      ),
      Model(
        "simpleDictDocument",
        model(
          "simpleDictDocument",
          "simple_dict",
          """      key:
            |        key: true
            |      label:
            |""".stripMargin,
        ),
        Seq(
          "entries" -> """{"a": "A", "b": {"label": "B"}, "c": {"key": "c", "label": "C"}}""",
        ),
        Seq("number" -> """{"a": 1}""", "unknownKey" -> """{"a": {"colour": "red"}}"""),
      ),
    )
  }

  /** Generated files of one model, by file name relative to the output directory. */
  private case class Generated(
      model: Try[String],
      modelFile: String,
      modelLines: Int,
      instanceFiles: Map[(Boolean, String), String],
  )

  private lazy val outDir: os.Path = os.temp.dir(prefix = "linkml-ts-")

  private lazy val generated: Map[String, Generated] =
    (catalogueModels ++ documentForms).map { entry =>
      val dir = safeName(entry.name)
      val modelFile = s"$dir/model.ts"
      val model = Try(TypeScriptGenerator(using entry.sv).serialize())
      val rootType = entry.sv.treeRoot.map(root => documentType(root)(using entry.sv))
      val instanceFiles = (for
        source <- model.toOption.toSeq
        tpe <- rootType.toSeq
        (valid, instances) <- Seq(true -> entry.valid, false -> entry.invalid)
        (name, json) <- instances
      yield {
        // A dict-shaped document needs KeyOptional, which the model only has if it uses it too
        val keyOptional =
          if source.contains("export type KeyOptional") then ""
          else TypeScriptGenerator.keyOptionalDefinition
        val prefix = if valid then "valid" else "invalid"
        val file = s"$dir/${prefix}_${safeName(name)}.ts"
        os.write.over(
          outDir / os.RelPath(file),
          s"$source\n$keyOptional\nexport const data: $tpe = ${json.strip};\n",
          createFolders = true,
        )
        (valid, name) -> file
      }).toMap
      model.foreach(source =>
        os.write.over(outDir / os.RelPath(modelFile), source, createFolders = true),
      )
      entry.name -> Generated(
        model,
        modelFile,
        model.map(_.linesIterator.size).getOrElse(0),
        instanceFiles,
      )
    }.toMap

  /** `tsc` diagnostics by file, from a single run over all the generated files. */
  private lazy val diagnostics: Map[String, Seq[Diagnostic]] = {
    val dir = compiler.getOrElse(throw IllegalStateException("no TypeScript compiler"))
    generated // write the files
    os.write.over(outDir / "tsconfig.json", tsconfig)
    // The generated code is an ES module
    os.write.over(outDir / "package.json", """{"type": "module"}""")
    val result = os.call(
      ("node", (dir / "node_modules" / "typescript" / "bin" / "tsc").toString, "-p", "."),
      cwd = outDir,
      check = false,
      stderr = os.Pipe,
    )
    val parsed = result.out.lines().flatMap(parseDiagnostic)
    if result.exitCode != 0 && parsed.isEmpty then
      throw RuntimeException(
        s"tsc exited with ${result.exitCode} and no diagnostics:\n" +
          result.out.text() + result.err.text(),
      )
    parsed.groupBy(_.file)
  }

  private def requireCompiler(): Unit =
    if compiler.isEmpty then {
      if inCi then fail("The TypeScript compiler is not installed")
      else cancel("Run `./mill generator.jvm.test.typescriptCompiler` to enable this test")
    }

  private def errorsIn(file: String, fromLine: Int): Seq[Diagnostic] =
    diagnostics.getOrElse(file, Nil).filter(_.line > fromLine)

  /** The errors in one document, after checking that its model type-checks. */
  private def documentErrors(model: String, valid: Boolean, document: String): Seq[Diagnostic] = {
    requireCompiler()
    val gen = generated(model)
    gen.model.get
    withClue("the model itself: ")(errorsIn(gen.modelFile, 0) shouldBe empty)
    val file = gen.instanceFiles.getOrElse((valid, document), cancel("The model has no tree root"))
    errorsIn(file, gen.modelLines)
  }

  "TypeScript generator" should {
    for entry <- ModelCatalogue.all do
      s"generate TypeScript for model '${entry.name}'" when {
        "the model itself" should {
          "type-check" in {
            assume(!skipModels.contains(entry.name), skipModels.getOrElse(entry.name, ""))
            requireCompiler()
            val gen = generated(entry.name)
            gen.model.get
            errorsIn(gen.modelFile, 0) shouldBe empty
          }
        }
        for
          (valid, instances) <- Seq(true -> entry.validInstances, false -> entry.invalidInstances)
          instance <- instances.filter(_.json.isDefined).distinct
        do
          val kind = if valid then "valid" else "invalid"
          s"$kind instance '${instance.name}'" in {
            processSkip(entry, instance)
            if !valid then
              assume(
                !inexpressible.contains((entry.name, instance.name)),
                inexpressible.getOrElse((entry.name, instance.name), ""),
              )
            val errors = documentErrors(entry.name, valid, instance.name)
            if valid then errors shouldBe empty
            else errors should not be empty
          }
      }

    for model <- documentForms do
      s"type a document of model '${model.name}'" when {
        for (document, _) <- model.valid do
          s"valid document '$document'" in {
            documentErrors(model.name, true, document) shouldBe empty
          }
        for (document, _) <- model.invalid do
          s"invalid document '$document'" in {
            documentErrors(model.name, false, document) should not be empty
          }
      }
  }
}

object TypeScriptCompileSpec {

  /** One `tsc` error, with the line in its file. */
  final case class Diagnostic(file: String, line: Int, message: String)

  private val diagnosticPattern = """^(.+?)\((\d+),\d+\): error (.*)$""".r

  private def parseDiagnostic(line: String): Option[Diagnostic] = line match {
    case diagnosticPattern(file, lineNo, message) => Some(Diagnostic(file, lineNo.toInt, message))
    case _ => None
  }

  /** TS type of a whole JSON document: the tree root in its `tree_root_as` form, written with the
    * names the generated model has. The value of a simple dict entry is looked up in the model with
    * an indexed access type, rather than worked out here again.
    */
  def documentType(root: ClassView)(using SchemaView): String = {
    val gen = TypeScriptGenerator()
    val ref = gen.unionName(root).getOrElse(gen.className(root))
    def key(slot: String) =
      TypeScriptGenerator.stringLiteral(gen.slotName(root.derivedAttributes(slot)))
    root.treeRootInlineType(None) match {
      case InlineType.plain => ref
      case InlineType.optional => s"$ref | null"
      case InlineType.list => s"$ref[]"
      case InlineType.dict(CollectionForm.CompactDict(k)) =>
        s"Record<string, KeyOptional<$ref, ${key(k)}>>"
      case InlineType.dict(CollectionForm.SimpleDict(k, v)) =>
        s"Record<string, $ref[${key(v)}] | KeyOptional<$ref, ${key(k)}>>"
    }
  }

  private def safeName(name: String): String = name.map(c => if c.isLetterOrDigit then c else '_')

  private val tsconfig: String =
    """{
      |  "compilerOptions": {
      |    "strict": true,
      |    "noEmit": true,
      |    "target": "es2022",
      |    "module": "nodenext",
      |    "erasableSyntaxOnly": true,
      |    "verbatimModuleSyntax": true,
      |    "pretty": false,
      |    "types": []
      |  },
      |  "include": ["**/*.ts"]
      |}
      |""".stripMargin
}
