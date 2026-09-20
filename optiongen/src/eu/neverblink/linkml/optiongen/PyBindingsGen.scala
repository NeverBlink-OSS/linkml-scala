package eu.neverblink.linkml.optiongen

/** Generates Python methods from the resolved option profiles and entry-point identities. */
object PyBindingsGen {

  def apply(catalog: Catalog): String = {
    val generators = catalog.generators.map(generator => generator.id -> generator).toMap
    val methods = Entrypoints.all.map(entry => render(entry, generators(entry.python)))
    val symbols = Entrypoints.all.map(e => s"""    "${e.symbol}",""").mkString("\n")

    s"""# AUTO-GENERATED from model/generator-options.yaml and the optiongen Entrypoints registry.
       |# Do not edit by hand - regenerate with LINKML_NATIVE=1 ./mill bindings.
       |\"\"\"The generator methods of :class:`linkml_scala.Schema`.
       |
       |Keyword arguments, defaults and documentation come from the Python option profiles.
       |\"\"\"
       |
       |from __future__ import annotations
       |
       |from typing import Any
       |
       |
       |def _pruning(mode: str, tree_root: str | None) -> Any:
       |    \"\"\"Encode the pruning mode the way the generators read it back.
       |
       |    `PruningMode` carries the tree-root override inside its `treeRoot` case rather than
       |    beside it, so naming a root is an object instead of a second field.
       |    \"\"\"
       |    if tree_root is None:
       |        return mode
       |    if mode not in ("treeRoot", "tree_root", "tree-root"):
       |        raise ValueError(f"tree_root only applies to pruning_mode='treeRoot', not {mode!r}")
       |    return {"treeRoot": tree_root}
       |
       |
       |# Every exported function taking (handle, options) and returning a document or NULL.
       |DOCUMENT_FUNCTIONS = (
       |$symbols
       |)
       |
       |
       |class Generators:
       |    \"\"\"Mixin holding one method per generator. Mixed into :class:`linkml_scala.Schema`.\"\"\"
       |
       |${methods.mkString("\n")}""".stripMargin
  }

  private def render(entry: Entrypoints.Entrypoint, generator: GeneratorDef): String = {
    val profile = generator.profiles(Interface.Python)
    val params = profile.parameters.map(parameter => pythonParam(parameter, profile))

    val signature =
      if params.isEmpty then s"    def ${entry.python}(self) -> ${entry.returns}:"
      else {
        val rendered = params.map(p => s"        ${p.name}: ${p.tpe} = ${p.default},")
        s"""    def ${entry.python}(
           |        self,
           |        *,
           |${rendered.mkString("\n")}
           |    ) -> ${entry.returns}:""".stripMargin
      }

    val doc = {
      val result = if entry.structured then " Returns a filename-to-content mapping." else ""
      val summary = docText(generator.description + result).replace("\n", "\n        ")
      val lines =
        params.filter(_.doc.nonEmpty).map(p => docText(wrap(s":param ${p.name}: ${p.doc}")))
      if lines.isEmpty then s"""        \"\"\"$summary\"\"\""""
      else s"""        \"\"\"$summary
           |
           |${lines.mkString("\n")}
           |        \"\"\"""".stripMargin
    }

    val call = params.flatMap(_.argument)
    val method = if entry.structured then "_json" else "_document"
    val body =
      if call.isEmpty then s"""        return self.$method("${entry.symbol}")"""
      else s"""        return self.$method(
           |            "${entry.symbol}",
           |${call.map(a => s"            $a,").mkString("\n")}
           |        )""".stripMargin

    s"$signature\n$doc\n$body\n"
  }

  /** A Python keyword argument from a resolved profile parameter.
    *
    * @param argument
    *   how to pass it on, or None when it is folded into another field's argument
    */
  private case class PyParam(
      name: String,
      tpe: String,
      default: String,
      doc: String,
      argument: Option[String],
  )

  private def pythonParam(parameter: Parameter, profile: Profile): PyParam = {
    def component(kind: PruningComponent): Parameter =
      profile.parameters.find(_.selector == Selector(parameter.selector.field, Some(kind)))
        .getOrElse(throw IllegalArgumentException(s"Missing $kind for ${parameter.selector.field}"))

    val argument = parameter.selector.component match {
      case None => Some(s"${parameter.selector.field}=${parameter.name}")
      case Some(PruningComponent.Mode) =>
        val root = component(PruningComponent.TreeRoot)
        Some(s"${parameter.selector.field}=_pruning(${parameter.name}, ${root.name})")
      case Some(PruningComponent.TreeRoot) => None
    }
    val doc = parameter.selector.component match {
      case Some(PruningComponent.TreeRoot) =>
        val mode = component(PruningComponent.Mode)
        s"${parameter.description} Only valid with `${mode.name}=\"treeRoot\"`."
      case _ => parameter.description
    }
    PyParam(
      parameter.name,
      pythonType(parameter.valueType),
      pythonDefault(parameter),
      doc,
      argument,
    )
  }

  private def pythonType(valueType: ValueType): String = valueType match {
    case ValueType.Bool => "bool"
    case ValueType.Int32 => "int"
    case ValueType.Text | ValueType.JsonFormat | ValueType.RdfFormat | ValueType.PruningKind =>
      "str"
    case ValueType.OptionalText => "str | None"
    case ValueType.Pruning =>
      throw IllegalArgumentException("Python pruning must use split selectors")
  }

  private def pythonDefault(parameter: Parameter): String =
    parameter.default.getOrElse(
      throw IllegalArgumentException(s"Missing Python default for ${parameter.name}"),
    ) match {
      case Value.Text(value) => Literals.quote(value)
      case Value.Bool(value) => if value then "True" else "False"
      case Value.Int32(value) => value.toString
      case Value.Absent => "None"
      case Value.Enum("tree_root") if parameter.valueType == ValueType.PruningKind =>
        Literals.quote("treeRoot")
      case Value.Enum(value) => Literals.quote(value)
      case Value.Pruning(_, _) =>
        throw IllegalArgumentException("Python pruning defaults must use split selectors")
    }

  private def docText(value: String): String =
    value.replace("\r\n", "\n").replace('\r', '\n').flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\n"
      case c if Character.isISOControl(c) => f"\\u${c.toInt}%04x"
      case c => c.toString
    }

  /** Wrap a `:param:` line to stay inside the project's line length, indenting continuations the
    * way the hand-written docstrings do.
    */
  private def wrap(line: String): String = {
    val limit = 96
    val words = line.split("\\s+")
    val out = List.newBuilder[String]
    val current = new StringBuilder("        ")
    words.foreach { word =>
      if current.length + 1 + word.length > limit && current.toString.trim.nonEmpty then {
        out += current.toString.stripTrailing()
        current.clear()
        current.append("            ")
      }
      if current.toString.trim.nonEmpty then current.append(' ')
      current.append(word)
    }
    out += current.toString.stripTrailing()
    out.result().mkString("\n")
  }
}
