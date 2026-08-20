package millbuild

import _root_.scala.meta.*

/** Reads a generator's `Options` case class out of its source: the fields, and the `@param` lines
  * documenting them.
  *
  * Shared by [[PyBindingsGen]] and [[CApiGen]]. A syntactic parse only, no compiler, the same as
  * [[TsDefsGen]].
  */
object OptionsReader {

  /** One field of an `Options` case class.
    *
    * @param name
    *   field name, with any backticks stripped
    * @param tpe
    *   the declared type, as written
    * @param default
    *   the default value, as written
    * @param doc
    *   the matching `@param` text, empty if undocumented
    */
  final case class Field(name: String, tpe: String, default: String, doc: String)

  /** @param source
    *   the generator's source text
    * @param generator
    *   the object holding the `Options` case class
    * @param path
    *   only used to say where, if there is nothing to read
    */
  def fields(source: String, generator: String, path: String): Seq[Field] = {
    val options = findOptions(source, generator)
      .getOrElse(sys.error(s"no Options case class found in $generator ($path)"))
    val docs = paramDocs(source, options)
    options.ctor.paramClauses.flatMap(_.values).map { param =>
      val name = param.name.value.stripPrefix("`").stripSuffix("`")
      Field(
        name = name,
        tpe = param.decltpe.map(_.syntax).getOrElse("Any"),
        default = param.default.map(_.syntax).getOrElse(""),
        doc = docs.getOrElse(name, ""),
      )
    }
  }

  private def findOptions(source: String, generator: String): Option[Defn.Class] = {
    val parsed = dialects.Scala3(source).parse[Source].get
    def inObject(t: Tree): Option[Defn.Class] = t match {
      case o: Defn.Object if o.name.value == generator =>
        collectClasses(o).find(_.name.value == "Options")
      case _ => t.children.iterator.flatMap(inObject).nextOption()
    }
    inObject(parsed)
  }

  private def collectClasses(t: Tree): List[Defn.Class] = {
    val self = t match { case c: Defn.Class => List(c); case _ => Nil }
    self ++ t.children.flatMap(collectClasses)
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
        docs += current -> text.toString.trim
        text.clear()
        current = ""
      }
    lines.foreach { line =>
      if line.startsWith("@param") then {
        flush()
        current = line.stripPrefix("@param").trim.stripPrefix("`").stripSuffix("`")
      } else if line.startsWith("@") then flush()
      else if current.nonEmpty && line.nonEmpty then text.append(line).append(" ")
    }
    flush()
    docs.result()
  }
}
