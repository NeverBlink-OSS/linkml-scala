package eu.neverblink.linkml.optiongen

object DocsGen {
  val referencePath: String = "docs/generator-options.md"
  val npmHost: String = "generator/npm/README.md"
  val pythonHost: String = "docs/python_bindings.md"

  def regions(catalog: Catalog): Map[String, Map[String, String]] = Map(
    npmHost -> Map("docs.npm.generators" -> npmTable(catalog)),
    pythonHost -> Map("docs.python.generators" -> pythonExamples(catalog)),
  )

  def reference(catalog: Catalog): String = {
    val sections = entries(catalog).map { case (entry, generator) =>
      val js = generator.profiles(Interface.Js).parameters.map { parameter =>
        parameter.tsName.getOrElse(parameter.name) + parameter.default.fold("") { value =>
          s" = ${boundaryValue(value, Interface.Js)}"
        }
      }
      val python = generator.profiles(Interface.Python).parameters.map { parameter =>
        s"${parameter.name}=${boundaryDefault(parameter, Interface.Python)}"
      }
      val rows = generator.fields.sortBy(_.rank).map { field =>
        val native = generator.profiles(Interface.Native).parameters
          .find(_.selector.field == field.name).get
        require(native.name == field.name, s"Native name differs from core field ${field.name}")
        val cells = Vector(
          code(field.name) + "<br>" + prose(field.description),
          code(scalaType(field.valueType)),
          code(coreValue(field.valueType, field.default)),
          exposure(generator, field, Interface.Cli),
          exposure(generator, field, Interface.Js),
          exposure(generator, field, Interface.Python),
        )
        s"| ${cells.mkString(" | ")} |"
      }.mkString("\n")
      val result = if entry.structured then "a filename-to-content mapping" else "a string"
      s"""## ${entry.generator}
         |
         |${prose(generator.description)} Returns $result. Native entry point ${code(entry.symbol)}.
         |
         |JavaScript positional signature ${code(
          s"LinkML.${entry.jsMethod}(${(Vector("schema") ++ js).mkString(", ")})",
        )}.
         |
         |Python keyword signature ${code(s"schema.${entry.python}(*, ${python.mkString(", ")})")}.
         |
         || Core / native field | Scala type | Core default | CLI flags and defaults | JavaScript parameters and defaults | Python keywords and defaults |
         || --- | --- | --- | --- | --- | --- |
         |$rows
         |""".stripMargin
    }.mkString("\n")
    s"""# Generator options
       |
       |<!-- Generated from model/generator-options.yaml. Regenerate with ./mill --no-server optiongen.regenerate. -->
       |
       |Each generator retains its nested Scala `Options` case class. Native JSON uses the same field
       |names and defaults. Omit a JSON field to use its core default. Python arguments are keyword-only.
       |JavaScript arguments are positional. CLI flags appear below without common input/output flags.
       |
       |Each exposed parameter shows its interface default. **Bold defaults differ from the core
       |constructor.** `required` means the caller must supply the argument. `Unexposed` means that
       |interface uses the core default without exposing a parameter.
       |
       |${semantics(catalog)}
       |$sections""".stripMargin
  }

  private def entries(catalog: Catalog): Vector[(Entrypoints.Entrypoint, GeneratorDef)] = {
    val generators = catalog.generators.map(generator => generator.id -> generator).toMap
    Entrypoints.all.map(entry => entry -> generators(entry.python))
  }

  private def npmTable(catalog: Catalog): String = {
    val rows = entries(catalog).map { case (entry, generator) =>
      val parameters = generator.profiles(Interface.Js).parameters.map { parameter =>
        val optional =
          if parameter.default.nonEmpty || parameter.valueType == ValueType.OptionalText
          then "?"
          else ""
        parameter.tsName.getOrElse(parameter.name) + optional
      }
      val call = s"${entry.jsMethod}(${(Vector("view") ++ parameters).mkString(", ")})"
      val result = if entry.structured then "Record<string, string>" else "string"
      s"| ${code(call)} | ${code(result)} | ${prose(generator.description)} |"
    }.mkString("\n")
    s"""
       || Function | Returns | Notes |
       || --- | --- | --- |
       |$rows
       |
       |See the [generator option reference](https://github.com/NeverBlink-OSS/linkml-scala/blob/main/docs/generator-options.md)
       |for defaults, CLI flags, Python keywords, and native JSON fields.
       |
       |""".stripMargin
  }

