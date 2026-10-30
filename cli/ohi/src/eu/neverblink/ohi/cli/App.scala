package eu.neverblink.ohi.cli

import caseapp.*
import caseapp.core.Error
import caseapp.core.help.HelpFormat
import caseapp.core.util.fansi
import eu.neverblink.linkml.cli.{App, Generate, Version, VersionCommand, VersionOptions}

import java.io.PrintStream

object Main {
  def main(args: Array[String]): Unit = new OhiApp().main(args)
}

final class OhiApp private[cli] (
    testMode: Boolean,
    outStream: PrintStream,
    errStream: PrintStream,
) extends App(testMode, outStream, errStream) {

  def this() = this(
    testMode = false,
    outStream = System.out,
    errStream = System.err,
  )

  private val enginePrefix = List("linkml")

  private[cli] val version: VersionCommand = new VersionCommand("ohi") {
    override def names: List[List[String]] =
      (super.names ++ super.names.map(enginePrefix ++ _)).distinct

    override def helpFormat: HelpFormat = HelpFormat.default(CliFormatting.enabled(this.outStream))

    override def helpAsked(progName: String, options: Either[Error, VersionOptions]): Nothing = {
      printLine(HelpOutput.existing(finalHelp, progName, CliFormatting.enabled(this.outStream)))
      exit(0)
    }
  }

  override def progName: String = "ohi"

  override def commands: Seq[Command[?]] = super.commands.flatMap {
    case Version => Seq(version)
    // `linkml generate <name>` is a hidden alias for `linkml to <name>`
    case command: Generate[?] =>
      Seq(new PrefixedCommand(command, "to"), new PrefixedCommand(command, "generate", true))
    case command => Seq(new PrefixedCommand(command))
  } ++ Seq(Search, Inspect, Get, Init, Add, Lock)

  override def main(args: Array[String]): Unit = {
    val words = args.toList
    val group = (if words.lastOption.exists(helpFlags.contains) then words.init else words) match {
      case `enginePrefix` :+ "generate" => enginePrefix :+ "to"
      case other => other
    }
    if Set(enginePrefix, enginePrefix :+ "to", enginePrefix :+ "from").contains(group) then {
      val output = new StringBuilder
      val colored = CliFormatting.enabled(outStream)
      val format =
        if colored then HelpFormat.default(false).copy(commandName = fansi.Color.Cyan)
        else HelpFormat.default(false)
      help.copy(commands = help.commands.filter(_.names.head.startsWith(group)))
        .printCommands(output, format, showHidden = false)
      printLine(output.result().linesIterator.map { line =>
        if line.nonEmpty && !line.startsWith(" ") then CliFormatting.styled(line, "1", colored)
        else line
      }.mkString("\n"))
      exit(0)
    } else if words.nonEmpty && !words.startsWith(enginePrefix) &&
      !helpFlags.contains(words.head) && commandFor(words).isEmpty
    then {
      val message =
        if words.head.startsWith("-") then Error.UnrecognizedArgument(words.head).message
        else s"Unknown command '${words.head}'."
      err(
        s"${CliFormatting.styled("Error:", "1;31", CliFormatting.enabled(errStream))} $message\n\n" +
          "Run 'ohi --help' to see available commands.",
      )
    } else super.main(args)
  }

  override def printUsage(): Nothing = {
    printLine(HelpOutput.root(CliFormatting.enabled(outStream)))
    exit(0)
  }

  /** Puts `command` under `linkml`, optionally replacing its first word with `verb`. */
  private final class PrefixedCommand[T](
      command: Command[T],
      verb: String = "",
      alias: Boolean = false,
  ) extends Command[T](using command.parser0, command.messages) {
    override def names: List[List[String]] = command.names.map { name =>
      enginePrefix ++ (if verb.isEmpty then name else verb :: name.tail)
    }
    override def group: String = if verb.isEmpty then command.group else verb
    override def hidden: Boolean = alias || command.hidden
    override lazy val finalHelp: Help[?] = command.finalHelp

    override def helpFormat: HelpFormat = HelpFormat.default(CliFormatting.enabled(outStream))

    override def printLine(line: String, toStderr: Boolean): Unit =
      command.printLine(line, toStderr)

    override def exit(code: Int): Nothing = command.exit(code)

    override def error(message: Error): Nothing = command.error(message)

    override def helpAsked(progName: String, options: Either[Error, T]): Nothing = {
      printLine(HelpOutput.existing(finalHelp, progName, CliFormatting.enabled(outStream)))
      exit(0)
    }

    override def run(options: T, remainingArgs: RemainingArgs): Unit =
      command.run(options, remainingArgs)
  }
}
