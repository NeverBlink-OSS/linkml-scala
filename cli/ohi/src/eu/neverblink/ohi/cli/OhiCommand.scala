package eu.neverblink.ohi.cli

import caseapp.*
import caseapp.core.Error
import eu.neverblink.linkml.cli.BaseCommand
import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.ohi.catalog.CatalogClient
import eu.neverblink.ohi.model.generated.{Artifact, Publication}

/** Ohi help and focused corrections, without changing engine commands or their diagnostics. */
abstract class OhiCommand[T: {Parser, Help}] extends BaseCommand[T] {
  protected def commandName: String
  private var arguments = Seq.empty[String]
  private var color = CliColor.Auto

  override def main(progName: String, args: Array[String]): Unit = {
    arguments = args.toSeq
    val (_, _, steps) = parser.scan(arguments, stopAtFirstUnrecognized, ignoreUnrecognized = true)
    color = if steps.exists {
        case Parser.Step.MatchedOption(index, consumed, arg) if arg.name.name == "color" =>
          arguments(index).endsWith("=never") || (consumed == 2 && arguments(index + 1) == "never")
        case _ => false
      }
    then CliColor.Never
    else CliColor.Auto
    try super.main(progName, args)
    finally {
      arguments = Seq.empty
      color = CliColor.Auto
    }
  }

  protected def colored: Boolean = color != CliColor.Never && CliFormatting.enabled(outStream)
  protected def errorColored: Boolean = color != CliColor.Never && CliFormatting.enabled(errStream)

  override def helpFormat: caseapp.core.help.HelpFormat =
    caseapp.core.help.HelpFormat.default(colored)

  override def helpAsked(progName: String, options: Either[Error, T]): Nothing = {
    printLine(HelpOutput.command(commandName, finalHelp, full = true, colored = colored))
    exit(0)
  }

  override def error(message: Error): Nothing = correction(message.message)

  protected def shortHelp(): Nothing =
    err(HelpOutput.command(commandName, finalHelp, full = false, colored = errorColored))

  protected def correction(
      message: String,
      detail: String = "",
      invalid: Boolean = true,
  ): Nothing = {
    val prefix = if invalid then CliFormatting.styled("Error:", "1;31", errorColored) + " " else ""
    err(
      prefix + message + (if detail.isEmpty then "" else "\n\n" + detail) +
        s"\n\nRun 'ohi $commandName --help' for all options.",
    )
  }

  protected def example(command: String): String = CliFormatting.styled(command, "36", errorColored)

  /** Rebuild a suggestion from parsed token spans, retaining options and safely quoting values. */
  protected def invocation(
      changes: Seq[(String, Option[String])] = Seq.empty,
      inputs: Option[Seq[String]] = None,
  ): String = {
    val (_, remaining, steps) = parser.scan(arguments, stopAtFirstUnrecognized, ignoreUnrecognized)
    val replacements = changes.map(_._1).toSet
    val options = steps.collect {
      case Parser.Step.MatchedOption(index, consumed, _)
          if !replacements.contains(arguments(index).takeWhile(_ != '=')) =>
        arguments.slice(index, index + consumed)
    }.flatten
    val updated = changes.flatMap { (name, value) =>
      value.toSeq.flatMap(v => Seq(name, v))
    } ++ options
    val positional = inputs.getOrElse(remaining.all)
    val words =
      if positional.exists(_.startsWith("-")) then updated ++ Seq("--") ++ positional
      else positional ++ updated
    (Seq("ohi", commandName) ++ words).map(CliFormatting.quote(_)).mkString(" ")
  }

  protected def reference(
      input: String,
      format: Option[String],
      client: CatalogClient,
  ): (Publication, Artifact) = {
    def formatCorrection(): Nothing = {
      val message = format.fold("Choose a file format with --format.")(value =>
        s"Unsupported format '${CliFormatting.clean(value)}'.",
      )
      correction(
        message,
        CliFormatting.formatsHelp(errorColored) + "\n\nExample with OWL (Turtle):\n  " +
          example(invocation(Seq("--format" -> Some("owl-turtle")))),
        invalid = format.nonEmpty,
      )
    }
    OntologyReference.resolve(input, format, client.search, client.inspect).fold(
      {
        case _: OntologyReference.Problem.MissingFormat |
            _: OntologyReference.Problem.InvalidFormat =>
          formatCorrection()
        case OntologyReference.Problem.Invalid(message) => correction(message)
        case OntologyReference.Problem.Unavailable(message, publication) =>
          correction(
            message,
            "Available formats\n" + publication.artifacts.map(a => stringify(a.format)).distinct
              .map("  " + _).mkString("\n"),
          )
        case OntologyReference.Problem.Ambiguous(message, publication, files) =>
          val changes = Seq("--format" -> None) ++
            Option.when(commandName == "add")("--artifact" -> Some(files.head.url.original))
          val selected =
            if commandName == "add" then publication.baseUrl.original else files.head.url.original
          correction(
            message,
            "Files\n" + files.map(a => "  " + a.url.original).mkString("\n") +
              "\n\nChoose a file URL, without --format. For example:\n  " +
              example(invocation(changes, Some(Seq(selected)))),
            invalid = false,
          )
      },
      identity,
    )
  }
}
