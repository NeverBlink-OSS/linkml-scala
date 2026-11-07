package eu.neverblink.linkml.optionsgen

/** An SDK, with the name and the code block language of its tab in the docs. */
enum Sdk(val label: String, val lang: String) {
  case Cli extends Sdk("CLI", "shell")
  case Python extends Sdk("Python", "python")
  case JavaScript extends Sdk("JavaScript", "js")
  case Scala extends Sdk("Scala", "scala")
  case C extends Sdk("C", "c")
}

/** The example of one converter in one SDK, which the docs show and the tests run.
  *
  * @param imports
  *   the imports, which a test with several snippets needs on their own
  * @param result
  *   the variable with the output, which the tests check
  */
final case class Snippet(sdk: Sdk, imports: Seq[String], body: String, result: String) {
  def code: String = if imports.isEmpty then body else imports.mkString("\n") + "\n\n" + body
}

/** The examples of each converter. They start from PROV-O, as `ohi get w3c/prov-o` saves it, and
  * set the options that have `examples` in the schema.
  */
object Snippets {

  val generatorInput = "prov-o.linkml.yaml"

  /** The file that `ohi get w3c/prov-o` saves for each importer, and the `--format` that picks it.
    */
  private val importerInputs = Map(
    "from_owl" -> (format = "owl-turtle", file = "prov-o.ttl"),
    "from_ossie" -> (format = "ossie-yaml", file = "prov-o.ossie.yaml"),
  )

  /** Where the command line example writes. Stdout for the converters not here. */
  private val cliOutputs = Map(
    "json_schema" -> "prov-o.schema.json",
    "shacl" -> "prov-o.shacl.ttl",
    "rdfs" -> "prov-o.rdfs.ttl",
    "linkml" -> "prov-o.derived.yaml",
    "frictionless" -> "datapackage/",
    "graphql" -> "prov-o.graphql",
    "typescript" -> "prov-o.ts",
    "pydantic" -> "prov_o.py",
    "er_diagram" -> "prov-o.mmd",
    "docs" -> "prov-o.md",
    "ossie" -> "prov-o.ossie.json",
    "owl" -> "prov-o.owl.ttl",
    "scala" -> "src/org/example/prov/",
    "translation" -> "prov-o.translation.json",
    "from_owl" -> "prov.yaml",
    "from_ossie" -> "prov.yaml",
  )

  /** The input file of a converter. */
  def input(function: ConverterFunction): String =
    if function.isImporter then importerInputs(function.name).file else generatorInput

  def apply(function: ConverterFunction, schema: OptionsSchema): Seq[Snippet] = {
    val examples = function.options.fields.flatMap(f => f.examples.headOption.map(f -> _))
    examples.foreach { (f, _) =>
      if f.valueClass.isDefined then
        sys.error(s"Option ${f.name} of ${function.name}: examples of objects are not supported")
    }
    function.options.fields.filter(f => f.required && f.default.isEmpty)
      .filterNot(f => examples.exists(_._1 == f))
      .foreach(f => sys.error(s"Required option ${f.name} of ${function.name} needs an example"))
    Seq(
      cli(function, examples),
      python(function, examples),
      javaScript(function, examples),
      scala(function, examples, schema),
      c(function, examples),
    )
  }

  private type Examples = Seq[(OptionField, String)]

  private def cli(function: ConverterFunction, examples: Examples): Snippet = {
    val get = if function.isImporter then
      s"ohi get w3c/prov-o --format ${importerInputs(function.name).format}"
    else "ohi get w3c/prov-o"
    val flags = examples.map { (f, value) =>
      if !f.onCli then sys.error(s"Option ${f.name} of ${function.name} is not on the command line")
      if f.valueType == "Boolean" then if value == "true" then f.cliFlag else s"${f.cliFlag}=false"
      else s"${f.cliFlag} $value"
    }
    val to = cliOutputs.get(function.name).map(path => s"--to $path")
    val command = (s"ohi linkml ${function.cliCommand}" +: flags).mkString(" ")
    val rest = (to.toSeq :+ input(function)).mkString(" ")
    val lines =
      if command.length + rest.length + 1 <= Code.maxLine then Seq(s"$command $rest")
      else Seq(s"$command \\", s"  $rest")
    Snippet(Sdk.Cli, Nil, (get +: lines).mkString("\n"), "")
  }

