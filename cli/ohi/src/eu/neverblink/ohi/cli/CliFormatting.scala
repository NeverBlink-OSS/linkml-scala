package eu.neverblink.ohi.cli

import eu.neverblink.linkml.runtime.stringify
import eu.neverblink.linkml.cli.TerminalOutput
import eu.neverblink.ohi.model.generated.Format

import java.io.PrintStream
import java.nio.file.Path

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

  /** Displays paths relative to cwd when their filesystem roots permit it. */
  def displayPath(path: Path): String = {
    val current = Path.of("").toAbsolutePath.normalize()
    val absolute = path.toAbsolutePath.normalize()
    // Windows paths on different drives cannot be relativized.
    val display = if absolute.getRoot == current.getRoot then current.relativize(absolute).toString
    else absolute.toString
    if display.isEmpty then "." else display
  }

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
