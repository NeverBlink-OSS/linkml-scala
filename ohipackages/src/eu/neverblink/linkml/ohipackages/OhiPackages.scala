package eu.neverblink.linkml.ohipackages

import eu.neverblink.linkml.schemaview.SchemaCodec
import org.virtuslab.yaml.{Node, parseYaml}

/** Builds the ohi-index packages of the LinkML schemas in a folder: one package per schema file.
  *
  * Each package is a folder `ontologies/ohi/<name>/<version>/` with the schema file, the license, a
  * README and an `ohi-package.yaml`, as ohi-index expects (see `IndexManifest` in
  * `model/ohi/index-manifest.yml`). A relative import of another schema of the folder becomes a
  * dependency on that schema's package, and the import is rewritten to the URL the hub publishes
  * that package's file at.
  */
object OhiPackages {

  val Namespace = "ohi"

  /** Where the hub serves the files of a published package:
    * `<FilesBase>/<namespace>/<name>/<version>/<file>`.
    */
  val FilesBase = "https://api.ohi.dev/files"

  val Publisher = "NeverBlink"

  val License = "Apache-2.0"

  val SourceRepo = "https://github.com/NeverBlink-OSS/ohi"

  /** Folders of `model/` that hold no schemas of ours: a copy of W3C PROV-O used as converter test
    * input, partly in Ossie and OWL.
    */
  val Skipped: Seq[os.SubPath] = Seq(os.sub / "converter" / "examples")

  /** One schema file and what the package needs from it.
    *
    * @param file
    *   the file, relative to the model folder
    * @param imports
    *   the `imports` entries as written in the file
    */
  final case class Schema(
      file: os.SubPath,
      text: String,
      name: String,
      title: String,
      description: String,
      keywords: Seq[String],
      imports: Seq[String],
  )

  /** A package that was written.
    *
    * @param dependencies
    *   the packages it depends on, as `namespace/name@version`
    */
  final case class Package(schema: Schema, folder: os.Path, dependencies: Seq[String])

  def main(args: Array[String]): Unit = args match {
    case Array(root, out, version, commit) =>
      val rootPath = os.Path(root)
      val packages = write(
        model = rootPath / "model",
        license = rootPath / "LICENSE",
        out = os.Path(out),
        version = version,
        commit = commit,
      )
      packages.foreach(p =>
        println(s"$Namespace/${p.schema.name}@$version  model/${p.schema.file}"),
      )
    case _ =>
      System.err.println("Usage: OhiPackages <repo root> <output folder> <version> <commit>")
      sys.exit(2)
  }

  /** Write a package for each schema file under `model` to `out/ontologies/ohi/`.
    *
    * @param license
    *   the license text file, copied into each package as `LICENSE`
    * @param commit
    *   the commit of the source repository the schemas were read at, for the README
    */
  def write(
      model: os.Path,
      license: os.Path,
      out: os.Path,
      version: String,
      commit: String,
      skipped: Seq[os.SubPath] = Skipped,
  ): Seq[Package] = {
    val schemas = read(model, skipped)
    val byFile = schemas.map(s => s.file -> s).toMap
    schemas.map { schema =>
      val local = schema.imports.flatMap(i => resolve(schema.file, i, byFile).map(i -> _))
      val dependencies = local.map((_, dep) => s"$Namespace/${dep.name}@$version").distinct
      val urls = local.map((i, dep) => i -> fileUrl(dep, version)).toMap
      val folder = out / "ontologies" / Namespace / schema.name / version
      os.makeDir.all(folder)
      os.write(folder / schema.file.last, rewriteImports(schema.text, urls, schema.file))
      os.copy(license, folder / "LICENSE")
      os.write(folder / "README.md", readme(schema, version, commit))
      os.write(folder / "ohi-package.yaml", manifest(schema, version, dependencies))
      Package(schema, folder, dependencies)
    }
  }

  /** Read the schema files under `model`, sorted by path. Fails if two of them would get the same
    * package name.
    */
  def read(model: os.Path, skipped: Seq[os.SubPath] = Skipped): Seq[Schema] = {
    val files = os
      .walk(model, skip = p => skipped.exists(s => p == model / s))
      .filter(p => os.isFile(p) && (p.ext == "yaml" || p.ext == "yml"))
      .map(_.subRelativeTo(model))
      .sortBy(_.toString)
    val schemas = files.map(file => parse(file, os.read(model / file)))
    schemas.groupBy(_.name).foreach { (name, same) =>
      if (same.size > 1)
        throw new IllegalArgumentException(
          s"Schemas ${same.map(_.file).mkString(", ")} would all be the package $name",
        )
    }
    schemas
  }

