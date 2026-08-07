package org.virtuslab.yaml

/** Builds scalar nodes that are always tagged as YAML strings.
  *
  * The public [[Node.ScalarNode]] factory infers the tag from the value, so `"123"`, `"true"` and
  * `"null"` come back tagged `int`/`bool`/`null` even when they are string values. Serializers use
  * that tag, and would emit them as unquoted literals. The needed constructor is `private[yaml]`,
  * so this helper lives in scala-yaml's own package.
  */
object StringNode {
  def apply(value: String): Node.ScalarNode =
    // Keep null as a null-tagged node: absent values are encoded that way.
    if (value eq null) Node.ScalarNode(null)
    else new Node.ScalarNode(value, Tag.str)
}
