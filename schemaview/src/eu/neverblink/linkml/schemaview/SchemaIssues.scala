package eu.neverblink.linkml.schemaview

import eu.neverblink.linkml.validation.{IssueSeverity, SchemaError, SchemaFatal, SchemaIssue}

import scala.util.Failure

/** Presentation helpers for the generated schema validation report model.
  */
object SchemaIssues {

  /** Short, one-line description of an issue.
    *
    * @note
    *   Only populated on issues that have been through `infer()`; the validator does not do that
    *   for you.
    */
  def description(issue: SchemaIssue): String = issue.message.getOrElse("")

  /** Longer description, including hints to fix where applicable. Issues whose long form is
    * identical to the short one declare no `details`, so fall back to `message`.
    *
    * @note
    *   Only populated on issues that have been through `infer()`.
    */
  def verbose(issue: SchemaIssue): String = issue.details.orElse(issue.message).getOrElse("")

  /** Human-readable severity label. */
  def level(issue: SchemaIssue): String = issue.severity match {
    case IssueSeverity.Fatal => "Fatal"
    case IssueSeverity.Error => "Error"
    case IssueSeverity.Warning => "Warning"
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
      (if showLevel then level(x) + ": " else "") +
        (if verbose then SchemaIssues.verbose(x) else description(x)),
    )
    val printed = stringified.mkString("\n")
    val restCount = problems.size - maxProblems
    val rest = if restCount > 0 then s"\nand $restCount more problems..." else ""
    printed + rest
  }

  /** Exception which formats a collection of schema (fatal) errors
    *
    * @param problems
    *   Issues to show in the exception
    * @param maxProblems
    *   Max number of issues to format
    */
  final case class ValidationFailedException(
      problems: Seq[SchemaError | SchemaFatal],
      maxProblems: Int,
  ) extends Exception("Schema validation failed:\n" + format(problems, maxProblems, false, false))

  /** Create a [[Failure]] containing a [[ValidationFailedException]]
    * @param problems
    *   Issues to include in the exception. Must be non-empty
    * @param maxProblems
    *   Max number of issues to include before ellipsis
    */
  def failure(
      problems: Seq[SchemaError | SchemaFatal],
      maxProblems: Int,
  ): Failure[Nothing] = {
    assume(problems.nonEmpty)
    Failure(ValidationFailedException(problems, maxProblems))
  }
}