  private def python(function: ConverterFunction, examples: Examples): Snippet = {
    val args = examples.map((f, value) => s"${f.name}=${pythonValue(f, value)}")
    if function.isImporter then {
      val call = Code.call(
        s"result = ohi_linkml.${function.name}(",
        s"""Path("${input(function)}").read_text()""" +: args,
        ")",
        "    ",
      )
      Snippet(
        Sdk.Python,
        Seq("from pathlib import Path", "", "import ohi_linkml"),
        call,
        "result",
      )
    } else {
      val result = resultName(function)
      val call = Code.call(s"$result = schema.${function.name}(", args, ")", "    ", "    ")
      val body = s"""with ohi_linkml.load_file("$generatorInput") as schema:
                    |$call""".stripMargin
      Snippet(Sdk.Python, Seq("import ohi_linkml"), body, result)
    }
  }

  private def pythonValue(f: OptionField, value: String): String = f.valueType match {
    case "Boolean" => value.capitalize
    case "Int" => value
    case _ => Code.quoted(value)
  }

  private def javaScript(function: ConverterFunction, examples: Examples): Snippet = {
    val options =
      if examples.isEmpty then Nil
      else
        Seq(
          examples.map((f, value) => s"${f.name}: ${jsValue(f, value)}").mkString("{ ", ", ", " }"),
        )
    val imports = Seq(
      """import { readFileSync } from "node:fs";""",
      """import { LinkML } from "@neverblink/linkml";""",
    )
    val read = s"""readFileSync("${input(function)}", "utf8")"""
    if function.isImporter then {
      val call =
        Code.call(s"const result = LinkML.${function.scalaName}(", read +: options, ");", "  ")
      Snippet(Sdk.JavaScript, imports, call, "result")
    } else {
      val result = resultName(function)
      val body =
        s"""const yaml = $read;
           |const { view, report } = LinkML.loadFromString(yaml, {});
           |if (!view) throw new Error(JSON.stringify(report));
           |${Code.call(
            s"const $result = view.${function.scalaName}(",
            options,
            ");",
            "  ",
          )}""".stripMargin
      Snippet(Sdk.JavaScript, imports, body, result)
    }
  }

  private def jsValue(f: OptionField, value: String): String = f.valueType match {
    case "Boolean" | "Int" => value
    case _ => Code.quoted(value)
  }

  private def scala(
      function: ConverterFunction,
      examples: Examples,
      schema: OptionsSchema,
  ): Snippet = {
    val c = function.options
    val pkg = c.scalaName.stripSuffix("GeneratorOptions").stripSuffix("ImporterOptions").toLowerCase
    // A package named `java` would hide the JDK's `java` package from the code around it
    val scalaPackage = if pkg == "java" then "javagen" else pkg
    val className = c.scalaName.stripSuffix("Options")
    val args = examples.map((f, value) => s"${f.scalaName} = ${scalaValue(f, value, schema)}")
    val enums = examples.collect { case (f, _) if f.isEnum => f.valueType }
    val optionImports = (Option.when(args.nonEmpty)(c.scalaName).toSeq ++ enums).distinct
    val options = Option.when(args.nonEmpty)(s"${c.scalaName}(${args.mkString(", ")})")
    val imports = Seq(
      Option.when(optionImports.nonEmpty) {
        val prefix = "import eu.neverblink.linkml.generator.options."
        if optionImports.sizeIs == 1 && prefix.length + optionImports.head.length <= Code.maxLine
        then prefix + optionImports.head
        else prefix + "*"
      },
      Some(s"import eu.neverblink.linkml.generator.$scalaPackage.$className"),
      Option.when(!function.isImporter)(
        "import eu.neverblink.linkml.schemaview.{SchemaIssues, SchemaView}",
      ),
      Option.when(function.isImporter)("\nimport java.nio.file.{Files, Path}"),
    ).flatten
    if function.isImporter then {
      val read = s"""Files.readString(Path.of("${input(function)}"))"""
      val call = Code.call(
        s"val schema = $className().serializeFromString(",
        read +: options.toSeq,
        ")",
        "  ",
      )
      Snippet(Sdk.Scala, imports, call, "schema")
    } else {
      val method = function.returns match {
        case Returns.Document => "serialize"
        case Returns.Map(_) if function.name != "scala" && function.name != "java" =>
          "generateFiles"
        case _ => "generate"
      }
      val result = resultName(function)
      val load =
        s"""val loaded = SchemaView.loadSchemaViewFromUri("$generatorInput")
           |given SchemaView = SchemaIssues.orThrow(loaded)""".stripMargin
      val call = Code.call(s"val $result = $className().$method(", options.toSeq, ")", "  ")
      Snippet(Sdk.Scala, imports, s"$load\n$call", result)
    }
  }

