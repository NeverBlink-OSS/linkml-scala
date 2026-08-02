package eu.neverblink.linkml.cli

import caseapp.*
import caseapp.core.commandparser.RuntimeCommandParser

import java.io.PrintStream

final class App private[cli] (
    testMode: Boolean,
    outStream: PrintStream,
    errStream: PrintStream,
) extends CommandsEntryPoint {

  // Default constructor for the CLI entrypoint.
  def this() = this(
    testMode = false,
    outStream = System.out,
    errStream = System.err,
  )

  override def progName: String = "linkml-scala"

  override def commands: Seq[Command[?]] = Seq(
    Validate,
    Shacl,
    JsonSchema,
    Scala,
    Rdfs,
    LinkMl,
    TableSchema,
    Version,
  )

  /** Flags that are not a command, but must still be treated as success. */
  private val helpFlags = Set("--help", "-h", "-help", "--usage")

  override def printLine(line: String, toStderr: Boolean): Unit =
    if toStderr then errStream.println(line) else outStream.println(line)

  override def exit(code: Int): Nothing =
    if testMode then throw ExitException(code) else super.exit(code)

  protected def err(message: String): Nothing =
    printLine(message, toStderr = true)
    exit(1)

  /** case-app answers an unrecognized command by printing the usage and exiting 0, so a typo like
    * `linkml-scala valdate model.yaml` looks like success. Report it as an error instead.
    */
  override def main(args: Array[String]): Unit =
    if args.nonEmpty && !helpFlags.contains(args.head) &&
      RuntimeCommandParser.parse(commands, args.toList).isEmpty
    then
      printLine(s"Unknown command: ${unrecognizedName(args)}", toStderr = true)
      printLine(s"Run '$progName --help' to see the available commands.", toStderr = true)
      exit(1)
    else super.main(args)

  /** The part of args the user meant as a command name: the leading non-option arguments, cut off
    * after the first one that no command name continues. So `genrate json-schema model.yaml`
    * reports `genrate`, while `generate grahpql model.yaml` reports `generate grahpql`.
    */
  private def unrecognizedName(args: Array[String]): String =
    val names = commands.flatMap(_.names)
    val words = args.toSeq.takeWhile(!_.startsWith("-"))
    val known = words.inits.find(prefix => names.exists(_.startsWith(prefix))).getOrElse(Seq.empty)
    (if words.isEmpty then args.toSeq else words).take(known.size + 1).mkString(" ")
}
