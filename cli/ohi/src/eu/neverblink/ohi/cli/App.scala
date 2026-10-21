package eu.neverblink.ohi.cli

import caseapp.*
import eu.neverblink.linkml.cli.{App, Version, VersionCommand}

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
  }

  override def progName: String = "ohi"

  override def commands: Seq[Command[?]] = super.commands.map {
    case Version => version
    case command => new PrefixedCommand(command)
  } ++ Seq(Search, Inspect, Get, Init, Add, Lock)

  override def main(args: Array[String]): Unit = {
    val words = args.toList
    if enginePrefix.nonEmpty && words.startsWith(enginePrefix) &&
      (words == enginePrefix ||
        (words.size == enginePrefix.size + 1 && helpFlags.contains(words.last)))
    then printUsage()
    else super.main(args)
  }

  private final class PrefixedCommand[T](command: Command[T])
      extends Command[T](using command.parser0, command.messages) {
    override def names: List[List[String]] = command.names.map(enginePrefix ++ _)
    override def group: String = command.group
    override def hidden: Boolean = command.hidden
    override lazy val finalHelp: Help[?] = command.finalHelp

    override def main(progName: String, args: Array[String]): Unit = command.main(progName, args)

    override def run(options: T, remainingArgs: RemainingArgs): Unit =
      command.run(options, remainingArgs)
  }
}
