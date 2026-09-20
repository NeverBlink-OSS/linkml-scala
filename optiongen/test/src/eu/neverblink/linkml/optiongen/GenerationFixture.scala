package eu.neverblink.linkml.optiongen

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object GenerationFixture {
  val core: Map[String, String] = Map(
    "json_schema" -> "jsonschema/JsonSchemaGenerator",
    "shacl" -> "shacl/ShaclGenerator",
    "rdfs" -> "rdfs/RdfsGenerator",
    "linkml" -> "linkml/LinkMlGenerator",
    "frictionless" -> "frictionless/FrictionlessGenerator",
    "graphql" -> "graphql/GraphQlGenerator",
    "er_diagram" -> "erdiagram/ErDiagramGenerator",
    "ossie" -> "ossie/OssieGenerator",
    "scala" -> "scala/ScalaGenerator",
    "translation" -> "translation/TranslationGenerator",
  ).view.mapValues(name => s"generator/src/eu/neverblink/linkml/generator/$name.scala").toMap
  val python = "python/linkml_scala/_generated.py"
  val native = "nativelib/src/eu/neverblink/linkml/nativelib/LinkMlCGenerators.scala"
  val js = "generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala"
  val tsOutput = "ui/linkml.d.ts"
  val cli = "cli/src/eu/neverblink/linkml/cli/GenerateImpl.scala"
  val pruning = "cli/src/eu/neverblink/linkml/cli/PruningOptions.scala"
  val reference = "docs/generator-options.md"
  val npm = "generator/npm/README.md"
  val pythonDocs = "docs/python_bindings.md"
  val template = "generator/npm/api.d.ts.template"
  val outputs: Set[String] = core.values.toSet ++ Set(
    python,
    native,
    js,
    tsOutput,
    cli,
    pruning,
    reference,
    npm,
    pythonDocs,
  )
  val scalaSentinel = "// Handwritten fixture sentinel.  Keep spacing.\n"
  val markdownSentinel = "\n<!-- Handwritten fixture sentinel.  Keep spacing. -->\n"
  val tsSentinel = "\n// Non-generator template sentinel.\n"

  def withFixture(f: GenerationFixture => Unit): Unit = {
    val root = Files.createTempDirectory("optiongen-generation-")
    val repo = Path.of(sys.env("OPTION_SCHEMA")).toAbsolutePath.getParent.getParent
    try {
      for relative <- outputs ++ Set(template, ".scalafmt.conf", "model/generator-options.yaml")
      do {
        val destination = root.resolve(relative)
        Files.createDirectories(destination.getParent)
        Files.copy(repo.resolve(relative), destination)
      }
      Files.writeString(root.resolve(cli), scalaSentinel + Files.readString(root.resolve(cli)))
      Files.writeString(root.resolve(npm), Files.readString(root.resolve(npm)) + markdownSentinel)
      Files.writeString(
        root.resolve(template),
        Files.readString(root.resolve(template)) + tsSentinel,
      )
      f(new GenerationFixture(root))
    } finally {
      val paths = Files.walk(root)
      try paths.iterator().asScala.toVector.reverse.foreach(Files.delete(_))
      finally paths.close()
    }
  }

  def replaceOnce(source: String, before: String, after: String): String = {
    val at = source.indexOf(before)
    require(at >= 0 && source.indexOf(before, at + before.length) < 0, s"Nonunique edit: $before")
    source.take(at) + after + source.drop(at + before.length)
  }

  def inClass(text: String, name: String, next: String)(edit: String => String): String = {
    val start = text.indexOf(s"\n  $name:\n")
    val end = text.indexOf(s"\n  $next:\n", start + 1)
    require(start >= 0 && end > start, s"Missing bounded class $name / $next")
    text.take(start) + edit(text.substring(start, end)) + text.drop(end)
  }

  def region(source: String, id: String): String = {
    val lines = source.linesIterator.toVector
    def marker(kind: String, line: String): Boolean =
      Set(s"// $kind GENERATED OPTIONS $id", s"<!-- $kind GENERATED OPTIONS $id -->")(line.trim)
    val start = lines.indexWhere(marker("BEGIN", _))
    val end = lines.indexWhere(marker("END", _), start + 1)
    require(start >= 0 && end > start, s"Missing region $id")
    lines.slice(start + 1, end).mkString("\n")
  }
}

final class GenerationFixture(val root: Path) {
  import GenerationFixture.*

  val schema: Path = root.resolve("model/generator-options.yaml")
  val bundle: Path = root.resolve("bundle")
  def schemaText: String = Files.readString(schema)
  def updateSchema(text: String): Unit = { Files.writeString(schema, text); () }
  def render(group: String = "all"): Unit =
    Main.run(
      Vector(
        "render",
        "--root",
        root.toString,
        "--schema",
        schema.toString,
        "--config",
        root.resolve(".scalafmt.conf").toString,
        "--output",
        bundle.toString,
        "--group",
        group,
      ),
    )
  def command(name: String): Unit =
    Main.run(Vector(name, "--root", root.toString, "--bundle", bundle.toString))
  def rendered(relative: String): String =
    Files.readString(bundle.resolve("files").resolve(relative))
  def stagedFiles: Map[String, String] = {
    val base = bundle.resolve("files")
    val paths = Files.walk(base)
    try
      paths.iterator().asScala.filter(Files.isRegularFile(_)).map { path =>
        base.relativize(path).iterator().asScala.mkString("/") -> Files.readString(path)
      }.toMap
    finally paths.close()
  }
  def destinations: Map[String, Option[String]] = outputs.map { relative =>
    relative -> Option.when(Files.exists(root.resolve(relative)))(
      Files.readString(root.resolve(relative)),
    )
  }.toMap
  def ts(): String = {
    val output = root.resolve("standalone-index.d.ts")
    Main.run(
      Vector(
        "ts",
        "--root",
        root.toString,
        "--schema",
        schema.toString,
        "--template",
        root.resolve(template).toString,
        "--output",
        output.toString,
      ),
    )
    Files.readString(output)
  }
}
