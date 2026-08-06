package eu.neverblink.linkml.schemaview.expression

import eu.neverblink.linkml.schemaview.AttributeView
import fastparse.*
import fastparse.NoWhitespace.given

/** A parsed Python-style interpolation string, as used by LinkML's `equals_expression`.
  *
  * Example: `"Unknown reference to element '{reference_value}'"`
  *
  * As in Python, `{{` and `}}` are escapes for literal `{` and `}`.
  *
  * TODO LNK-170: when we implement other expression types, we probably should make this extend some
  * common Expression trait.
  */
final case class StringInterpolationExpression(
    elements: Seq[StringInterpolationExpression.Element],
)

object StringInterpolationExpression:
  sealed trait Element

  /** Expands to: literal string `value`. */
  final case class Literal(value: String) extends Element

  /** Expands to: the value of the slot named `slotName` in the current context. */
  final case class Substitution(slot: AttributeView) extends Element

  /** Parse an interpolation string into literals and `{slot}` substitutions. */
  def parse(input: String)(using context: AttributeView): Parsed[StringInterpolationExpression] =
    fastparse.parse(input, Parser().expression(using _))

  private final class Parser(using context: AttributeView):
    def expression[$: P]: P[StringInterpolationExpression] =
      P(element.rep ~ End).map(es => StringInterpolationExpression(mergeLiterals(es)))

    private def element[$: P]: P[Element] = P(escapedBrace | substitution | literal)

    /** `{{` and `}}` — an escaped single brace. */
    private def escapedBrace[$: P]: P[Literal] =
      P(("{{" | "}}").!).map(s => Literal(s.take(1)))

    private def substitution[$: P]: P[Substitution] =
      P("{" ~/ slotName ~ "}").flatMap { name =>
        context.definingClassView.attributeViews.get(name) match {
          case Some(value) => Pass(Substitution(value))
          case None =>
            Fail(
              s"Unknown slot name '$name' in context of class " +
                s"'${context.definingClassView.name}'",
            )
        }
      }

    private def slotName[$: P]: P[String] =
      P(CharsWhile(_ != '}').!).opaque("a slot name")

    private def literal[$: P]: P[Literal] =
      P(CharsWhile(c => c != '{' && c != '}').!).map(Literal.apply)

    /** Fold runs of adjacent literals into one, so escapes don't split the surrounding text. */
    private def mergeLiterals(elements: Seq[Element]): Seq[Element] =
      elements.foldLeft(Vector.empty[Element]) {
        case (acc :+ Literal(prev), Literal(next)) => acc :+ Literal(prev + next)
        case (acc, e) => acc :+ e
      }
