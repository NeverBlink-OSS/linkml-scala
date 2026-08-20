package millbuild

import _root_.scala.meta.*

/** Generates the Python bindings' generator methods from each generator's `Options` case class, so
  * the option names, types, defaults and docs can never drift from the Scala they came from.
  *
  * The same idea as [[TsDefsGen]].
  */
object PyBindingsGen {

  /** One Python method: the exported symbol it calls, and the generator whose `Options` describes
    * its keyword arguments.
    *
    * @param python
    *   Python method name
    * @param symbol
    *   the exported C function
    * @param generator
    *   the generator object holding the `Options` case class
    * @param returns
    *   Python return annotation
    * @param summary
    *   first line of the docstring
    */
  private case class Binding(
      python: String,
      symbol: String,
      generator: String,
      returns: String,
      summary: String,
  ) {

    def source: String = {
      val pkg = generator.stripSuffix("Generator").toLowerCase
      s"generator/src/eu/neverblink/linkml/generator/$pkg/$generator.scala"
    }

    /** Whether the result is JSON to be parsed rather than a document. */
    def structured: Boolean = returns != "str"
  }

  private val bindings = Seq(
    Binding(
      "json_schema",
      "linkml_json_schema",
      "JsonSchemaGenerator",
      "str",
      "Generate a JSON Schema.",
    ),
    Binding(
      "shacl",
      "linkml_shacl",
      "ShaclGenerator",
      "str",
      "Generate SHACL shapes, serialized as N-Triples.",
    ),
    Binding(
      "rdfs",
      "linkml_rdfs",
      "RdfsGenerator",
      "str",
      "Generate RDFS, serialized as N-Triples.",
    ),
    Binding(
      "linkml",
      "linkml_linkml",
      "LinkMlGenerator",
      "str",
      "Materialize a derived LinkML schema: imports resolved, slots pushed into attributes.",
    ),
    Binding(
      "table_schema",
      "linkml_table_schema",
      "TableSchemaGenerator",
      "str",
      "Generate a Frictionless Table Schema, serialized as JSON.",
    ),
    Binding(
      "graphql",
      "linkml_graphql",
      "GraphQlGenerator",
      "str",
      "Generate a GraphQL schema: types, interfaces, scalars and enums, but no queries.",
    ),
    Binding(
      "er_diagram",
      "linkml_er_diagram",
      "ErDiagramGenerator",
      "str",
      "Generate a Mermaid entity relationship diagram.",
    ),
    Binding(
      "scala",
      "linkml_scala",
      "ScalaGenerator",
      "dict[str, str]",
      "Generate Scala classes, as a filename to source mapping.",
    ),
  )

  /** @param read
    *   reads a repository-relative path, so the caller decides where the sources come from
    */
  def apply(read: String => String): String = {
    val methods = bindings.map(binding => render(binding, read(binding.source)))
    val symbols = bindings.map(b => s"""    "${b.symbol}",""").mkString("\n")

    s"""# AUTO-GENERATED from the generators' Options case classes.
       |# Do not edit by hand - regenerate with ./mill pyBindings.
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

  private def render(binding: Binding, source: String): String = {
    val options = findOptions(source, binding.generator)
      .getOrElse(sys.error(s"no Options case class found in ${binding.source}"))
    val docs = paramDocs(source, options)
    val params = options.ctor.paramClauses.flatMap(_.values)
      .flatMap(param => pythonParams(param, docs))

    val signature =
      if params.isEmpty then s"    def ${binding.python}(self) -> ${binding.returns}:"
      else {
        val rendered = params.map(p => s"        ${p.name}: ${p.tpe} = ${p.default},")
        s"""    def ${binding.python}(
           |        self,
           |        *,
           |${rendered.mkString("\n")}
           |    ) -> ${binding.returns}:""".stripMargin
      }

    val doc = {
      val lines = params.filter(_.doc.nonEmpty).map(p => wrap(s":param ${p.name}: ${p.doc}"))
      if lines.isEmpty then s"""        \"\"\"${binding.summary}\"\"\""""
      else s"""        \"\"\"${binding.summary}
           |
           |${lines.mkString("\n")}
           |        \"\"\"""".stripMargin
    }

    val call = params.flatMap(_.argument)
    val method = if binding.structured then "_json" else "_document"
    val body =
      if call.isEmpty then s"""        return self.$method("${binding.symbol}")"""
      else s"""        return self.$method(
           |            "${binding.symbol}",
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
  private def pythonParams(param: Term.Param, docs: Map[String, String]): Seq[PyParam] = {
    val scalaName = param.name.value.stripPrefix("`").stripSuffix("`")
    val name = snakeCase(scalaName)
    val tpe = param.decltpe.map(_.syntax).getOrElse("Any")
    val default = param.default.map(_.syntax).getOrElse("None")
    val doc = docs.getOrElse(scalaName, "")

    tpe match {
      case "PruningMode" =>
        Seq(
          PyParam(
            name,
            "str",
            pruningDefault(default),
            doc,
            Some(s"$scalaName=_pruning($name, tree_root)"),
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
      case _ =>
        Seq(
          PyParam(
            name,
            pythonType(tpe),
            pythonDefault(tpe, default),
            doc,
            Some(s"$scalaName=$name"),
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
      case (_, "None") => "None"
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
      case "treeRoot" => "\"treeRoot\""
      case other => "\"" + other + "\""
    }

  private def snakeCase(camel: String): String =
    camel.flatMap(c => if c.isUpper then s"_${c.toLower}" else c.toString)

  private def findOptions(source: String, generator: String): Option[Defn.Class] = {
    val parsed = dialects.Scala3(source).parse[Source].get
    def inObject(t: Tree): Option[Defn.Class] = t match {
      case o: Defn.Object if o.name.value == generator =>
        collect[Defn.Class](o).find(_.name.value == "Options")
      case _ => t.children.iterator.flatMap(inObject).nextOption()
    }
    inObject(parsed)
  }

  private def collect[T <: Tree](t: Tree)(using tag: reflect.ClassTag[T]): List[T] = {
    val self = t match { case matched: T => List(matched); case _ => Nil }
    self ++ t.children.flatMap(collect[T])
  }

  /** The `@param` entries of the scaladoc directly above the `Options` class. */
  private def paramDocs(source: String, options: Defn.Class): Map[String, String] = {
    val raw = "/\\*\\*[\\s\\S]*?\\*/".r
      .findAllMatchIn(source)
      .filter(_.end <= options.pos.start)
      .toList
      .lastOption
      .map(_.matched)
      .getOrElse("")

    val lines = raw
      .stripPrefix("/**")
      .stripSuffix("*/")
      .linesIterator
      .map(l => l.trim.stripPrefix("*").trim)
      .toList

    val docs = Map.newBuilder[String, String]
    var current = ""
    val text = new StringBuilder
    def flush(): Unit =
      if current.nonEmpty then {
        // Scaladoc wraps `code` in backticks, which reads the same in Python docstrings.
        docs += current -> text.toString.trim
        text.clear()
        current = ""
      }
    lines.foreach { line =>
      if line.startsWith("@param") then {
        flush()
        current = line.stripPrefix("@param").trim
      } else if line.startsWith("@") then flush()
      else if current.nonEmpty && line.nonEmpty then text.append(line).append(" ")
    }
    flush()
    docs.result()
  }

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
