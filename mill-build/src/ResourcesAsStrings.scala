package millbuild

import mill.javalib.JavaModule
import mill.*

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import scala.collection.mutable

/** Embeds the module's resources into generated Scala sources, so they can be read on every
  * platform, including Scala.js and Scala Native where there are no class path resources.
  *
  * The `module` parameter only picks the package of the generated code,
  * `eu.neverblink.linkml.<module>`. In that package it generates:
  *
  *   - `Resources` with `map` (resource path to text) and `read(path)` for look-ups by a string
  *     path like `/dir/file.txt`, plus a val for each top level file and directory of the
  *     resources;
  *   - one object per resource directory in the `resourcedirs` sub-package, in a file of its own,
  *     with a val for each file and sub-directory.
  *
  * Every object also has a `path` val with the path of its directory, like `/dir/`.
  *
  * So `Resources.dir.`file.txt`` is completed by an IDE, and going to its definition shows the text
  * of the file. Texts are embedded as multi-line string literals when possible, and as escaped
  * string literals otherwise. Only UTF-8 text files are supported.
  */
trait ResourcesAsStrings(module: String) extends JavaModule {
  override def generatedSources = Task {
    val pkg = s"eu.neverblink.linkml.$module"
    val pkgDir = Task.dest / "eu" / "neverblink" / "linkml" / module
    val generated = ResourcesAsStrings.generate(pkg, ResourcesAsStrings.files(resources()))
    generated.map { case (relPath, content) =>
      val dest = pkgDir / relPath
      os.write.over(dest, content, createFolders = true)
      PathRef(dest)
    } ++ super.generatedSources()
  }
}

object ResourcesAsStrings {

  /** The max number of bytes in a string literal, which must fit in a constant of a class file */
  private val maxLiteralBytes = 60000

  private val dirsPkg = "resourcedirs"

  private val keywords = Set(
    "abstract",
    "case",
    "catch",
    "class",
    "def",
    "do",
    "else",
    "enum",
    "export",
    "extends",
    "false",
    "final",
    "finally",
    "for",
    "given",
    "if",
    "implicit",
    "import",
    "lazy",
    "match",
    "new",
    "null",
    "object",
    "override",
    "package",
    "private",
    "protected",
    "return",
    "sealed",
    "super",
    "then",
    "throw",
    "trait",
    "true",
    "try",
    "type",
    "val",
    "var",
    "while",
    "with",
    "yield",
    "_",
  )

  /** Members of every object, which vals for resources can't override */
  private val objectMembers = Set(
    "equals",
    "hashCode",
    "toString",
    "getClass",
    "wait",
    "notify",
    "notifyAll",
    "clone",
    "finalize",
    "asInstanceOf",
    "isInstanceOf",
    "synchronized",
    "eq",
    "ne",
    "##",
    "==",
    "!=",
  )

  /** @return
    *   path segments of all files in the resource roots, relative to their root, with the files,
    *   sorted by the paths
    */
  def files(resources: Seq[PathRef]): Seq[(Seq[String], os.Path)] = {
    val files = mutable.LinkedHashMap.empty[Seq[String], os.Path]
    resources.filter(ref => os.exists(ref.path)).foreach { ref =>
      os.walk(ref.path, followLinks = true).sorted.foreach { path =>
        // A later resource root overrides files with the same path in earlier ones
        if (os.isFile(path)) files(path.relativeTo(ref.path).segments) = path
      }
    }
    files.toSeq.sortBy(_._1.mkString("/"))
  }

  private case class Dir(segments: Seq[String]) {
    val files = mutable.ArrayBuffer.empty[(String, os.Path)]
    val dirs = mutable.ArrayBuffer.empty[Dir]
  }

