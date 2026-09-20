package eu.neverblink.linkml.optiongen

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.IOException
import java.nio.file.Files
import scala.sys.process.{Process, ProcessLogger}

class SchemaGenerationSpec extends AnyWordSpec, Matchers {
  import GenerationFixture.*

  private def compact(value: String): String = value.replaceAll("\\s+", " ").trim

  private def commentText(value: String): String = compact(value.replaceAll("\\n\\s*\\*(?!/)", " "))

  private def change(text: String, replacements: (String, String)*): String =
    replacements.foldLeft(text) { case (current, (before, after)) =>
      replaceOnce(current, before, after)
    }

  private def declaration(source: String, start: String): String = {
    val index = source.indexOf(start)
    require(index >= 0, s"Missing declaration $start")
    compact(source.substring(index))
  }

  private def jsProfile(block: String, profile: String): String = {
    val start = block.indexOf("            js:\n")
    val end = block.indexOf("            cli:\n", start)
    require(start >= 0 && end > start, "Missing JS profile boundaries")
    block.take(start) + profile + block.drop(end)
  }

  private def readCatalog(schema: String): Catalog =
    OptionSchemaReader.read(schema).fold(errors => fail(errors.mkString("\n")), identity)

  private def pythonMethod(source: String, name: String): String = {
    val start = source.indexOf(s"    def $name(")
    require(start >= 0, s"Missing Python method $name")
    val end = source.indexOf("\n    def ", start + 1)
    if end < 0 then source.substring(start) else source.substring(start, end)
  }

  private def render(fixture: GenerationFixture): Unit = {
    val before = fixture.destinations
    fixture.render()
    fixture.destinations shouldBe before
    fixture.stagedFiles.keySet shouldBe outputs
    fixture.rendered(cli) should startWith(scalaSentinel)
    fixture.rendered(npm) should endWith(markdownSentinel)
    fixture.rendered(tsOutput) should endWith(tsSentinel)
  }

  private def runPython(fixture: GenerationFixture, assertions: String): Unit = {
    val script = fixture.root.resolve("record_generated.py")
    Files.writeString(
      script,
      """import importlib.util
        |import sys
        |
        |spec = importlib.util.spec_from_file_location("fresh_options", sys.argv[1])
        |module = importlib.util.module_from_spec(spec)
        |spec.loader.exec_module(module)
        |
        |class Recorder(module.Generators):
        |    def __init__(self):
        |        self.calls = []
        |
        |    def _document(self, function, **options):
        |        self.calls.append(("document", function, options))
        |        return "document"
        |
        |    def _json(self, function, **options):
        |        self.calls.append(("json", function, options))
        |        return {"file": "content"}
        |
        |recorder = Recorder()
        |""".stripMargin + assertions,
    )
    val executable = sys.env.getOrElse("PYTHON", "python3")
    val output = new StringBuilder
    val exit = try {
      // Ignore Python environment settings that can disable assertions.
      Process(
        Seq(
          executable,
          "-I",
          script.toString,
          fixture.bundle.resolve("files").resolve(python).toString,
        ),
      ).!(ProcessLogger(line => { output.append(line).append('\n'); () }))
    } catch {
      case error: IOException =>
        fail(
          s"Could not execute generated Python with '$executable'. Set PYTHON to an interpreter.",
          error,
        )
    }
    withClue(s"Fresh generated Python failed:\n$output") { exit shouldBe 0 }
  }

