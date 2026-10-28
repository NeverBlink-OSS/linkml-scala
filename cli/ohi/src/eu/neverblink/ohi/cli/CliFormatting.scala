package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.cli.TerminalOutput
import eu.neverblink.ohi.model.generated.Format

import java.io.PrintStream

private[cli] object CliFormatting {
  val formats: Seq[(Format, String)] = Seq(
    Format.OwlTurtle -> "OWL (Turtle)",
    Format.OwlRdfxml -> "OWL (RDF/XML)",
    Format.RdfsTurtle -> "RDFS (Turtle)",
    Format.LinkmlYaml -> "LinkML (YAML)",
    Format.OssieYaml -> "Apache Ossie (YAML)",
  )

  def formatsHelp(colored: Boolean = false): String =
    styled("Supported formats", "1", colored) + "\n" + formats.map { (format, label) =>
      "  " + styled(f"${stringify(format)}%-14s", "36", colored) + label
    }.mkString("\n")

  def enabled(stream: PrintStream): Boolean =
    TerminalOutput.colored(stream)

  /** Apply an ANSI SGR code with a trailing reset, or return the text unchanged when disabled. */
  def styled(text: String, code: String, colored: Boolean): String =
    if colored && text.nonEmpty then s"\u001b[${code}m$text\u001b[0m" else text

  def action(verb: String, path: String, colored: Boolean): String =
    styled(verb, "32", colored) + " " + styled(path, "36", colored)

  /** Normalize untrusted display fields without changing stored or structured values. */
  def clean(value: String): String =
    TerminalOutput.withoutAnsi(value)
      .replaceAll("(?U)[\\p{Cc}\\p{Cf}&&[^\\s]]", "")
      .replaceAll("(?U)\\s+", " ")
      .trim

  /** Quote one shell argument, leaving safe, readable tokens unquoted. */
  def quote(value: String, always: Boolean = false): String =
    if !always && value.nonEmpty && value.forall(character =>
        character.isLetterOrDigit || "_@%+=:,./-".contains(character),
      )
    then value
    else "'" + value.replace("'", "'\\''") + "'"
}
