package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.validation.{IssueSeverity, SchemaFatal, SchemaIssue}

/** Presentation helpers for the generated schema validation report model.
  */
object SchemaIssues {

  /** Short, one-line description of an issue.
    *
    * @note
    *   Only populated on issues that have been through `infer()`; the validator does not do that
    *   for you.
    */
  def description(issue: SchemaIssue): String = issue.message.getOrElseFast("")

  /** Longer description, including hints to fix where applicable. Issues whose long form is
    * identical to the short one declare no `details`, so fall back to `message`.
    *
    * @note
    *   Only populated on issues that have been through `infer()`.
    */
  def verbose(issue: SchemaIssue): String =
    issue.details.orElseFast(issue.message).getOrElseFast("")

  /** Human-readable severity label. */
  def level(issue: SchemaIssue): String = issue.severity match {
    case _: IssueSeverity.Fatal.type => "Fatal"
    case _: IssueSeverity.Error.type => "Error"
    case _: IssueSeverity.Warning.type => "Warning"
  }

  /** Format a collection of issues into a text representation
    *
    * @param problems
    *   Issues to format
    * @param maxProblems
    *   Maximum number of issues to include in the text before ellipsis
    * @param verbose
    *   Whether to use the verbose description
    * @param showLevel
    *   Whether to format the severity of the issue
    */
  def format(
      problems: Seq[SchemaIssue],
      maxProblems: Int,
      verbose: Boolean,
      showLevel: Boolean,
  ): String = {
    val limited = problems.take(maxProblems)
    val stringified = limited.map(x =>
      (if showLevel then level(x).concat(": ") else "").concat(
        if verbose then SchemaIssues.verbose(x) else description(x),
      ),
    )
    val printed = stringified.mkString("\n")
    val restCount = problems.size - maxProblems
    if (restCount > 0) {
      s"$printed\nand $restCount more problems..."
    } else printed
  }

  /** Exception thrown when a schema cannot be loaded at all – a parse failure, an unreadable
    * import, or a fatal validation problem.
    *
    * @param problems
    *   Issues that blocked loading. Must already have been through `infer()`.
    * @param maxProblems
    *   Max number of issues to format
    */
  final case class FatalSchemaException(
      problems: Seq[SchemaFatal],
      maxProblems: Int,
  ) extends RuntimeException(
        "Fatal validation problems:\n" + format(problems, maxProblems, true, false),
      )

  /** Unwrap a load result, throwing [[FatalSchemaException]] if it failed.
    */
  def orThrow[T](loaded: Either[Seq[SchemaFatal], T], maxProblems: Int = 5): T =
    loaded match {
      case Right(value) => value
      case Left(problems) => throw FatalSchemaException(problems.map(_.infer()), maxProblems)
    }
}
