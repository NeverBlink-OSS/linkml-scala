package eu.neverblink.linkml.generator.ossie.expression

import eu.neverblink.linkml.generator.ossie.expression.Expression.{AtLeast, AtMost, RegexpLike}
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.LinkmlAny

/** The value constraints used in Ossie in the `requires` field, for one specific [[Ref]].
  *
  * This is the subset of the constraints that can be expressed in LinkML currently.
  */
private[ossie] final case class Constraints(
    minimumValue: Option[LinkmlAny] = None,
    maximumValue: Option[LinkmlAny] = None,
    pattern: Option[String] = None,
) {

  /** As expressions over [[ref]]. A bound that cannot be written as an Ossie value is left out. */
  def render(ref: Ref): Seq[Expression] = {
    val out = Seq.newBuilder[Expression]
    minimumValue.flatMap(Literal.fromLinkml).foreachFast(v => out += AtLeast(ref, v))
    maximumValue.flatMap(Literal.fromLinkml).foreachFast(v => out += AtMost(ref, v))
    pattern.foreachFast(p => out += RegexpLike(ref, Literal.Text(p)))
    out.result()
  }
}

private[ossie] object Constraints {

  /** Gather constraints [[expressions]] place on [[ref]] into [[Constraints]].
    *
    * This method will ignore any expressions that do not apply to [[ref]], and any expressions that
    * cannot be expressed in LinkML.
    */
  def from(ref: Ref, expressions: Seq[Expression]): Constraints =
    expressions.filter(_.ref == ref).foldLeft(Constraints()) { (acc, expression) =>
      expression match {
        case AtLeast(_, v) => acc.copy(minimumValue = Some(v.toLinkml))
        case AtMost(_, v) => acc.copy(maximumValue = Some(v.toLinkml))
        case RegexpLike(_, p) => acc.copy(pattern = Some(p.value))
        case _ => acc
      }
    }
}
