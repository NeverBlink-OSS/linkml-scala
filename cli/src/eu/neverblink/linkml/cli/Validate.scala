package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.cli.ValidationReport.{Format, Issue, Severity}
import eu.neverblink.linkml.schemaview.{SchemaValidator, SchemaView}

import scala.util.control.NonFatal

@HelpMessage("Validate one or more LinkML schemas")
@ArgsName("<input-file>...")
final case class ValidateOptions(
    @HelpMessage(
      "Output format for the validation report. One of terminal|plain. " +
        "'terminal' is colored and human-friendly (default); 'plain' is bare text for tools.",
    )
    format: String = "terminal",
    @HelpMessage("Treat warnings as failures: exit with a non-zero code if any warnings are found.")
    strict: Boolean = false,
)

object Validate extends BaseCommand[ValidateOptions] {
  override def names: List[List[String]] = List(
    List("validate"),
    List("lint"),
  )

  override def run(options: ValidateOptions, remainingArgs: RemainingArgs): Unit =
    val format = Format.parse(options.format).getOrElse(
      err(s"Unknown format '${options.format}'. Supported formats: ${Format.supported}."),
    )
    val inputNames = remainingArgs.remaining
    if inputNames.isEmpty then err("At least one input file is required.")

    // Every file is checked even if an earlier one failed.
    val reports = inputNames.map(name => name -> collectIssues(name))
    printLine(ValidationReport.renderAll(reports, format))

    // Errors and fatal problems always fail the command. Warnings only fail in --strict mode.
    val failed = reports.exists((_, issues) =>
      issues.exists { i =>
        i.severity == Severity.Fatal || i.severity == Severity.Error ||
        (options.strict && i.severity == Severity.Warning)
      },
    )
    if failed then exit(1)

  /** Load the schema and collect every issue.
    *
    * Fatal problems can't be recovered into a [[SchemaView]] (its constructor refuses to build a
    * schema with fatal problems), so they surface as a load exception. [[FatalSchemaException]]
    * carries the structured issues, anything else only has a message to go on. Errors and warnings
    * come from the linter on the successfully-built view.
    */
  private def collectIssues(inputName: String): Seq[Issue] =
    try
      SchemaView.loadSchemaViewFromUri(inputName) match {
        case Right(sv) => ValidationReport.issuesOf(SchemaValidator(using sv).lintProblems)
        case Left(problems) => ValidationReport.issuesOf(problems)
      }
    catch case NonFatal(ex) => fatalIssues(Option(ex.getMessage).getOrElse(ex.toString))

  private def fatalIssues(message: String): Seq[Issue] =
    message
      .stripPrefix("Fatal validation problems:\n")
      .linesIterator
      .filter(_.nonEmpty)
      .map(Issue(Severity.Fatal, _))
      .toSeq
}
