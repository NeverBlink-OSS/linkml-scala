package eu.neverblink.linkml.generator.ossie.expression

/** A name as it appears in an Ossie expression: a concept on a concept-level expression or the role
  * in a relationship constraint.
  */
private[ossie] opaque type Ref = String

private[ossie] object Ref {
  def apply(name: String): Ref = name

  /** What a name may start with. */
  def isStart(c: Char): Boolean = (c.isLetter && c < 128) || c == '_'

  /** What a name may continue with. There is no quoting/escaping support in Ossie right now. */
  def isPart(c: Char): Boolean = isStart(c) || (c >= '0' && c <= '9')

  extension (r: Ref) def name: String = r
}
