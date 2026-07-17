package eu.neverblink.linkml.cli

import eu.neverblink.linkml.schemaview.SchemaProblem

/** ANSI escape codes for the terminal report. Kept private to this file. */
private object Ansi {
  val reset = "\u001b[0m"
  val bold = "\u001b[1m"
  val dim = "\u001b[2m"
  val red = "\u001b[31m"
  val brightRed = "\u001b[91m"
  val yellow = "\u001b[33m"
  val green = "\u001b[32m"
}

/** Renders the results of schema validation for the `validate` command.
  *
  * The rendering is a pure function of the issues and the chosen [[Format]], so it can be tested
  * without running a schema through the validator.
  */
object ValidationReport {

  /** Output format for the validation report. */
  enum Format:
    /** Human-friendly, colored, with icons and a summary. */
    case Terminal

    /** Bare `LEVEL: message` lines for machine consumption. */
    case Plain

  object Format:
    def parse(s: String): Option[Format] = s.toLowerCase match
      case "terminal" => Some(Terminal)
      case "plain" => Some(Plain)
      case _ => None

    /** Human-readable list of the supported values, for help / error messages. */
    val supported: String = "terminal|plain"

  /** Severity of an issue. Declaration order is display / sort order (most severe first). */
  enum Severity(val label: String, val icon: String, val color: String):
    case Fatal extends Severity("FATAL", "✖", Ansi.brightRed) // ✖
    case Error extends Severity("ERROR", "✖", Ansi.red) // ✖
    case Warning extends Severity("WARNING", "⚠", Ansi.yellow) // ⚠

  final case class Issue(severity: Severity, message: String)

  /** Map structured schema problems to display issues. */
  def issuesOf(problems: Seq[SchemaProblem]): Seq[Issue] =
    problems.map { p =>
      val severity = p match
        case _: SchemaProblem.Fatal => Severity.Fatal
        case _: SchemaProblem.Error => Severity.Error
        case _: SchemaProblem.Warning => Severity.Warning
      Issue(severity, p.description)
    }

  /** Render a full validation report.
    *
    * @param schemaName
    *   Name of the validated schema, shown in the terminal header.
    * @param issues
    *   The issues found; empty means the schema is valid.
    * @param format
    *   The output format.
    */
  def render(schemaName: String, issues: Seq[Issue], format: Format): String =
    format match
      case Format.Plain => renderPlain(issues)
      case Format.Terminal => renderTerminal(schemaName, issues)

  private def renderPlain(issues: Seq[Issue]): String =
    if issues.isEmpty then "Schema is valid."
    else
      val lines = sorted(issues).map(i => s"${i.severity.label}: ${i.message}")
      (lines :+ "" :+ summaryText(issues)).mkString("\n")

  private def renderTerminal(schemaName: String, issues: Seq[Issue]): String =
    import Ansi.*
    val sb = new StringBuilder
    sb.append(s"${dim}Validating $schemaName$reset\n\n")
    if issues.isEmpty then
      sb.append(s"$green$bold✔ Schema is valid.$reset") // ✔
    else
      val entries = sorted(issues)
      val labelWidth = entries.iterator.map(_.severity.label.length).max
      for i <- entries do
        val s = i.severity
        val label = s.label.padTo(labelWidth, ' ')
        sb.append(s"  ${s.color}${s.icon} $bold$label$reset  ${i.message}\n")
      sb.append("\n")
      val summary = summarySeverity(issues)
      sb.append(s"  ${summary.color}$bold${summary.icon} ${summaryText(issues)}$reset")
    sb.toString

  private def sorted(issues: Seq[Issue]): Seq[Issue] =
    issues.sortBy(_.severity.ordinal)

  /** The most severe severity present; only called when [[issues]] is non-empty. */
  private def summarySeverity(issues: Seq[Issue]): Severity =
    issues.map(_.severity).minBy(_.ordinal)

  /** e.g. "1 error, 2 warnings" — counts per severity, most severe first, only non-zero. */
  private def summaryText(issues: Seq[Issue]): String =
    val parts = Severity.values.toSeq.flatMap { sev =>
      val n = issues.count(_.severity == sev)
      if n == 0 then None else Some(s"$n ${noun(sev)}${if n == 1 then "" else "s"}")
    }
    if parts.isEmpty then "no issues" else parts.mkString(", ")

  private def noun(severity: Severity): String = severity match
    case Severity.Fatal => "fatal error"
    case Severity.Error => "error"
    case Severity.Warning => "warning"
}