  /** @return
    *   paths of the generated sources, relative to the directory of the package, with their content
    */
  def generate(pkg: String, files: Seq[(Seq[String], os.Path)]): Seq[(os.SubPath, String)] = {
    val root = Dir(Nil)
    val dirs = mutable.LinkedHashMap[Seq[String], Dir](Nil -> root)
    def dirOf(segments: Seq[String]): Dir = dirs.getOrElseUpdate(
      segments, {
        val dir = Dir(segments)
        dirOf(segments.init).dirs += dir
        dir
      },
    )
    files.foreach { case (segments, path) => dirOf(segments.init).files += segments.last -> path }

    // Unique names for the objects of directories, even on case-insensitive file systems
    val usedNames = mutable.Set.empty[String]
    val objectNames = dirs.keys.collect {
      case segments if segments.nonEmpty =>
        val base = "Dir_" + segments.map(_.replaceAll("[^A-Za-z0-9]", "_")).mkString("_")
        var name = base
        var i = 2
        while (!usedNames.add(name.toLowerCase)) {
          name = s"${base}_$i"
          i += 1
        }
        segments -> name
    }.toMap

    def objectRef(dir: Dir): String =
      s"_root_.$pkg.$dirsPkg.${objectNames(dir.segments)}"

    def members(dir: Dir, reserved: Set[String]): String = {
      val sb = new java.lang.StringBuilder
      val names = dir.files.map(_._1) ++ dir.dirs.map(_.segments.last)
      names.foreach { name =>
        if (name == "path" || reserved(name) || objectMembers(name) || name.contains('`'))
          sys.error(s"Can't generate a val for resource /${(dir.segments :+ name).mkString("/")}")
      }
      sb.append("\n  /** Path of the directory */\n")
      sb.append(s"  val path: String = ${escapedLiteral(dirPath(dir.segments))}\n")
      dir.dirs.foreach { sub =>
        sb.append(s"\n  /** Resources in ${dirPath(sub.segments)} */\n")
        sb.append(
          s"  val ${ident(sub.segments.last)}: ${objectRef(sub)}.type = ${objectRef(sub)}\n",
        )
      }
      dir.files.foreach { case (name, file) =>
        val path = (dir.segments :+ name).mkString("/", "/", "")
        sb.append(s"\n  /** $path */\n")
        sb.append(s"  val ${ident(name)}: String =\n    ").append(textExpr(readText(file)))
        sb.append("\n")
      }
      sb.toString
    }

    val rootSource = {
      val sb = new java.lang.StringBuilder
      sb.append(header(pkg))
      sb.append("\n/** Resources of the module, embedded as strings */\n")
      sb.append("object Resources {\n")
      // Before `map`, which reads them while being initialized
      sb.append(members(root, Set("map", "read")))
      sb.append("\n  /** Texts of all resources by their paths, like `/dir/file.txt` */\n")
      sb.append("  val map: _root_.java.util.HashMap[String, String] = {\n")
      sb.append("    val m = new _root_.java.util.LinkedHashMap[String, String]\n")
      // Many puts in one method could exceed the max size of a method in a class file
      val puts = for {
        dir <- dirs.values.toSeq.sortBy(_.segments.mkString("/"))
        (name, _) <- dir.files
      } yield {
        val ref = if (dir.segments.isEmpty) s"_root_.$pkg.Resources" else objectRef(dir)
        val path = (dir.segments :+ name).mkString("/", "/", "")
        s"      m.put(${escapedLiteral(path)}, $ref.${ident(name)})\n"
      }
      puts.grouped(1000).zipWithIndex.foreach { case (group, i) =>
        sb.append(s"    def put$i(): Unit = {\n")
        group.foreach(sb.append)
        sb.append("    }\n")
        sb.append(s"    put$i()\n")
      }
      sb.append("    m\n")
      sb.append("  }\n")
      sb.append(
        "\n  /** @return the text of the resource with the given path, like `/dir/file.txt` */\n",
      )
      sb.append("  def read(path: String): String = {\n")
      sb.append("    val text = map.get(path)\n")
      sb.append("    if (text eq null)\n")
      sb.append(
        "      throw new _root_.java.util.NoSuchElementException(\"Resource not found: \" + path)\n",
      )
      sb.append("    text\n")
      sb.append("  }\n")
      sb.append("}\n")
      sb.toString
    }

    val dirSources = dirs.values.filter(_.segments.nonEmpty).map { dir =>
      val name = objectNames(dir.segments)
      val sb = new java.lang.StringBuilder
      sb.append(header(s"$pkg.$dirsPkg"))
      sb.append(s"\n/** Resources in ${dirPath(dir.segments)} */\n")
      sb.append(s"object $name {\n")
      sb.append(members(dir, Set.empty))
      sb.append("}\n")
      (os.sub / dirsPkg / s"$name.scala") -> sb.toString
    }

    (os.sub / "Resources.scala" -> rootSource) +: dirSources.toSeq
  }

