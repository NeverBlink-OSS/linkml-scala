package eu.neverblink.linkml.optiongen

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object Main {
  private val pythonOutput = "python/linkml_scala/_generated.py"
  private val nativeOutput = "nativelib/src/eu/neverblink/linkml/nativelib/LinkMlCGenerators.scala"
  private val uiOutput = "ui/linkml.d.ts"
  private val tsTemplate = "generator/npm/api.d.ts.template"
  private def registeredOutputs: Set[String] =
    Entrypoints.all.map(_.source).toSet ++ Set(
      pythonOutput,
      nativeOutput,
      JsGen.host,
      uiOutput,
      CliGen.host,
      CliGen.pruningHost,
      DocsGen.referencePath,
      DocsGen.npmHost,
      DocsGen.pythonHost,
    )

  private def loadCatalog(path: Path): Catalog = {
    val catalog = OptionSchemaReader.load(path.toString, Entrypoints.all.map(_.python).toSet)
      .fold(errors => throw IllegalArgumentException(errors.mkString("\n")), identity)
    catalog.warnings.foreach(w => System.err.println(s"warning: $w"))
    catalog
  }

  def main(args: Array[String]): Unit =
    try run(args.toVector)
    catch {
      case NonFatal(error) =>
        System.err.println(failureMessage(error))
        sys.exit(1)
    }

  private[optiongen] def failureMessage(error: Throwable): String = {
    val message = new StringBuilder(
      Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName),
    )
    var seen = Set(error)
    var cause = error.getCause
    while cause != null && !seen(cause) do {
      message.append("\nCaused by: ").append(cause.toString)
      seen += cause
      cause = cause.getCause
    }
    message.toString
  }

  private[optiongen] def run(args: Vector[String]): Unit = {
    require(args.nonEmpty, "Expected render, ts, check, or apply")
    val pairs = args.tail.grouped(2).toVector
    require(
      pairs.forall(p => p.size == 2 && p.head.startsWith("--")),
      "Expected --name value arguments",
    )
    require(pairs.map(_.head).distinct.size == pairs.size, "Duplicate arguments")
    val options = pairs.map(p => p.head -> p(1)).toMap
    val allowed = args.head match {
      case "render" => Set("--root", "--schema", "--config", "--output", "--region", "--group")
      case "ts" => Set("--root", "--schema", "--template", "--output")
      case "check" | "apply" => Set("--root", "--bundle")
      case other => throw IllegalArgumentException(s"Unknown command: $other")
    }
    require(
      options.keySet.subsetOf(allowed),
      "Unknown arguments: " + (options.keySet -- allowed).mkString(", "),
    )
    def path(key: String): Path = Path.of(
      options.getOrElse(key, throw IllegalArgumentException(s"Missing $key")),
    ).toAbsolutePath.normalize()
    val root = path("--root")
    args.head match {
      case "render" =>
        val catalog = loadCatalog(path("--schema"))
        val selection = options.get("--region")
        val group = options.getOrElse("--group", "all")
        require(
          Set("all", "core", "bindings", "js", "cli", "docs")(group),
          s"Unknown output group: $group",
        )
        require(
          selection.isEmpty || Set("all", "core")(group),
          "Region selection requires core outputs",
        )
        val groups = if selection.nonEmpty then Set("core")
        else if group == "all" then Set("core", "bindings", "js", "cli", "docs")
        else Set(group)
        require(
          selection.forall(id => Entrypoints.all.exists(e => s"core.${e.python}" == id)),
          "Unknown core region",
        )
        val files = ScalaFormatter.withFormatter(path("--config")) { format =>
          val core = Entrypoints.all.filter(e =>
            groups("core") && selection.forall(_ == s"core.${e.python}"),
          ).map { entry =>
            val id = s"core.${entry.python}"
            val generator = catalog.generators.find(_.id == entry.python).get
            RegionWriter.prepare(
              root.resolve(entry.source),
              Map(id -> CoreGen.render(entry, generator)),
              Set(id),
              format,
            )
          }.toVector
          val bindings =
            if !groups("bindings") then Vector.empty
            else
              Vector(
                RegionWriter.complete(root.resolve(pythonOutput), PyBindingsGen(catalog)),
                RegionWriter.complete(
                  root.resolve(nativeOutput),
                  format(root.resolve(nativeOutput), CApiGen(catalog)),
                ),
              )
          val js = if !groups("js") then Vector.empty
          else {
            val regions = JsGen.regions(catalog)
            Vector(
              RegionWriter.prepare(root.resolve(JsGen.host), regions, regions.keySet, format),
              RegionWriter.complete(
                root.resolve(uiOutput),
                TsDefsGen(catalog, Files.readString(root.resolve(tsTemplate))),
              ),
            )
          }
          val cli = if !groups("cli") then Vector.empty
          else {
            Vector(
              CliGen.host -> CliGen.regions(catalog),
              CliGen.pruningHost -> CliGen.pruningRegions(catalog),
            )
              .map { (host, regions) =>
                RegionWriter.prepare(root.resolve(host), regions, regions.keySet, format)
              }
          }
          val docs = if !groups("docs") then Vector.empty
          else {
            val guides = DocsGen.regions(catalog).toVector.sortBy(_._1).map { (host, regions) =>
              RegionWriter.prepare(
                root.resolve(host),
                regions,
                regions.keySet,
                (_, content) => content,
              )
            }
            guides :+ RegionWriter.complete(
              root.resolve(DocsGen.referencePath),
              DocsGen.reference(catalog),
            )
          }
          core ++ bindings ++ js ++ cli ++ docs
        }
        writeBundle(root, path("--output"), files)
      case "ts" =>
        val content = TsDefsGen(loadCatalog(path("--schema")), Files.readString(path("--template")))
        val output = path("--output")
        Files.createDirectories(output.getParent)
        Files.writeString(output, content)
      case "check" =>
        val stale = RegionWriter.stale(readBundle(root, path("--bundle")))
        require(
          stale.isEmpty,
          "Generated option files are stale:\n" + stale.map(root.relativize).mkString("\n"),
        )
        println("Generated option files are current.")
      case "apply" =>
        RegionWriter.publish(readBundle(root, path("--bundle"))).foreach(p =>
          println(s"Updated ${root.relativize(p)}"),
        )
      case _ => throw IllegalArgumentException("Unknown command")
    }
  }

  private[optiongen] def writeBundle(
      root: Path,
      bundle: Path,
      files: Vector[RegionWriter.PlannedFile],
  ): Unit = {
    val paths = files.map(file => root.relativize(file.path).iterator().asScala.mkString("/"))
    require(paths.nonEmpty && paths.distinct.size == paths.size, "Invalid bundle selection")
    require(paths.forall(registeredOutputs), "Unregistered bundle destination")
    val entries = files.zip(paths).map { (file, relative) =>
      val staged = bundle.resolve("files").resolve(relative)
      Files.createDirectories(staged.getParent)
      Files.writeString(staged, file.content)
      s"${file.before.getOrElse("-")}\t${RegionWriter.digest(file.content)}\t$relative"
    }
    val manifest = (("paths" +: paths).mkString("\t") +: entries).mkString("", "\n", "\n")
    Files.writeString(bundle.resolve("manifest.tsv"), manifest)
  }

  private def readBundle(root: Path, bundle: Path): Vector[RegionWriter.PlannedFile] = {
    val registered = registeredOutputs
    val lines = Files.readAllLines(bundle.resolve("manifest.tsv")).asScala.toVector
    val header = lines.headOption.toVector.flatMap(_.split("\t", -1))
    require(header.headOption.contains("paths") && header.size > 1, "Missing bundle selection")
    val selected = header.tail
    require(
      selected.distinct.size == selected.size && selected.forall(registered),
      "Invalid bundle selection",
    )
    val files = lines.tail.map { line =>
      val parts = line.split("\t", -1)
      require(parts.length == 3 && registered(parts(2)), s"Invalid bundle entry: $line")
      val before = Option.when(parts(0) != "-")(parts(0))
      require(before.forall(_.matches("[a-f0-9]{64}")), s"Invalid bundle source digest: $line")
      val content = Files.readString(bundle.resolve("files").resolve(parts(2)))
      require(RegionWriter.digest(content) == parts(1), s"Modified bundle output: ${parts(2)}")
      parts(2) -> RegionWriter.PlannedFile(root.resolve(parts(2)), before, content)
    }
    val paths = files.map(_._1)
    require(
      paths.distinct.size == paths.size && paths.toSet == selected.toSet,
      "Incomplete or duplicate bundle outputs",
    )
    files.map(_._2)
  }
}
