package millbuild

import _root_.scala.meta.*

/** Generates the npm package's TypeScript declarations from the Scala.js facade
  * ([[LinkMlJsApi]]), so the types can never drift from the implementation.
  * Syntactic parse only — no compiler needed.
  */
object TsDefsGen {
  private val exportedName = "LinkML"
  private val objectName = "LinkMlJsApi"

  def apply(scalaSource: String): String = {
    val parsed = dialects.Scala3(scalaSource).parse[Source].get
    val obj = findObject(parsed).getOrElse(sys.error(s"object $objectName not found"))

    val nested = collectClasses(obj).flatMap(collectDefs).toSet
    val methods = collectDefs(obj)
      .filterNot(nested.contains)
      .filterNot(_.mods.exists(_.is[Mod.Private]))

    val members = methods.map(renderMethod(scalaSource, _)).mkString("\n\n")

    s"""// AUTO-GENERATED from generator/src-js/eu/neverblink/linkml/js/LinkMlJsApi.scala.
       |// Do not edit by hand — regenerate with ./mill uiTypes (or generator.js.npmPackage).
       |
       |export interface ${exportedName}Api {
       |$members
       |}
       |
       |export declare const $exportedName: ${exportedName}Api;
       |""".stripMargin
  }

  private def findObject(t: Tree): Option[Defn.Object] = t match {
    case o: Defn.Object if o.name.value == objectName => Some(o)
    case _ => t.children.iterator.flatMap(findObject).nextOption()
  }

  private def collectDefs(t: Tree): List[Defn.Def] = {
    val self = t match { case d: Defn.Def => List(d); case _ => Nil }
    self ++ t.children.flatMap(collectDefs)
  }

  private def collectClasses(t: Tree): List[Defn.Class] = {
    val self = t match { case c: Defn.Class => List(c); case _ => Nil }
    self ++ t.children.flatMap(collectClasses)
  }

  private def renderMethod(source: String, d: Defn.Def): String = {
    val params = d.paramClauseGroups.flatMap(_.paramClauses).flatMap(_.values)
    val tsParams = params.map { p =>
      val name = tsName(p.name.value)
      val tpe = p.decltpe.map(_.syntax).getOrElse("Any")
      val optional = p.default.isDefined || isOptionalType(tpe)
      s"$name${if (optional) "?" else ""}: ${mapType(tpe)}"
    }
    val retType = mapType(d.decltpe.map(_.syntax).getOrElse("Unit"))
    val signature = s"  ${d.name.value}(${tsParams.mkString(", ")}): $retType;"

    leadingDoc(source, d).map(parseDoc).map(renderTsDoc) match {
      case Some(doc) => s"$doc\n$signature"
      case None => signature
    }
  }

  private def tsName(scalaName: String): String =
    // Strip backticks (`package` in the source / scaladoc) before renaming, so the
    // signature param and its @param doc stay in sync.
    scalaName.stripPrefix("`").stripSuffix("`") match {
      case "package" => "packageName"
      case n => n
    }

  private def mapType(scalaType: String): String =
    innerOf(scalaType, "Option")
      .orElse(innerOf(scalaType, "js.UndefOr"))
      .map(mapType)
      .getOrElse {
        scalaType match {
          case "String" => "string"
          case "Boolean" => "boolean"
          case "Int" | "Long" | "Double" | "Float" => "number"
          case "js.Dictionary[String]" => "Record<string, string>"
          case other => other
        }
      }

  private def isOptionalType(scalaType: String): Boolean =
    scalaType.startsWith("Option[") || scalaType.startsWith("js.UndefOr[")

  private def innerOf(scalaType: String, wrapper: String): Option[String] = {
    val prefix = s"$wrapper["
    if (scalaType.startsWith(prefix) && scalaType.endsWith("]"))
      Some(scalaType.stripPrefix(prefix).stripSuffix("]"))
    else None
  }

  private case class Doc(summary: String, params: List[(String, String)], ret: Option[String])

  private def leadingDoc(source: String, d: Defn.Def): Option[String] = {
    val start = d.pos.start
    "/\\*\\*[\\s\\S]*?\\*/".r
      .findAllMatchIn(source)
      .filter(_.end <= start)
      .toList
      .lastOption
      .filter(m => source.substring(m.end, start).trim.isEmpty)
      .map(_.matched)
  }

  private def parseDoc(raw: String): Doc = {
    val lines = raw
      .stripPrefix("/**")
      .stripSuffix("*/")
      .linesIterator
      .map(l => l.trim.stripPrefix("*").trim)
      .toList

    val summary = new StringBuilder
    val params = List.newBuilder[(String, String)]
    val retText = new StringBuilder
    var section = 0 // 0 = summary, 1 = @param, 2 = @return
    var currentParam = ""
    val paramText = new StringBuilder

    def flushParam(): Unit =
      if (currentParam.nonEmpty) {
        params += currentParam -> paramText.toString.trim
        paramText.clear()
        currentParam = ""
      }

    lines.foreach { line =>
      if (line.startsWith("@param")) {
        flushParam()
        val rest = line.stripPrefix("@param").trim
        currentParam = rest.takeWhile(!_.isWhitespace)
        val desc = rest.dropWhile(!_.isWhitespace).trim
        if (desc.nonEmpty) paramText.append(desc)
        section = 1
      } else if (line.startsWith("@return")) {
        flushParam()
        section = 2
        val desc = line.stripPrefix("@return").trim
        if (desc.nonEmpty) retText.append(desc)
      } else
        section match {
          case 0 => if (line.nonEmpty || summary.nonEmpty) summary.append(line).append(" ")
          case 1 => if (line.nonEmpty) paramText.append(" ").append(line)
          case 2 => if (line.nonEmpty) retText.append(" ").append(line)
        }
    }
    flushParam()
    Doc(summary.toString.trim, params.result(), Option.when(retText.nonEmpty)(retText.toString.trim))
  }

  private def renderTsDoc(doc: Doc): String = {
    val b = List.newBuilder[String]
    b += "  /**"
    if (doc.summary.nonEmpty) b += s"   * ${doc.summary}"
    doc.params.foreach { case (name, desc) => b += s"   * @param ${tsName(name)} $desc" }
    doc.ret.foreach(r => b += s"   * @returns $r")
    b += "   */"
    b.result().mkString("\n")
  }
}
