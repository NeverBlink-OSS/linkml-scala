package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.cli.ValidationReport.Format
import eu.neverblink.linkml.schemaview.{SchemaValidator, SchemaView}
import eu.neverblink.linkml.validation.{
  IssueLocationImpl,
  IssueSeverity,
  SchemaImportErrorImpl,
  SchemaIssue,
}

import scala.util.control.NonFatal

@HelpMessage("Validate one or more LinkML schemas")
@ArgsName("<input-file>...")
final case class ValidateOptions(
    @HelpMessage(
      "Output format for the validation report. One of terminal|plain|json. " +
        "'terminal' is colored and human-friendly (default); 'plain' is bare text for tools; " +
        "'json' is a SchemaValidationReport serialized as JSON.",
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
        i.severity == IssueSeverity.Fatal || i.severity == IssueSeverity.Error ||
        (options.strict && i.severity == IssueSeverity.Warning)
      },
    )
    if failed then exit(1)

  /** Load the schema and collect every issue.
    *
    * Fatal problems can't be recovered into a [[SchemaView]] (its constructor refuses to build a
    * schema with fatal problems), so loading returns them as a Left. Errors and warnings come from
    * the linter on the successfully-built view.
    */
  private def collectIssues(inputName: String): Seq[SchemaIssue] =
    try
      SchemaView.loadSchemaViewFromUri(inputName) match {
        case Right(sv) => SchemaValidator(using sv).lintProblems
        case Left(problems) => problems
      }
    catch
      // Only unexpected failures land here - a custom importer throwing, say. Reported as an import
      // failure so that every issue, including this one, is structured.
      case ex if NonFatal(ex) =>
        val msg = ex.getMessage
        Seq(
          new SchemaImportErrorImpl(
            location = IssueLocationImpl(),
            importUri = inputName,
            reason = if (msg ne null) msg else ex.toString,
          ),
        )
}
