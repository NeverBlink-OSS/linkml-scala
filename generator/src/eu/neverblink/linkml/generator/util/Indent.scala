package eu.neverblink.linkml.generator.util

import Printable.PrintableOrSimple

import java.lang

class Indent(spaces: Int = 2)(inner: lang.StringBuilder => Unit) {
  override def toString: String = {
    val stringBuilder = new lang.StringBuilder()
    inner(stringBuilder)
    stringBuilder.toString.indent(spaces)
  }
}

/** Base trait for classes that have a code-gen representation, to be used with the [[indent]]
  * interpolator
  */
trait Printable:
  def print: String

object Printable {

  /** Printable */
  type PrintableOrSimple = Printable | String | AnyVal
  extension (lp: PrintableOrSimple)
    def print: String = lp match {
      case p: Printable => p.print
      case s: String => s
      case anyVal: AnyVal => anyVal.toString
    }
  extension (seq: Seq[PrintableOrSimple]) def lines: String = seq.map(_.print).mkString("\n")
}

extension (sc: StringContext)
  /** String interpolator allowing automatic indentations that cooperate with `stripMargin`.
    *
    * @see
    *   [[scala.collection.immutable.StringOps.stripMargin]]
    *
    * @note
    *   This interpolator does not allow complex objects by default, but allows [[Printable]]
    *   objects. This allows defining
    */
  def indent(args: PrintableOrSimple*): String = {
    val sb = lang.StringBuilder()
    var first = true
    for (part, arg) <- sc.parts.zip(args) do {
      sb.append(part.translateEscapes())
      var i = part.length - 1
      while i >= 0 && part(i) == ' ' do i -= 1
      if first && i == -1 then sb.append(arg.print.indent(part.length).strip())
      else if i != -1 && part(i) == '|' then
        sb.append(arg.print.indent(part.length - i - 1).strip())
      else sb.append(arg.print.strip())
      first = false
    }
    sb.append(sc.parts.last.translateEscapes())
    sb.toString
  }
