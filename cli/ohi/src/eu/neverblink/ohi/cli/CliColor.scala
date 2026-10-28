package eu.neverblink.ohi.cli

import caseapp.core.Error
import caseapp.core.argparser.{ArgParser, SimpleArgParser}

import java.io.Console

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

  /** Returns false when the current console's terminal attachment cannot be established. */
  def terminalAttached: Boolean = Option(System.console()).exists { console =>
    // JDK 22–24 can supply a console for pipes. Reflection retains Java 17 compatibility.
    try classOf[Console].getMethod("isTerminal").invoke(console).asInstanceOf[Boolean]
    catch {
      case _: NoSuchMethodException =>
        Option(System.getProperty("jdk.console")).forall(_ == "java.base")
      case _: ReflectiveOperationException | _: SecurityException => false
    }
  }
}
