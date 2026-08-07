package eu.neverblink.linkml.runtime

import scala.annotation.implicitNotFound
import scala.quoted.*

/** How a slot's value is rendered when an `equals_expression` substitutes it into a string.
  *
  * Instances are resolved by the compiler against the range's Scala type, so a range with no
  * instance in scope is a compile error in the generated code. To support a type this module does
  * not know about, define your own `given` for it.
  */
@implicitNotFound(
  "A value of type ${T} cannot be substituted into an equals_expression, because there is no " +
    "Stringify[${T}] in scope. Define a `given Stringify[${T}]` to say how it should render.",
)
trait Stringify[-T]:
  def stringify(value: T): String

object Stringify:

  /** What the elements of a multivalued slot are joined with. */
  val separator: String = ", "

  given Stringify[String] = value => value
  given Stringify[Boolean] = _.toString
  given Stringify[Int] = _.toString
  given Stringify[Float] = _.toString
  given Stringify[Double] = _.toString
  given Stringify[BigDecimal] = _.toString

  given Stringify[LinkmlDate] = _.value
  given Stringify[LinkmlTime] = _.value
  given Stringify[LinkmlDateTime] = _.value

  /** Covers [[Uri]] and [[Curie]] too – the trait is contravariant. */
  given Stringify[UriOrCurie] = _.original

  /** Also covers `Unknown`, the fallback for a LinkML type with no `base`. */
  given Stringify[LinkmlAny] = _.value

  /** A reference renders as the identifier it holds. */
  given referenceStringify[T]: Stringify[Reference[T]] = _.value

  /** A multivalued slot renders as its elements joined with [[separator]], in document order. */
  given seqStringify[T](using element: Stringify[T]): Stringify[Seq[T]] =
    _.map(element.stringify).mkString(separator)

  /** Derive an instance for a LinkML enum, rendering each case as the text it was declared with in
    * the schema. The text is read from the `@named` annotation the Scala generator emits.
    *
    * `T` must be a sealed hierarchy of case objects, which is what the generator emits for a static
    * enum. Generated enums carry a `derives Stringify` clause, so there is normally no need to call
    * this by hand.
    */
  // `make[T]` is applied explicitly: `Expr` is covariant and `Stringify` contravariant, so
  // leaving it to inference widens the derived type all the way to `Any`.
  inline def derived[T]: Stringify[T] = ${ StringifyImpl.make[T] }

  /** Build an instance from the case-to-text mapping recovered by [[derived]]. Public only because
    * the derived code has to call it.
    */
  def fromMapping[T](mapping: Seq[(T, String)]): Stringify[T] =
    val texts = mapping.toMap
    value => texts(value)

/** Render `value` as a string, for substitution into an `equals_expression`. */
def stringify[T](value: T)(using s: Stringify[T]): String = s.stringify(value)

private object StringifyImpl:
  def make[T: Type](using Quotes): Expr[Stringify[T]] = new StringifyImpl().make[T]

private class StringifyImpl(using Quotes) extends MacroUtils:
  import quotes.reflect.*

  def make[T: Type]: Expr[Stringify[T]] =
    val pairs = adtLeafObjects(TypeRepr.of[T].dealias).map { leafTpe =>
      val text = Expr(enumValueName(leafTpe))
      val value = enumOrModuleValueRef(leafTpe).asExpr.asInstanceOf[Expr[T]]
      '{ ($value, $text) }
    }.toList
    '{ Stringify.fromMapping(${ Expr.ofList(pairs) }) }
