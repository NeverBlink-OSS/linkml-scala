package eu.neverblink.ohi.cli

private[cli] object CliFormatting {

  /** Apply an ANSI SGR code with a trailing reset, or return the text unchanged when disabled. */
  def styled(text: String, code: String, colored: Boolean): String =
    if colored && text.nonEmpty then s"\u001b[${code}m$text\u001b[0m" else text
}
