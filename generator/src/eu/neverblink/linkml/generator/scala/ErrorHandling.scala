package eu.neverblink.linkml.generator.scala

import eu.neverblink.linkml.schemaview.Case

/** How the generated functions report the `errors` of a LinkML function definition.
  */
enum ErrorHandling:
  /** Each error class gets an exception class, and the method is annotated with `@throws` for each
    * of them.
    */
  case annotation

  /** The method returns `Either[E1 | E2, R]`, with the error classes themselves on the left. No
    * exception classes are generated. A function with no `errors` returns `R` as it is.
    */
  case either

object ErrorHandling {

  /** The mode the caller named, or None if it is not one we know.
    *
    * Accepts camel, kebab and snake case alike.
    */
  def parse(mode: String): Option[ErrorHandling] = Case.base(mode) match {
    case "annotation" => Some(ErrorHandling.annotation)
    case "either" => Some(ErrorHandling.either)
    case _ => None
  }

  def unknownMode(mode: String): String =
    s"Unknown error handling mode '$mode'. Supported modes: annotation, either."

  /** The named mode.
    *
    * @throws IllegalArgumentException
    *   if `mode` is not a mode we know
    */
  def apply(mode: String): ErrorHandling =
    parse(mode).getOrElse(throw IllegalArgumentException(unknownMode(mode)))
}