  private def pythonExamples(catalog: Catalog): String = {
    val examples = entries(catalog).map { case (entry, generator) =>
      val parameters = generator.profiles(Interface.Python).parameters.map { parameter =>
        s"${parameter.name}=${boundaryDefault(parameter, Interface.Python)}"
      }
      val call = s"schema.${entry.python}(${parameters.mkString(", ")})"
      if call.length <= 96 then call
      else s"schema.${entry.python}(\n${parameters.map(p => s"    $p,").mkString("\n")}\n)"
    }.mkString("\n")
    val structured = Entrypoints.all.filter(_.structured).map(entry => code(s"${entry.python}()"))
    s"""
       |```python
       |$examples
       |```
       |
       |All arguments are keyword-only. ${structured.mkString(" and ")} return filename-to-content
       |dicts. Other generators return strings.
       |
       |See the [generator option reference](generator-options.md) for every option, interface default,
       |format alias, and pruning mode.
       |
       |""".stripMargin
  }

  private def exposure(generator: GeneratorDef, field: FieldDef, interface: Interface): String = {
    val parameters = generator.profiles(interface).parameters.filter(_.selector.field == field.name)
      .flatMap { parameter =>
        if parameter.adapter.contains(Adapter.PruningOptions) then {
          Vector(
            ("pruningMode", PruningComponent.Mode, ValueType.PruningKind),
            ("treeRoot", PruningComponent.TreeRoot, ValueType.OptionalText),
          ).map { case (name, component, valueType) =>
            parameter.copy(
              selector = Selector(field.name, Some(component)),
              name = name,
              valueType = valueType,
              default = parameter.default.map(project(_, Some(component))),
            )
          }
        } else Vector(parameter)
      }
    if parameters.isEmpty then "Unexposed"
    else
      parameters.map { parameter =>
        val name = interface match {
          case Interface.Cli =>
            "--" + parameter.name.flatMap { char =>
              if char.isUpper then s"-${char.toLower}" else char.toString
            }
          case Interface.Js => parameter.tsName.getOrElse(parameter.name)
          case _ => parameter.name
        }
        val inherited = project(field.default, parameter.selector.component)
        val default = code(boundaryDefault(parameter, interface))
        val displayed = if parameter.default.contains(inherited) then default else s"**$default**"
        s"${code(name)} = $displayed"
      }.mkString("<br>")
  }

  private def project(value: Value, component: Option[PruningComponent]): Value =
    (value, component) match {
      case (Value.Pruning(mode, _), Some(PruningComponent.Mode)) => Value.Enum(pruningName(mode))
      case (Value.Pruning(_, root), Some(PruningComponent.TreeRoot)) =>
        root.fold(Value.Absent)(Value.Text(_))
      case (_, None) => value
      case _ =>
        throw IllegalArgumentException(
          s"Unsupported documentation projection $component for $value",
        )
    }

  private def boundaryDefault(parameter: Parameter, interface: Interface): String =
    parameter.default.fold("required")(boundaryValue(_, interface))

  private def boundaryValue(value: Value, interface: Interface): String = value match {
    case Value.Text(text) => Literals.quote(text)
    case Value.Bool(boolean) =>
      if interface == Interface.Python then (if boolean then "True" else "False")
      else boolean.toString
    case Value.Int32(number) => number.toString
    case Value.Absent =>
      interface match {
        case Interface.Python => "None"
        case Interface.Js => "undefined"
        case Interface.Cli => "not set"
        case Interface.Native => "null"
      }
    case Value.Enum(name) => Literals.quote(if name == "tree_root" then "treeRoot" else name)
    case Value.Pruning(_, _) =>
      throw IllegalArgumentException("Pruning documentation requires split parameters")
  }

