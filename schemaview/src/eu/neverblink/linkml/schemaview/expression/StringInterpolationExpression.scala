package eu.neverblink.linkml.schemaview.expression

import eu.neverblink.linkml.schemaview.{
  AttributeView,
  ClassInlineAttributeView,
  ClassView,
  InlineType,
}
import fastparse.*
import fastparse.NoWhitespace.given
import scala.annotation.tailrec

/** A parsed Python-style interpolation string, as used by LinkML's `equals_expression`.
  *
  * Example: `"Unknown reference to element '{reference_value}'"`
  *
  * A substitution may reach into inlined classes with dot notation, as in
  * `"{address.city}, {address.country.name}"`.
  *
  * As in Python, `{{` and `}}` are escapes for literal `{` and `}`.
  *
  * TODO LNK-170: when we implement other expression types, we probably should make this extend some
  * common Expression trait.
  *
  * TODO LNK-170: consider decoupling this from SchemaView, so that we can parse expressions without
  * needing a context.
  */
final case class StringInterpolationExpression(
    elements: Seq[StringInterpolationExpression.Element],
)

object StringInterpolationExpression:
  sealed trait Element

  /** Expands to: literal string `value`. */
  final case class Literal(value: String) extends Element

  /** Expands to: the value found by following `path` from the current context's class.
    *
    * `path` is never empty. Every element but the last is a single-valued inlined class, which the
    * path descends into; the last element is the attribute holding the substituted value.
    */
  final case class Substitution(path: Seq[AttributeView]) extends Element:
    /** The attribute holding the substituted value. */
    def target: AttributeView = path.last

    /** The path as written in the schema, e.g. `address.city`. */
    def pathString: String = path.map(_.slotView.slot.name).mkString(".")

  /** Parse an interpolation string into literals and `{slot}` / `{slot.path}` substitutions. */
  def parse(input: String)(using context: AttributeView): Parsed[StringInterpolationExpression] =
    fastparse.parse(input, Parser().expression(using _))

  private final class Parser(using context: AttributeView):
    def expression[$: P]: P[StringInterpolationExpression] =
      P(element.rep ~ End).map(es => StringInterpolationExpression(mergeLiterals(es)))

    private def element[$: P]: P[Element] = P(escapedBrace | substitution | literal)

    /** `{{` and `}}` – an escaped single brace. */
    private def escapedBrace[$: P]: P[Literal] =
      P(("{{" | "}}").!).map(s => Literal(s.take(1)))

    private def substitution[$: P]: P[Substitution] =
      P("{" ~/ slotPath ~ "}").flatMap { path =>
        resolve(path) match {
          case Right(views) => Pass(Substitution(views))
          case Left(error) => Fail(error)
        }
      }

    /** The raw text between the braces. Segments are split off in [[resolve]] rather than in the
      * grammar, so that a malformed path is reported as one readable message instead of a character
      * offset.
      */
    private def slotPath[$: P]: P[String] =
      P(CharsWhile(_ != '}').!).opaque("a slot name")

    /** Follow a dotted path from the context's class, descending into inlined classes.
      *
      * @return
      *   the attribute for each segment, or a message explaining where the path went wrong
      */
    private def resolve(path: String): Either[String, Seq[AttributeView]] = {
      val segments = path.split("\\.", -1).toIndexedSeq

      @tailrec
      def go(
          name: String,
          rest: Seq[String],
          cls: ClassView,
          acc: Vector[AttributeView],
      ): Either[String, Seq[AttributeView]] =
        cls.attributeViews.get(name) match {
          case None => Left(s"Unknown slot name '$name' in context of class '${cls.name}'")
          case Some(attribute) if rest.isEmpty => Right(acc :+ attribute)
          case Some(attribute) =>
            descend(attribute) match {
              case Left(error) => Left(error)
              case Right(next) => go(rest.head, rest.tail, next, acc :+ attribute)
            }
        }

      if segments.exists(_.isEmpty) then Left(s"Empty slot name in path '$path'")
      else go(segments.head, segments.tail, context.definingClassView, Vector.empty)
    }

    /** The class a path continues into after `attribute`, if it can continue at all. Only a
      * single-valued inlined class holds exactly one nested object to read a slot from.
      */
    private def descend(attribute: AttributeView): Either[String, ClassView] = {
      val name = attribute.slotView.slot.name
      attribute match {
        case ClassInlineAttributeView(_, _, classView, InlineType.plain | InlineType.optional) =>
          Right(classView)
        case _: ClassInlineAttributeView =>
          Left(s"Slot '$name' is a multivalued inlined class, so a path cannot descend into it")
        case _ =>
          Left(
            s"Slot '$name' is not a single-valued inlined class, " +
              "so a path cannot descend into it",
          )
      }
    }

    private def literal[$: P]: P[Literal] =
      P(CharsWhile(c => c != '{' && c != '}').!).map(Literal.apply)

    /** Fold runs of adjacent literals into one, so escapes don't split the surrounding text. */
    private def mergeLiterals(elements: Seq[Element]): Seq[Element] =
      elements.foldLeft(Vector.empty[Element]) {
        case (acc :+ Literal(prev), Literal(next)) => acc :+ Literal(prev + next)
        case (acc, e) => acc :+ e
      }
