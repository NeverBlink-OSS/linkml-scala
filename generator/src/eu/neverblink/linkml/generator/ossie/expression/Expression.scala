package eu.neverblink.linkml.generator.ossie.expression

import fastparse.*
import fastparse.NoWhitespace.given

/** One expression of the SQL-like expression language used in the Ossie ontology specification.
  *
  * This is a model of a subset of the language, as supported in LinkML-Scala. As of Ossie 0.2.0 the
  * language has no formal grounding, so this is rough in places. We will try to make it more robust
  * when the language is better defined.
  *
  * We ignore unparseable expressions, or those that we don't support yet.
  */
private[ossie] sealed trait Expression {

  /** The name this expression is about. */
  def ref: Ref

  /** Render this expression as a string, in the same form that [[parse]] can read. */
  def render: String
}

private[ossie] object Expression {

  /** `Ref.relationship` - how a concept states that a relationship is mandatory. */
  final case class Member(ref: Ref, relationship: String) extends Expression {
    override def render: String = s"${ref.name}.$relationship"
  }

  /** `Ref IN ('a', 'b')` - how an enumerated value type states the values it allows. */
  final case class InList(ref: Ref, values: Seq[Literal.Text]) extends Expression {
    override def render: String =
      s"${ref.name} IN (${values.map(_.render).mkString(", ")})"
  }

  /** `Ref >= 1` */
  final case class AtLeast(ref: Ref, value: Literal) extends Expression {
    override def render: String = s"${ref.name} >= ${value.render}"
  }

  /** `Ref <= 10` */
  final case class AtMost(ref: Ref, value: Literal) extends Expression {
    override def render: String = s"${ref.name} <= ${value.render}"
  }

  /** `REGEXP_LIKE(Ref, '^x')` */
  final case class RegexpLike(ref: Ref, pattern: Literal.Text) extends Expression {
    override def render: String =
      s"REGEXP_LIKE(${ref.name}, ${pattern.render})"
  }

  /** Read an expression, or None if it is not one of the shapes above. */
  def parse(text: String): Option[Expression] =
    fastparse.parse(text, expression(using _)) match {
      case Parsed.Success(e, _) => Some(e)
      case _: Parsed.Failure => None
    }

  private def expression[$: P]: P[Expression] =
    P(ws ~ (regexpLike | inList | atLeast | atMost | member) ~ ws ~ End)

  /** Ossie cannot quote a name, so a name is a bare identifier and nothing else. */
  private def ident[$: P]: P[String] =
    P((CharPred(Ref.isStart) ~ CharsWhile(Ref.isPart, 0)).!)

  private def ref[$: P]: P[Ref] = ident.map(Ref.apply)

  /** A single-quoted string, in which a doubled quote is an escaped one. */
  private def literalStr[$: P]: P[Literal.Text] =
    P("'" ~ (P("''").map(_ => "'") | CharPred(_ != '\'').!).rep ~ "'")
      .map(_.mkString).map(Literal.Text.apply)

  /** A bare number. Read as a whole token and checked with the same test [[Literal.fromLinkml]]
    * uses, so the writer and the reader cannot disagree about what counts as one.
    */
  private def literalNum[$: P]: P[Literal.Number] =
    P(CharsWhile(c => c != ' ' && c != '\t' && c != ',' && c != ')').!)
      .filter(Literal.isNumber)
      .map(Literal.Number.apply)

  private def value[$: P]: P[Literal] = P(literalStr | literalNum)

  private def member[$: P]: P[Member] = P(ref ~ "." ~ ident).map(Member.apply)

  private def inList[$: P]: P[InList] =
    P(ref ~ ws1 ~ "IN" ~ ws ~ "(" ~ ws ~ literalStr.rep(sep = ws ~ "," ~ ws) ~ ws ~ ")")
      .map(InList.apply)

  private def atLeast[$: P]: P[AtLeast] = P(ref ~ ws ~ ">=" ~ ws ~ value).map(AtLeast.apply)

  private def atMost[$: P]: P[AtMost] = P(ref ~ ws ~ "<=" ~ ws ~ value).map(AtMost.apply)

  private def regexpLike[$: P]: P[RegexpLike] =
    P("REGEXP_LIKE" ~ ws ~ "(" ~ ws ~ ref ~ ws ~ "," ~ ws ~ literalStr ~ ws ~ ")").map(
      RegexpLike.apply,
    )

  private def ws[$: P]: P[Unit] = P(CharsWhileIn(" \t", 0))

  private def ws1[$: P]: P[Unit] = P(CharsWhileIn(" \t"))
}
