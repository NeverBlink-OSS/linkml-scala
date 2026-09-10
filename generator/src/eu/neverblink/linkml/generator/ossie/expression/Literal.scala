package eu.neverblink.linkml.generator.ossie.expression

import eu.neverblink.linkml.runtime.LinkmlAny
import org.virtuslab.yaml.{Node, NodeOps, StringNode, parseYaml}

/** A value written into an Ossie expression. */
private[ossie] sealed trait Literal {

  /** The literal as it appears in an expression. */
  def render: String

  /** The LinkML value this stands for. */
  def toLinkml: LinkmlAny
}

private[ossie] object Literal {

  /** A bare number, kept exactly as written rather than normalized. */
  final case class Number(text: String) extends Literal {
    override def render: String = text

    override def toLinkml: LinkmlAny = LinkmlAny(text)
  }

  /** A single-quoted string. [[value]] is unescaped. */
  final case class Text(value: String) extends Literal {
    override def render: String = "'" + value.replace("'", "''") + "'"

    override def toLinkml: LinkmlAny = LinkmlAny(StringNode(value).asYaml.strip())
  }

  /** The Ossie literal for a LinkML value, or None if it cannot be written as one.
    *
    * LinkML provides these as raw YAML, so the source is read as YAML.
    */
  def fromLinkml(value: LinkmlAny): Option[Literal] = {
    val raw = value.value.strip()
    if raw.isEmpty then None
    else
      parseYaml(raw) match {
        case Right(node: Node.ScalarNode) =>
          val text = node.value
          if text == null || text.isEmpty || text.exists(c => c == '\n' || c == '\r') then None
          else if isNumber(text) then Some(Number(text))
          else Some(Text(text))
        // Anything that is not a plain scalar - a mapping, a sequence, unreadable YAML - has no
        // Ossie value to stand for it.
        case _ => None
      }
  }

  private[expression] def isNumber(text: String): Boolean =
    try {
      BigDecimal(text)
      true
    } catch {
      case _: NumberFormatException => false
    }
}