  private def scalaType(valueType: ValueType): String = valueType match {
    case ValueType.Text => "String"
    case ValueType.Bool => "Boolean"
    case ValueType.Int32 => "Int"
    case ValueType.OptionalText => "Option[String]"
    case ValueType.JsonFormat => "JsonOutputFormat"
    case ValueType.RdfFormat => "RdfFormat"
    case ValueType.Pruning => "PruningMode"
    case ValueType.PruningKind =>
      throw IllegalArgumentException("Pruning kind is not a core Scala type")
  }

  private def coreValue(valueType: ValueType, value: Value): String = (valueType, value) match {
    case (ValueType.OptionalText, Value.Text(text)) => s"Some(${Literals.quote(text)})"
    case (ValueType.OptionalText, Value.Absent) => "None"
    case (ValueType.JsonFormat, Value.Enum(name)) => s"JsonOutputFormat.$name"
    case (ValueType.RdfFormat, Value.Enum(name)) => s"RdfFormat.$name"
    case (_, Value.Pruning(PruningKind.Skip, None)) => "PruningMode.skip"
    case (_, Value.Pruning(PruningKind.Schema, None)) => "PruningMode.schemaRoot"
    case (_, Value.Pruning(PruningKind.TreeRoot, root)) =>
      s"PruningMode.treeRoot(${root.fold("None")(text => s"Some(${Literals.quote(text)})")})"
    case _ => boundaryValue(value, Interface.Cli)
  }

  private def semantics(catalog: Catalog): String = {
    val pruning = catalog.bindings.find(_.valueType == ValueType.PruningKind).get
    val rows = pruning.members.map { member =>
      val value = if member.name == "tree_root" then "treeRoot" else member.name
      s"| ${code(member.name)} | ${code(Literals.quote(value))} | ${prose(member.description)} |"
    }.mkString("\n")
    val field = catalog.generators.flatMap(_.fields).find(_.valueType == ValueType.Pruning).get.name
    val rootExample = s"{${Literals.quote(field)}: {\"treeRoot\": \"Person\"}}"
    s"""## Pruning and formats
       |
       || Schema mode | Native JSON value | Meaning |
       || --- | --- | --- |
       |$rows
       |
       |The authoring schema represents pruning as a mode plus an optional root name, for example
       |`{mode: tree_root, treeRoot: Person}`. Native options use a string mode or a single-key object.
       |To override the root, send ${code(rootExample)}. This JSON shape also applies to Python's native calls.
       |
       |Pruning accepts `treeRoot`, `tree_root`, and `tree-root`. Python permits a `tree_root` override
       |only with those spellings and rejects an override for other modes. CLI and JavaScript ignore a
       |root override outside tree-root pruning. Optional root strings are passed without trimming.
       |
       |JSON/YAML formats accept `yaml`, `yml`, and `json`, ignoring case. RDF accepts `ttl`, `turtle`,
       |`nt`, and `ntriples`. CLI and JavaScript ignore RDF case. Native JSON and Python require lowercase RDF names.
       |""".stripMargin
  }

  private def pruningName(mode: PruningKind): String = mode match {
    case PruningKind.Skip => "skip"
    case PruningKind.Schema => "schema"
    case PruningKind.TreeRoot => "tree_root"
  }

  private def prose(value: String): String =
    value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
      .replace("|", "&#124;").replace("\r\n", " ").replace('\r', ' ').replace('\n', ' ')

  private def code(value: String): String = {
    val escaped = value.replace("|", "\\|").replace("\r", "\\r").replace("\n", "\\n")
    val width = "`+".r.findAllIn(escaped).map(_.length).maxOption.getOrElse(0) + 1
    val fence = "`" * width
    s"$fence $escaped $fence"
  }
}
