package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.cli.ValidationReport.{Format, Issue, Severity}
import eu.neverblink.linkml.schemaview.{SchemaValidator, SchemaView}

import scala.util.control.NonFatal

@HelpMessage("Validate a LinkML schema")
@ArgsName("<input-file>")
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
  override def run(options: ValidateOptions, remainingArgs: RemainingArgs): Unit =
    val format = Format.parse(options.format).getOrElse(
      err(s"Unknown format '${options.format}'. Supported formats: ${Format.supported}."),
    )
    val inputName = remainingArgs.remaining.headOption.getOrElse(err("Input file is required."))

    val issues = collectIssues(inputName)
    printLine(ValidationReport.render(inputName, issues, format))

    // Errors and fatal problems always fail the command; warnings only fail in --strict mode.
    val failed = issues.exists { i =>
      i.severity == Severity.Fatal || i.severity == Severity.Error ||
      (options.strict && i.severity == Severity.Warning)
    }
    if failed then exit(1)

  /** Load the schema and collect every issue.
    *
    * Fatal problems can't be recovered into a [[SchemaView]] (its constructor refuses to build a
    * schema with fatal problems), so they surface as a load exception, which we translate into
    * fatal issues. Errors and warnings come from the linter on the successfully-built view.
    */
  private def collectIssues(inputName: String): Seq[Issue] =
    try
      val sv = SchemaView.loadSchemaViewFromUri(inputName)
      ValidationReport.issuesOf(SchemaValidator(using sv).lintProblems)
    catch
      case NonFatal(ex) => fatalIssues(Option(ex.getMessage).getOrElse(ex.toString))

  private def fatalIssues(message: String): Seq[Issue] =
    message
      .stripPrefix("Fatal validation problems:\n")
      .linesIterator
      .filter(_.nonEmpty)
      .map(Issue(Severity.Fatal, _))
      .toSeq
}