  private def scalaValue(f: OptionField, value: String, schema: OptionsSchema): String = {
    val v = f.valueType match {
      case "Boolean" | "Int" => value
      case "String" => Code.quoted(value)
      case enumName =>
        schema.enums.find(schema.enumName(_) == enumName)
          .flatMap(ev => schema.enumCases(ev).find(_.name == value))
          .fold(sys.error(s"Option ${f.name}: no value $value in $enumName"))(_.scalaCase)
    }
    if f.required then v else s"Some($v)"
  }

  private def c(function: ConverterFunction, examples: Examples): Snippet = {
    val json =
      if examples.isEmpty then "NULL"
      else
        Code.quoted(
          examples.map((f, value) => s"\"${f.name}\": ${jsonValue(f, value)}").mkString(
            "{",
            ", ",
            "}",
          ),
        )
    if function.isImporter then {
      val body =
        s"""/* document is the text of ${input(function)}. */
           |/* The result is an ImportResult in JSON. */
           |linkml_init_threads();
           |char *error = NULL;
           |${Code.call(
            s"char *result = ${function.symbol}(",
            Seq("document", json, "&error"),
            ");",
            "    ",
            trailing = false,
          )}
           |linkml_free(result);
           |linkml_free(error);""".stripMargin
      Snippet(
        Sdk.C,
        Seq("#include <stddef.h>", "#include \"ohi_linkml.h\""),
        body,
        "result",
      )
    } else {
      val result = resultName(function)
      val body =
        s"""linkml_init_threads();
           |char *report = NULL, *error = NULL;
           |long long schema =
           |    linkml_load_file("$generatorInput", NULL, &report, &error);
           |${Code.call(
            s"char *$result = ${function.symbol}(",
            Seq("schema", json, "&error"),
            ");",
            "    ",
            trailing = false,
          )}
           |linkml_free($result);
           |linkml_free(report);
           |linkml_free(error);
           |linkml_close(schema);""".stripMargin
      Snippet(
        Sdk.C,
        Seq("#include <stddef.h>", "#include \"ohi_linkml.h\""),
        body,
        result,
      )
    }
  }

  private def jsonValue(f: OptionField, value: String): String = f.valueType match {
    case "Boolean" | "Int" => value
    case _ => Code.quoted(value)
  }

  /** The variable with a generator's output: text, files, or the statistics. */
  private def resultName(function: ConverterFunction): String = function.returns match {
    case Returns.Document => "output"
    case Returns.Map(_) => "files"
    case _ => "result"
  }
}

object Code {

  /** The longest line of a snippet, which fits the code blocks of the docs. */
  val maxLine = 72

  /** A call, on one line if it fits, or else with one argument per line and trailing commas.
    *
    * @param open
    *   the code up to and including the opening parenthesis
    * @param close
    *   the closing parenthesis and what follows it
    * @param indent
    *   the indentation of each argument on its own line
    * @param margin
    *   the indentation of the whole call
    * @param trailing
    *   whether the last argument on its own line gets a comma, which C does not allow
    */
  def call(
      open: String,
      args: Seq[String],
      close: String,
      indent: String,
      margin: String = "",
      trailing: Boolean = true,
  ): String = {
    val line = margin + open + args.mkString(", ") + close
    if line.length <= maxLine || args.isEmpty then line
    else
      val lines = args.zipWithIndex.map { (a, i) =>
        margin + indent + a + (if i < args.size - 1 || trailing then "," else "")
      }
      (s"$margin$open" +: lines :+ s"$margin$close").mkString("\n")
  }

  /** A double-quoted string literal, which Python, JavaScript, Scala, C and JSON all read. */
  def quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
