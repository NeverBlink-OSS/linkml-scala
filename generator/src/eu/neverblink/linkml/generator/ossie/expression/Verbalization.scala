package eu.neverblink.linkml.generator.ossie.expression

import fastparse.*
import fastparse.NoWhitespace.given

/** How a relationship is said out loud: `{Concept} phrase {Range}`, or `{Concept} phrase
  * {Range:role}`, where `role` needs disambiguation.
  */
private[ossie] final case class Verbalization(
    concept: Ref,
    phrase: String,
    range: Ref,
    role: Option[String],
) {
  def render: String = {
    val target = role.fold(s"{${range.name}}")(r => s"{${range.name}:$r}")
    s"{${concept.name}} $phrase $target"
  }
}

private[ossie] object Verbalization {

  /** Read a verbalization, or None if it is not a supported shape. */
  def parse(text: String): Option[Verbalization] =
    fastparse.parse(text, verbalization(using _)) match {
      case Parsed.Success(v, _) => Some(v)
      case _: Parsed.Failure => None
    }

  private def verbalization[$: P]: P[Verbalization] =
    P(ws ~ "{" ~ name ~ "}" ~ phrase ~ "{" ~ name ~ (":" ~ role).? ~ "}" ~ ws ~ End).map {
      (concept, phrase, range, role) =>
        Verbalization(Ref(concept), phrase, Ref(range), role)
    }

  private def name[$: P]: P[String] = P(CharsWhile(c => c != '{' && c != '}' && c != ':').!)

  private def phrase[$: P]: P[String] = P(CharsWhile(c => c != '{' && c != '}').!).map(_.trim)

  private def role[$: P]: P[String] = P(CharsWhile(_ != '}').!)

  private def ws[$: P]: P[Unit] = P(CharsWhileIn(" \t", 0))
}
