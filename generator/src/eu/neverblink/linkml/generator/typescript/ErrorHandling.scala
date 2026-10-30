package eu.neverblink.linkml.generator.typescript

import eu.neverblink.linkml.schemaview.Case

/** How the generated functions report the `errors` of a LinkML function definition.
  */
enum ErrorHandling:
  /** The method returns the value, and its doc comment gets a `@throws` tag for each error class.
    */
  case exception

  /** The method returns `Result<R, Failure<...> | ...>`: the value or one of the errors. A function
    * with no `errors` returns `R` as it is.
    */
  case result

object ErrorHandling {

  /** The mode requested by the caller, or None if it's unknown. */
  def parse(mode: String): Option[ErrorHandling] = Case.base(mode) match {
    case "throw" | "exception" => Some(ErrorHandling.exception)
    case "result" => Some(ErrorHandling.result)
    case _ => None
  }

  def unknownMode(mode: String): String =
    s"Unknown error handling mode '$mode'. Supported modes: throw, result."

  /** The named mode.
    *
    * @throws IllegalArgumentException
    *   if `mode` is not a mode we know
    */
  def apply(mode: String): ErrorHandling =
    parse(mode).getOrElse(throw IllegalArgumentException(unknownMode(mode)))
}