  "Schema-driven generation" should {
    "keep required JS arguments after undefined-capable arguments in their declared positions" in GenerationFixture.withFixture {
      fixture =>
        val shacl = inClass(fixture.schemaText, "ShaclOptions", "RdfsOptions") { block =>
          jsProfile(
            block,
            """            js:
          |              parameters:
          |                - {selector: open, name: open}
          |                - {selector: onlyClassesFromRootSchema, name: onlyClassesFromRootSchema}
          |                - {selector: format, name: format, default: {kind: none}}
          |              omitted: []
          |""".stripMargin,
          )
        }
        fixture.updateSchema(inClass(shacl, "JsonSchemaOptions", "ShaclOptions") { block =>
          jsProfile(
            block,
            """            js:
          |              parameters:
          |                - {selector: treeRoot, name: treeRootOverride, default: {kind: none}}
          |                - {selector: open, name: open, default: {kind: none}}
          |              omitted: [treeRootInlineType, indentationStep, metadataLanguage, includeNull]
          |""".stripMargin,
          )
        })
        val ts = fixture.ts()
        ts should include(
          "shacl(schema: SchemaView, open: boolean | undefined, onlyClassesFromRootSchema: boolean | undefined, format: string): string;",
        )
        ts should include(
          "jsonSchema(schema: SchemaView, treeRootOverride: string | undefined, open: boolean): string;",
        )
    }

    "qualify parser calls when JS parameters have the same names" in GenerationFixture.withFixture {
      fixture =>
        val shacl = inClass(fixture.schemaText, "ShaclOptions", "RdfsOptions") { block =>
          jsProfile(
            block,
            """            js:
          |              parameters:
          |                - {selector: open, name: open}
          |                - {selector: onlyClassesFromRootSchema, name: onlyClassesFromRootSchema}
          |                - {selector: format, name: rdfFormat}
          |              omitted: []
          |""".stripMargin,
          )
        }
        val schema = inClass(shacl, "LinkMlOptions", "FrictionlessOptions") { block =>
          replaceOnce(block, "name: outFormat", "name: outputFormat")
        }
        val methods = JsGen.regions(readCatalog(schema))
        methods("js.shacl") should include("format = this.rdfFormat(rdfFormat)")
        methods("js.linkml") should include("val format = this.outputFormat(outputFormat)")
    }

    "omit unexposed JS conversions while retaining receiver evaluation order" in GenerationFixture.withFixture {
      fixture =>
        for {
          (id, className, next) <- Vector(
            ("linkml", "LinkMlOptions", "FrictionlessOptions"),
            ("ossie", "OssieOptions", "ScalaOptions"),
          )
          omitted <- Vector(
            Set("outputFormat"),
            Set("pruningMode"),
            Set("outputFormat", "pruningMode"),
          )
        } withClue(s"$id omits ${omitted.mkString(", ")}: ") {
          val parameters = Vector(
            "pruningMode" -> "{selector: pruningMode.mode, name: pruningMode}",
            "pruningMode" -> "{selector: pruningMode.treeRoot, name: treeRoot}",
            "outputFormat" -> "{selector: outputFormat, name: outFormat}",
          ).filterNot((field, _) => omitted(field)).map(_._2) ++
            Option.when(id == "linkml")("{selector: skipClassDerivation, name: skipDerivation}")
          val parameterLines = if parameters.isEmpty then " []\n"
          else parameters.map(parameter => s"                - $parameter\n").mkString("\n", "", "")
          val omittedFields = omitted ++ Option.when(id == "ossie")("metadataLanguage")
          val profile = "            js:\n              parameters:" + parameterLines +
            s"              omitted: [${omittedFields.toVector.sorted.mkString(", ")}]\n"
          val schema = inClass(fixture.schemaText, className, next)(jsProfile(_, profile))
          val method = JsGen.regions(readCatalog(schema))(s"js.$id")
          val body = method.substring(method.indexOf("): String ="))
          omitted.foreach(field => body should not include s"$field =")
          val receiver = body.indexOf("using schema.underlying")
          if omitted("outputFormat") then body should not include "this.outputFormat("
          else {
            val format = body.indexOf("this.outputFormat(outFormat)")
            format should be >= 0
            format should be < receiver
          }
          if omitted("pruningMode") then body should not include "PruningMode("
          else {
            val pruning = body.indexOf("PruningMode(pruningMode, treeRoot.toOption)")
            pruning should be >= 0
            if id == "linkml" then {
              pruning should be < receiver
              if !omitted("outputFormat") then
                pruning should be < body.indexOf("this.outputFormat(outFormat)")
            } else pruning should be > receiver
          }
          if omitted == Set("outputFormat", "pruningMode") then body should not include "val "
        }
    }

    "propagate changed core definitions, ranks and falsy defaults" in GenerationFixture.withFixture {
      fixture =>
        val jsonChanged = inClass(fixture.schemaText, "JsonSchemaOptions", "ShaclOptions") {
          block =>
            change(
              block,
              "Options for generating JSON Schema." -> "Fixture JSON options.",
              "omitted: [treeRootInlineType, indentationStep, metadataLanguage, includeNull]" ->
                "omitted: [treeRootInlineType, indentationStep, metadataLanguage, includeNull, traceLabel]",
              "omitted: [indentationStep, metadataLanguage]" ->
                "omitted: [indentationStep, metadataLanguage, traceLabel]",
              "        rank: 1\n        ifabsent: 'False'" ->
                "        rank: 7\n        ifabsent: 'True'",
              "        rank: 2\n        description: >-\n          If defined, override the schema" ->
                "        rank: 2\n        ifabsent: string()\n        description: >-\n          If defined, override the schema",
              "ifabsent: int(2)" -> "ifabsent: int(0)",
              "ifabsent: string(en)" -> "ifabsent: string()",
              "description: >-\n          Number of spaces in pretty print indentation of the serialized JSON Schema." ->
                "description: Fixture indentation permits zero.",
              "    attributes:\n" ->
                """    attributes:
              |      traceLabel:
              |        range: string
              |        required: true
              |        rank: 1
              |        ifabsent: string(fixture)
              |        description: Fixture-only scalar label.
              |""".stripMargin,
            )
        }
        val shaclChanged = inClass(jsonChanged, "ShaclOptions", "RdfsOptions") { block =>
          replaceOnce(block, "ifabsent: RdfFormat(ttl)", "ifabsent: RdfFormat(nt)")
        }
        fixture.updateSchema(inClass(shaclChanged, "LinkMlOptions", "FrictionlessOptions") {
          block =>
            replaceOnce(
              block,
              "ifabsent: JsonOutputFormat(yaml)",
              "ifabsent: JsonOutputFormat(json)",
            )
        })
        render(fixture)

        val coreDefinition = region(fixture.rendered(core("json_schema")), "core.json_schema")
        declaration(coreDefinition, "final case class Options(") shouldBe compact(
          """final case class Options(
          |    traceLabel: String = "fixture",
          |    treeRoot: Option[String] = Some(""),
          |    treeRootInlineType: Option[String] = None,
          |    indentationStep: Int = 0,
          |    metadataLanguage: String = "",
          |    includeNull: Boolean = false,
          |    open: Boolean = true,
          |)
          |""".stripMargin,
        )
        coreDefinition should include("Fixture JSON options.")
        coreDefinition should include("Fixture-only scalar label.")
        coreDefinition should include("Fixture indentation permits zero.")
        compact(region(fixture.rendered(core("shacl")), "core.shacl")) should include(
          "format: _root_.eu.neverblink.linkml.generator.rdf.RdfFormat = _root_.eu.neverblink.linkml.generator.rdf.RdfFormat.nt,",
        )
        compact(region(fixture.rendered(core("linkml")), "core.linkml")) should include(
          "outputFormat: _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat = _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat.json,",
        )

        val jsonMethod = pythonMethod(fixture.rendered(python), "json_schema")
        compact(
          jsonMethod.substring(0, jsonMethod.indexOf(") -> str:") + ") -> str:".length),
        ) shouldBe
          compact("""def json_schema(
          |    self,
          |    *,
          |    trace_label: str = "fixture",
          |    tree_root: str | None = "",
          |    tree_root_inline_type: str | None = None,
          |    indentation_step: int = 0,
          |    metadata_language: str = "",
          |    include_null: bool = False,
          |    open: bool = True,
          |) -> str:
          |""".stripMargin)
        declaration(jsonMethod, "return self._document(") shouldBe compact(
          """return self._document(
          |    "linkml_json_schema",
          |    traceLabel=trace_label,
          |    treeRoot=tree_root,
          |    treeRootInlineType=tree_root_inline_type,
          |    indentationStep=indentation_step,
          |    metadataLanguage=metadata_language,
          |    includeNull=include_null,
          |    open=open,
          |)
          |""".stripMargin,
        )
        jsonMethod should include(":param trace_label: Fixture-only scalar label.")
        jsonMethod should include(":param indentation_step: Fixture indentation permits zero.")
        commentText(fixture.rendered(native)) should include(
          "/** Fixture JSON options. Options: `traceLabel`, `treeRoot`, `treeRootInlineType`, " +
            "`indentationStep`, `metadataLanguage`, `includeNull`, `open`. */",
        )
        val docs = fixture.rendered(reference)
        docs should include(
          " ` traceLabel `<br>Fixture-only scalar label. | ` String ` | ` \"fixture\" ` | " +
            "Unexposed | Unexposed | ` trace_label ` = ` \"fixture\" ` |",
        )
        docs should include(
          " ` indentationStep `<br>Fixture indentation permits zero. | ` Int ` | ` 0 ` | " +
            "Unexposed | Unexposed | ` indentation_step ` = ` 0 ` |",
        )
        docs should include(
          "schema.json_schema(*, trace_label=\"fixture\", tree_root=\"\", " +
            "tree_root_inline_type=None, indentation_step=0, metadata_language=\"\", include_null=False, open=True)",
        )
        fixture.rendered(pythonDocs) should include("    trace_label=\"fixture\",")
        fixture.rendered(npm) should include("Fixture JSON options.")

        runPython(
          fixture,
          """assert recorder.json_schema() == "document"
        |assert recorder.calls.pop() == ("document", "linkml_json_schema", {
        |    "traceLabel": "fixture", "treeRoot": "", "treeRootInlineType": None,
        |    "indentationStep": 0, "metadataLanguage": "", "includeNull": False, "open": True,
        |})
        |recorder.json_schema(trace_label="", tree_root=None, tree_root_inline_type="list",
        |                     indentation_step=4, metadata_language="fr", include_null=True, open=False)
        |assert recorder.calls.pop() == ("document", "linkml_json_schema", {
        |    "traceLabel": "", "treeRoot": None, "treeRootInlineType": "list",
        |    "indentationStep": 4, "metadataLanguage": "fr", "includeNull": True, "open": False,
        |})
        |recorder.shacl()
        |assert recorder.calls.pop() == ("document", "linkml_shacl", {
        |    "open": False, "onlyClassesFromRootSchema": False, "format": "nt",
        |})
        |recorder.linkml()
        |assert recorder.calls.pop() == ("document", "linkml_linkml", {
        |    "pruningMode": "skip", "skipClassDerivation": False, "outputFormat": "json",
        |})
        |assert recorder.calls == []
        |""".stripMargin,
        )
    }

    "project renamed, reordered, required and omitted interface parameters" in GenerationFixture.withFixture {
      fixture =>
        fixture.updateSchema(inClass(fixture.schemaText, "ShaclOptions", "RdfsOptions") { block =>
          val start = block.indexOf("            js:\n")
          val end = block.indexOf("    attributes:\n", start)
          require(start >= 0 && end > start)
          block.take(start) + """            js:
          |              parameters:
          |                - {selector: format, name: syntax, default: {kind: none}}
          |                - {selector: open, name: allowExtra, default: {kind: literal, value: true}}
          |              omitted: [onlyClassesFromRootSchema]
          |            cli:
          |              parameters:
          |                - selector: format
          |                  name: rdfSyntax
          |                  default: {kind: literal, value: nt}
          |                  description: Fixture RDF syntax. Default nt.
          |                - selector: open
          |                  name: allowExtra
          |                  default: {kind: literal, value: true}
          |                  description: Fixture extra properties. Default true.
          |              omitted: [onlyClassesFromRootSchema]
          |""".stripMargin + block.drop(end)
        })
        render(fixture)

        declaration(
          region(fixture.rendered(core("shacl")), "core.shacl"),
          "final case class Options(",
        ) shouldBe
          compact("""final case class Options(
          |    open: Boolean = false,
          |    onlyClassesFromRootSchema: Boolean = false,
          |    format: _root_.eu.neverblink.linkml.generator.rdf.RdfFormat = _root_.eu.neverblink.linkml.generator.rdf.RdfFormat.ttl,
          |) extends _root_.eu.neverblink.linkml.generator.rdf.RdfOptions
          |""".stripMargin)
        val jsMethod = region(fixture.rendered(js), "js.shacl")
        declaration(jsMethod, "def shacl(") shouldBe compact(
          """def shacl(
          |    schema: SchemaViewJs,
          |    syntax: String,
          |    allowExtra: Boolean = true,
          |): String =
          |    ShaclGenerator(using schema.underlying).serialize(
          |        ShaclGenerator.Options(
          |            open = allowExtra,
          |            format = this.rdfFormat(syntax),
          |        ),
          |    )
          |""".stripMargin,
        )
        compact(region(fixture.rendered(cli), "cli.shacl.fields")) shouldBe compact(
          """@HelpMessage("Fixture RDF syntax. Default nt.")
          |rdfSyntax: String = "nt",
          |@HelpMessage("Fixture extra properties. Default true.")
          |allowExtra: Boolean = true,
          |""".stripMargin,
        )
        compact(region(fixture.rendered(cli), "cli.shacl.options")) shouldBe compact(
          """ShaclGenerator.Options(
          |    open = options.allowExtra,
          |    format = RdfOutput.parse(options.rdfSyntax).getOrElse(
          |        err(RdfOutput.unknownFormat(options.rdfSyntax)),
          |    ),
          |),
          |""".stripMargin,
        )
        val tsSignature = "shacl(schema: SchemaView, syntax: string, allowExtra?: boolean): string;"
        fixture.rendered(tsOutput).linesIterator.find(
          _.trim.startsWith("shacl("),
        ).get.trim shouldBe tsSignature
        val standaloneTs = fixture.ts()
        standaloneTs.linesIterator.find(_.trim.startsWith("shacl(")).get.trim shouldBe tsSignature
        standaloneTs should endWith(tsSentinel)
        val docs = fixture.rendered(reference)
        docs should include("LinkML.shacl(schema, syntax, allowExtra = true)")
        docs should include(
          " ` onlyClassesFromRootSchema `<br>Whether to include only classes from the root schema. " +
            "This is useful if you intend to generate SHACL shapes for each schema file separately, " +
            "and you don't need the imported classes to be included in the generated SHACL shapes. | " +
            "` Boolean ` | ` false ` | Unexposed | Unexposed | ` only_classes_from_root_schema ` = ` False ` |",
        )
        docs should include(
          " ` open `<br>Whether the generated shapes should be open, allowing properties the schema does not mention. | ` Boolean ` | ` false ` | " +
            "` --allow-extra ` = **` true `** | ` allowExtra ` = **` true `** | ` open ` = ` False ` |",
        )
        docs should include(
          " ` format `<br>Which RDF serialization to write: `ttl` for Turtle, which is prefixed and pretty-printed, or `nt` for N-Triples. | ` RdfFormat ` | ` RdfFormat.ttl ` | " +
            "` --rdf-syntax ` = **` \"nt\" `** | ` syntax ` = **` required `** | ` format ` = ` \"ttl\" ` |",
        )
        fixture.rendered(npm) should include(
          " ` shacl(view, syntax, allowExtra?) ` | ` string ` | Options for generating SHACL shapes. |",
        )
    }

    "preserve structured pruning roots and shared descriptions across boundaries" in GenerationFixture.withFixture {
      fixture =>
        val descriptions = change(
          fixture.schemaText,
          "description: Which unused elements (classes, types, enums) to remove." ->
            "description: Fixture reachability selection.",
          "description: Tree root class name to use instead of the schema-defined tree_root." ->
            "description: Fixture root override.",
          "description: remove all elements unreachable from the tree_root class." ->
            "description: retain only elements reachable from the selected fixture root.",
        )
        val linkmlChanged = inClass(descriptions, "LinkMlOptions", "FrictionlessOptions") { block =>
          change(
            block,
            "value: {mode: skip}" -> "value: {mode: tree_root, treeRoot: CoreRoot}",
            "defaults: {pruningMode: {mode: tree_root}}" ->
              "defaults: {pruningMode: {mode: tree_root, treeRoot: JsRoot}}",
            "selector: pruningMode.mode\n                  name: pruningMode" ->
              "selector: pruningMode.mode\n                  name: reachability",
            "selector: pruningMode.treeRoot, name: treeRoot" ->
              "selector: pruningMode.treeRoot, name: selectedRoot",
          )
        }
        val cliDefault = "defaults: {pruningMode: {mode: skip}}"
        linkmlChanged.sliding(cliDefault.length).count(_ == cliDefault) shouldBe 5
        fixture.updateSchema(
          linkmlChanged.replace(
            cliDefault,
            "defaults: {pruningMode: {mode: tree_root, treeRoot: CliRoot}}",
          ),
        )
        render(fixture)

        declaration(
          region(fixture.rendered(core("linkml")), "core.linkml"),
          "final case class Options(",
        ) shouldBe
          compact("""final case class Options(
          |    pruningMode: _root_.eu.neverblink.linkml.generator.util.PruningMode = _root_.eu.neverblink.linkml.generator.util.PruningMode.treeRoot(Some("CoreRoot")),
          |    skipClassDerivation: Boolean = false,
          |    outputFormat: _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat = _root_.eu.neverblink.linkml.generator.util.JsonOutputFormat.yaml,
          |)
          |""".stripMargin)
        val linkmlMethod = pythonMethod(fixture.rendered(python), "linkml")
        compact(
          linkmlMethod.substring(0, linkmlMethod.indexOf(") -> str:") + ") -> str:".length),
        ) shouldBe
          compact("""def linkml(
          |    self,
          |    *,
          |    pruning_mode: str = "treeRoot",
          |    tree_root: str | None = "CoreRoot",
          |    skip_class_derivation: bool = False,
          |    output_format: str = "yaml",
          |) -> str:
          |""".stripMargin)
        declaration(linkmlMethod, "return self._document(") shouldBe compact(
          """return self._document(
          |    "linkml_linkml",
          |    pruningMode=_pruning(pruning_mode, tree_root),
          |    skipClassDerivation=skip_class_derivation,
          |    outputFormat=output_format,
          |)
          |""".stripMargin,
        )
        linkmlMethod should include(":param tree_root: Fixture root override.")
        val jsMethod = region(fixture.rendered(js), "js.linkml")
        declaration(jsMethod, "def linkml(") shouldBe compact(
          """def linkml(
          |    schema: SchemaViewJs,
          |    reachability: String = "treeRoot",
          |    skipDerivation: Boolean = false,
          |    selectedRoot: js.UndefOr[String] = "JsRoot",
          |    outFormat: String = "yaml",
          |): String = {
          |    val mode = PruningMode(reachability, selectedRoot.toOption)
          |    val format = this.outputFormat(outFormat)
          |    LinkMlGenerator(using schema.underlying).serialize(
          |        LinkMlGenerator.Options(
          |            pruningMode = mode,
          |            skipClassDerivation = skipDerivation,
          |            outputFormat = format,
          |        ),
          |    )
          |}
          |""".stripMargin,
        )
        commentText(jsMethod) should include(
          "Fixture root override. Ignored outside tree-root pruning mode.",
        )
        commentText(jsMethod) should include(
          "retain only elements reachable from the selected fixture root.",
        )
        compact(region(fixture.rendered(pruning), "cli.pruning.fields")) shouldBe compact(
          """@HelpMessage(
          |    "Fixture reachability selection.\ntreeRoot - retain only elements reachable from the selected fixture root.\nschema - remove all elements unreachable from any of the classes defined in the root schema.\nskip - do not remove unused elements.\nDefault: treeRoot.",
          |)
          |pruningMode: PruningMode = PruningMode.treeRoot(None),
          |@HelpMessage("Fixture root override.")
          |treeRoot: Option[String] = Some("CliRoot"),
          |""".stripMargin,
        )
        compact(region(fixture.rendered(cli), "cli.linkml.options")) shouldBe compact(
          """LinkMlGenerator.Options(
          |    pruningMode = options.pruning.resolvedPruningMode,
          |    skipClassDerivation = options.skipDerivation,
          |    outputFormat = JsonOutputFormat.parse(options.format).getOrElse(
          |        err(JsonOutputFormat.unknownFormat(options.format)),
          |    ),
          |),
          |""".stripMargin,
        )
        val docs = fixture.rendered(reference)
        docs should include(
          " ` tree_root ` | ` \"treeRoot\" ` | retain only elements reachable from the selected fixture root. |",
        )
        docs should include(
          " ` pruningMode `<br>Method to use for schema definition pruning. | ` PruningMode ` | " +
            "` PruningMode.treeRoot(Some(\"CoreRoot\")) ` | " +
            "` --pruning-mode ` = ` \"treeRoot\" `<br>` --tree-root ` = **` \"CliRoot\" `** | " +
            "` reachability ` = ` \"treeRoot\" `<br>` selectedRoot ` = **` \"JsRoot\" `** | " +
            "` pruning_mode ` = ` \"treeRoot\" `<br>` tree_root ` = ` \"CoreRoot\" ` |",
        )
        fixture.rendered(pythonDocs) should include("    tree_root=\"CoreRoot\",")
        runPython(
          fixture,
          """recorder.linkml()
        |assert recorder.calls.pop() == ("document", "linkml_linkml", {
        |    "pruningMode": {"treeRoot": "CoreRoot"}, "skipClassDerivation": False, "outputFormat": "yaml",
        |})
        |recorder.linkml(tree_root="", skip_class_derivation=True, output_format="json")
        |assert recorder.calls.pop() == ("document", "linkml_linkml", {
        |    "pruningMode": {"treeRoot": ""}, "skipClassDerivation": True, "outputFormat": "json",
        |})
        |recorder.linkml(pruning_mode="skip", tree_root=None)
        |assert recorder.calls.pop() == ("document", "linkml_linkml", {
        |    "pruningMode": "skip", "skipClassDerivation": False, "outputFormat": "yaml",
        |})
        |assert recorder.frictionless(pruning_mode="tree_root", tree_root="TableRoot",
        |                            metadata_language="") == {"file": "content"}
        |assert recorder.calls.pop() == ("json", "linkml_frictionless", {
        |    "pruningMode": {"treeRoot": "TableRoot"}, "skipClassesWithoutIdentifier": False,
        |    "metadataLanguage": "",
        |})
        |assert recorder.calls == []
        |""".stripMargin,
        )
    }
  }
}
