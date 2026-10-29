package eu.neverblink.linkml.agents

import org.virtuslab.yaml.{Node, Tag, parseYaml}

/** Loose reading of YAML documents, for files that have no fixed schema. */
object Yaml {

  /** The document, or None for an empty one. */
  def parse(text: String): Either[String, Option[Node]] =
    if text.trim.isEmpty then Right(None)
    else parseYaml(text).left.map(_.msg).map(Some(_))

  def load(text: String): Option[Node] =
    parse(text).fold(e => throw IllegalArgumentException(e), identity)

  extension (node: Node) {
    def entries: Seq[(String, Node)] = node match {
      case m: Node.MappingNode =>
        m.mappings.toSeq.collect { case (k: Node.ScalarNode, v) => k.value -> v }
      case _ => Seq.empty
    }

    def items: Seq[Node] = node match {
      case s: Node.SequenceNode => s.nodes
      case _ => Seq.empty
    }

    def get(key: String): Option[Node] = entries.collectFirst { case (`key`, v) => v }

    /** The value of a scalar, or None for null and for collections. */
    def text: Option[String] = node match {
      case s: Node.ScalarNode if s.tag != Tag.nullTag => Some(s.value)
      case _ => None
    }

    /** Like Python's truthiness of the loaded value. */
    def isTrue: Boolean = node match {
      case s: Node.ScalarNode if s.tag == Tag.boolean => s.value.toBooleanOption.getOrElse(false)
      case s: Node.ScalarNode => s.tag != Tag.nullTag && s.value.nonEmpty
      case _ => entries.nonEmpty || items.nonEmpty
    }
  }
}
