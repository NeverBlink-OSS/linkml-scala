package eu.neverblink.linkml.cli

import caseapp.*

@HelpMessage("Validate a LinkML schema")
@ArgsName("<input-file>")
final case class ValidateOptions()

object Validate extends BaseCommand[ValidateOptions] {
  override def run(options: ValidateOptions, remainingArgs: RemainingArgs): Unit =
    loadSchema(remainingArgs.remaining.headOption)
    printLine("Schema is valid.")
}