  def parse(file: os.SubPath, text: String): Schema = {
    val node = parseYaml(text) match {
      case Right(node) => node
      case Left(err) => throw new IllegalArgumentException(s"$file: not valid YAML: ${err.msg}")
    }
    val schema = SchemaCodec.decode(node)
    val title = schema.title.map(t => oneLine(t.plain)).filter(_.nonEmpty).getOrElse(schema.name)
    Schema(
      file = file,
      text = text,
      name = schema.name.replace('_', '-'),
      title = title,
      description =
        schema.description.map(d => oneLine(d.plain)).filter(_.nonEmpty).getOrElse(title),
      keywords = schema.keywords.map(k => oneLine(k.plain)).filter(_.nonEmpty) match {
        case Seq() => Seq("linkml")
        case keywords => keywords.distinct
      },
      imports = rawImports(node),
    )
  }

  /** The `imports` entries as written, before any prefix expands them. */
  private def rawImports(node: Node): Seq[String] = node match {
    case Node.MappingNode(mappings, _) =>
      mappings.collectFirst { case (Node.ScalarNode("imports", _), value) => value } match {
        case Some(Node.SequenceNode(entries, _)) =>
          entries.collect { case Node.ScalarNode(value, _) =>
            value
          }
        case _ => Seq()
      }
    case _ => Seq()
  }

  /** The schema of the folder that an import of `from` points to, if it is a relative import. An
    * import with a `:`, such as `linkml:types` or a URL, is not one of the folder's schemas.
    */
  def resolve(
      from: os.SubPath,
      entry: String,
      byFile: Map[os.SubPath, Schema],
  ): Option[Schema] =
    if (entry.contains(':')) None
    else {
      val dir = os.sub / from.segments.dropRight(1)
      val target =
        try dir / os.RelPath(entry)
        catch {
          case e: IllegalArgumentException =>
            throw new IllegalArgumentException(s"$from: import $entry leaves the model folder", e)
        }
      val candidates =
        if (target.ext == "yaml" || target.ext == "yml") Seq(target)
        else Seq(target / os.up / s"${target.last}.yaml", target / os.up / s"${target.last}.yml")
      candidates.collectFirst(byFile) match {
        case found @ Some(_) => found
        case None =>
          throw new IllegalArgumentException(s"$from: import $entry is no schema of the folder")
      }
    }

  def fileUrl(schema: Schema, version: String): String =
    s"$FilesBase/$Namespace/${schema.name}/$version/${schema.file.last}"

  private val ImportsKey = """imports:\s*(#.*)?""".r
  private val Entry = """(\s*-\s+)(["']?)(.*?)\2(\s+#.*)?""".r

  /** Replace the `imports` entries that are keys of `urls` by their URL, leaving the rest of the
    * text as it is. Only a block list of imports is supported, which is what the schemas use.
    */
  def rewriteImports(text: String, urls: Map[String, String], file: os.SubPath): String =
    if (urls.isEmpty) text
    else {
      val lines = text.split("\n", -1).toVector
      val start = lines.indexWhere(l => ImportsKey.matches(l.stripTrailing()))
      if (start < 0)
        throw new IllegalArgumentException(s"$file: imports is not a block list at the top level")
      var replaced = Set.empty[String]
      val rewritten = lines.zipWithIndex.map { (line, i) =>
        lazy val inList = i > start && lines.slice(start + 1, i).forall(l =>
          l.trim.isEmpty || l.trim.startsWith("#") || l.startsWith(" ") || l.startsWith("-"),
        )
        line.stripTrailing() match {
          case Entry(dash, _, value, comment) if inList && urls.contains(value) =>
            replaced += value
            s"$dash${urls(value)}${Option(comment).getOrElse("")}"
          case _ => line
        }
      }
      val missed = urls.keySet -- replaced
      if (missed.nonEmpty)
        throw new IllegalArgumentException(
          s"$file: could not rewrite the imports ${missed.mkString(", ")}",
        )
      rewritten.mkString("\n")
    }

  def manifest(schema: Schema, version: String, dependencies: Seq[String]): String = {
    def list(values: Seq[String]) = values.map(quote).mkString("[", ", ", "]")
    s"""namespace: $Namespace
       |name: ${schema.name}
       |version: ${quote(version)}
       |language: linkml
       |syntax: yaml
       |roots: ${list(Seq(schema.file.last))}
       |licenses: ${list(Seq(License))}
       |licenseFiles: [LICENSE]
       |dependencies: ${list(dependencies)}
       |title: ${quote(schema.title)}
       |publisher: ${quote(Publisher)}
       |description: ${quote(schema.description)}
       |keywords: ${list(schema.keywords)}
       |""".stripMargin
  }

  def readme(schema: Schema, version: String, commit: String): String =
    s"""# ${schema.title}
       |
       |${schema.description}
       |
       |Source: [`model/${schema.file}`]($SourceRepo/blob/$commit/model/${schema.file}) of
       |linkml-scala, release $version (commit `$commit`). Its imports of other schemas of
       |`model/` point to their packages in the `$Namespace` namespace.
       |
       |License: $License, see [LICENSE](LICENSE).
       |""".stripMargin

  /** A YAML double-quoted string. */
  def quote(s: String): String = {
    val sb = new StringBuilder("\"")
    s.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\t' => sb.append("\\t")
      case c if c < ' ' => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"').toString
  }

  private def oneLine(s: String): String = s.trim.replaceAll("""\s+""", " ")
}
