package millbuild

/** Generates the Python bindings' generator methods from [[Entrypoints]] and each generator's
  * `Options` case class, so the option names, types, defaults and docs can never drift from the
  * Scala they came from.
  *
  * The same idea as [[TsDefsGen]]. Reading the sources is [[OptionsReader]]'s job.
  */
object PyBindingsGen {

  /** @param read
    *   reads a repository-relative path, so the caller decides where the sources come from
    */
  def apply(read: String => String): String = {
    val methods = Entrypoints.all.map(entry => render(entry, read(entry.source)))
    val symbols = Entrypoints.all.map(e => s"""    "${e.symbol}",""").mkString("\n")

    s"""# AUTO-GENERATED from mill-build/src/Entrypoints.scala and the generators' Options case
       |# classes. Do not edit by hand - regenerate with ./mill bindings.
       |\"\"\"The generator methods of :class:`linkml_scala.Schema`.
       |
       |Each one mirrors an ``Options`` case class in the Scala sources, so the keyword arguments,
       |their defaults and their documentation are whatever the generator itself declares.
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

  private def render(entry: Entrypoints.Entrypoint, source: String): String = {
    val params = OptionsReader.fields(source, entry.generator, entry.source).flatMap(pythonParams)

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
      val lines = params.filter(_.doc.nonEmpty).map(p => wrap(s":param ${p.name}: ${p.doc}"))
      if lines.isEmpty then s"""        \"\"\"${entry.summary}\"\"\""""
      else
        s"""        \"\"\"${entry.summary}
           |
           |${lines.mkString("\n")}
           |        \"\"\"""".stripMargin
    }

    val call = params.flatMap(_.argument)
    val method = if entry.structured then "_json" else "_document"
    val body =
      if call.isEmpty then s"""        return self.$method("${entry.symbol}")"""
      else
        s"""        return self.$method(
           |            "${entry.symbol}",
           |${call.map(a => s"            $a,").mkString("\n")}
           |        )""".stripMargin

    s"$signature\n$doc\n$body\n"
  }

  /** A Python keyword argument derived from one `Options` field.
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

  /** Translate one `Options` field into the Python keyword arguments that stand for it.
    *
    * Usually one. `PruningMode` becomes two, because it carries its tree root override inside
    * itself while Python users expect to name the class separately.
    */
  private def pythonParams(field: OptionsReader.Field): Seq[PyParam] = {
    val name = snakeCase(field.name)
    field.tpe match {
      case "PruningMode" =>
        Seq(
          PyParam(
            name,
            "str",
            pruningDefault(field.default),
            field.doc,
            Some(s"${field.name}=_pruning($name, tree_root)"),
          ),
          PyParam(
            "tree_root",
            "str | None",
            "None",
            "prune from this class instead of the schema's own `tree_root`. Only valid with " +
              "`pruning_mode=\"treeRoot\"`.",
            None,
          ),
        )
      case tpe =>
        Seq(
          PyParam(
            name,
            pythonType(tpe),
            pythonDefault(tpe, field.default),
            field.doc,
            Some(s"${field.name}=$name"),
          ),
        )
    }
  }

  private def pythonType(scalaType: String): String = scalaType match {
    case "Boolean" => "bool"
    case "Int" | "Long" => "int"
    case "String" => "str"
    case "Option[String]" => "str | None"
    // LinkMlGenerator.OutputFormat and friends: enums the boundary spells as their name.
    case _ => "str"
  }

  private def pythonDefault(scalaType: String, default: String): String =
    (scalaType, default) match {
      case (_, "true") => "True"
      case (_, "false") => "False"
      case (_, "None") | (_, "") => "None"
      case ("Option[String]", value) => value.stripPrefix("Some(").stripSuffix(")")
      case ("Int" | "Long" | "String", value) => value
      // An enum case referenced bare or qualified, e.g. `yaml` or `OutputFormat.yaml`.
      case (_, value) => "\"" + value.split('.').last + "\""
    }

  /** `PruningMode.treeRoot(None)` and `schemaRoot` are how the enum is written in Scala; the
    * boundary spells the same three modes as plain names.
    */
  private def pruningDefault(default: String): String =
    default.split('.').last.takeWhile(_ != '(') match {
      case "schemaRoot" => "\"schema\""
      case other => "\"" + other + "\""
    }

  private def snakeCase(camel: String): String =
    camel.flatMap(c => if c.isUpper then s"_${c.toLower}" else c.toString)

  /** Wrap a `:param:` line to stay inside the project's line length, indenting continuations the
    * way the hand-written docstrings do.
    */
  private def wrap(line: String): String = {
    val limit = 96
    val words = line.split(' ')
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