  private def dirPath(segments: Seq[String]): String =
    if (segments.isEmpty) "/" else segments.mkString("/", "/", "/")

  private def header(pkg: String): String =
    s"// Generated by ResourcesAsStrings from the resources of the module. Do not edit.\n" +
      s"package $pkg\n"

  private def readText(file: os.Path): String =
    try
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(os.read.bytes(file)))
        .toString
    catch {
      case e: CharacterCodingException =>
        sys.error(s"Only UTF-8 text resources are supported, but $file is not: $e")
    }

  /** @return the name as a Scala identifier, in backticks if needed */
  def ident(name: String): String =
    if (name.matches("[A-Za-z_][A-Za-z0-9_]*") && !keywords(name)) name else s"`$name`"

  /** @return
    *   an expression with the value of the text, split into literals that fit in class files
    */
  private def textExpr(text: String): String = chunks(text) match {
    case Seq() => "\"\""
    case Seq(chunk) => literal(chunk)
    case chunks =>
      chunks.map(chunk => s"      .append(${literal(chunk)})\n")
        .mkString(s"new _root_.java.lang.StringBuilder(${text.length})\n", "", "      .toString")
  }

  /** Splits the text into chunks of up to [[maxLiteralBytes]] bytes in the modified UTF-8 used by
    * class files, after the ends of lines when possible
    */
  private def chunks(text: String): Seq[String] = {
    val result = Seq.newBuilder[String]
    var start = 0
    while (start < text.length) {
      var bytes = 0
      var end = start
      var lastLineEnd = -1
      while (end < text.length && bytes + utf8Len(text.charAt(end)) <= maxLiteralBytes) {
        bytes += utf8Len(text.charAt(end))
        if (text.charAt(end) == '\n') lastLineEnd = end + 1
        end += 1
      }
      if (end < text.length) {
        if (lastLineEnd > start) end = lastLineEnd
        else if (Character.isHighSurrogate(text.charAt(end - 1))) end -= 1
      }
      result += text.substring(start, end)
      start = end
    }
    result.result()
  }

  private def utf8Len(c: Char): Int =
    if (c != 0 && c < 0x80) 1 else if (c < 0x800) 2 else 3

  /** @return
    *   a multi-line literal, which shows the text as it is, if the text can be put in one, or an
    *   escaped literal otherwise
    */
  private def literal(text: String): String =
    if (
      !text.contains("\"\"\"") && text.forall(c => c == '\n' || c == '\t' || c >= ' ' && c != 0x7f)
    )
      "\"\"\"" + text + "\"\"\""
    else escapedLiteral(text)

  private def escapedLiteral(text: String): String = {
    val sb = new java.lang.StringBuilder("\"")
    text.foreach {
      case '"' => sb.append("\\\"")
      case '\\' => sb.append("\\\\")
      case '\n' => sb.append("\\n")
      case '\r' => sb.append("\\r")
      case '\t' => sb.append("\\t")
      case c if c < ' ' || c == 0x7f => sb.append(f"\\u${c.toInt}%04x")
      case c => sb.append(c)
    }
    sb.append('"').toString
  }
}
