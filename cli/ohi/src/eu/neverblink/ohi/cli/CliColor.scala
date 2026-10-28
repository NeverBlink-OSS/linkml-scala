package eu.neverblink.ohi.cli

import caseapp.core.Error
import caseapp.core.argparser.{ArgParser, SimpleArgParser}

import eu.neverblink.linkml.cli.TerminalOutput

private[cli] enum CliColor {
  case Auto, Never

  def enabled(terminal: Boolean, term: Option[String]): Boolean =
    this == Auto && terminal && !term.contains("dumb")
}

private[cli] object CliColor {
  given ArgParser[CliColor] = SimpleArgParser.from("auto|never") {
    case "auto" => Right(CliColor.Auto)
    case "never" => Right(CliColor.Never)
    case value => Left(Error.MalformedValue("color", s"$value (expected auto or never)"))
  }

  def terminalAttached: Boolean = TerminalOutput.terminalAttached
}
