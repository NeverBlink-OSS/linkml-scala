package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.schemaview.SchemaView

import java.io.{ByteArrayOutputStream, PrintStream}
import scala.util.control.NonFatal

/** Thrown instead of a real process exit when a command runs in test mode. */
final case class ExitException(code: Int) extends RuntimeException

/** Base for all CLI commands. Output goes through overridable streams so tests can capture
  * stdout/stderr via [[runTestCommand]] without touching globals.
  */
abstract class BaseCommand[T: {Parser, Help}] extends Command[T] {
  private var testMode = false
  protected var out: PrintStream = System.out
  protected var errStream: PrintStream = System.err

  final override def printLine(line: String, toStderr: Boolean): Unit =
    if toStderr then errStream.println(line) else out.println(line)

  final override def exit(code: Int): Nothing =
    if testMode then throw ExitException(code) else super.exit(code)

  protected final def err(message: String): Nothing = {
    printLine(message, toStderr = true)
    exit(1)
  }

  def loadSchema(inFile: Option[String]): SchemaView =
    inFile match {
      case None => err("Input file is required."); null
      case Some(inputName) =>
        try SchemaView.loadSchemaViewFromUri(inputName)
        catch {
          case ex if NonFatal(ex) =>
            err("Cannot load schema: " + ex.getMessage); null
        }
    }

  /** Runs the whole CLI (via [[App]]) with the given args, capturing stdout and stderr. For tests
    * only.
    */
  final def runTestCommand(args: List[String]): (String, String) = {
    val (out, err, _) = runTestCommandWithExitCode(args)
    (out, err)
  }

  /** Like [[runTestCommand]] but also returns the exit code (0 if the command didn't call `exit`).
    * For tests only.
    */
  final def runTestCommandWithExitCode(args: List[String]): (String, String, Int) = {
    val bufOut = ByteArrayOutputStream()
    val bufErr = ByteArrayOutputStream()
    testMode = true
    out = PrintStream(bufOut, true, "UTF-8")
    errStream = PrintStream(bufErr, true, "UTF-8")
    var exitCode = 0
    try App.main(args.toArray)
    catch { case ExitException(code) => exitCode = code }
    finally {
      out.flush()
      errStream.flush()
      out = System.out
      errStream = System.err
      testMode = false
    }
    (bufOut.toString("UTF-8"), bufErr.toString("UTF-8"), exitCode)
  }
}
