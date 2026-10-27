package eu.neverblink.linkml.cli

import caseapp.*
import eu.neverblink.linkml.schemaview.{
  FileSystemImporter,
  ImportMap,
  ImportMapImporter,
  Importer,
  SchemaIssues,
  SchemaView,
}
import eu.neverblink.linkml.rdf.RdfParseException
import eu.neverblink.linkml.runtime.FastUtils.*
import eu.neverblink.linkml.runtime.SchemaConversionException

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}
import scala.util.control.NonFatal

/** Thrown instead of a real process exit when a command runs in test mode. */
final case class ExitException(code: Int) extends RuntimeException

object BaseCommand {

  /** The help text of the `--import-map` option. */
  final val importMapHelp =
    "A JSON file that maps imports to the locations to load them from, such as " +
      "{\"https://example.org/units/\": \"deps/units/\"}. Keys are full URIs or paths, not " +
      "CURIEs. A key that ends with / or # matches every import that starts with it. A relative " +
      "location is relative to the current directory."
}

/** Base for all CLI commands. Output goes through overridable streams so tests can capture
  * stdout/stderr via [[runTestCommand]] without touching globals.
  */
abstract class BaseCommand[T: {Parser, Help}] extends Command[T] {
  private var testMode = false
  protected var outStream: PrintStream = System.out
  protected var errStream: PrintStream = System.err

  final override def printLine(line: String, toStderr: Boolean): Unit =
    if toStderr then errStream.println(line) else outStream.println(line)

  final override def exit(code: Int): Nothing =
    if testMode then throw ExitException(code) else super.exit(code)

  protected final def err(message: String): Nothing =
    printLine(message, toStderr = true)
    exit(1)

  /** Prints `message` with the reason for `e`, without a stack trace, and exits with 1, if `e` is a
    * problem with the input that the user can fix: a [[SchemaConversionException]] or an
    * [[RdfParseException]]. Any other exception, such as a bug, is thrown again, so its stack trace
    * is printed. So is an [[ExitException]], as the command has already printed why it stopped.
    */
  protected final def err(message: String, e: Throwable): Nothing = e match {
    case _: SchemaConversionException | _: RdfParseException => err(s"$message: ${e.getMessage}")
    case _ => throw e
  }

  /** The importer for the JSON file of the `--import-map` option, if there is one. */
  protected final def importer(importMapFile: Option[String]): Importer =
    importMapFile.foldFast(FileSystemImporter: Importer) { file =>
      val json =
        try os.read(os.Path(file, os.pwd))
        catch { case NonFatal(e) => err(s"Cannot read --import-map '$file': ${e.getMessage}") }
      ImportMap.fromJson(json, file) match {
        case Right(map) => ImportMapImporter(map, FileSystemImporter)
        case Left(issue) => err("Invalid --import-map: " + SchemaIssues.verbose(issue.infer()))
      }
    }

  def loadSchema(inFile: Option[String], importMapFile: Option[String] = None): SchemaView =
    inFile.foldFast {
      err("Input file is required.")
    } { inputName =>
      SchemaView.loadSchemaViewFromUri(inputName, importer(importMapFile)) match {
        case Right(sv) => sv
        case Left(problems) =>
          val formatted = SchemaIssues.format(
            problems.map(_.infer()),
            maxProblems = 5,
            verbose = true,
            showLevel = false,
          )
          err("Cannot load schema: " + formatted)
      }
    }

  /** Write to `file`, or to the command's stdout when there is none.
    *
    * A regular file is written to a temporary file next to it first, which then replaces it. So if
    * `write` fails, the previous `file` is kept, or none is made, and no partially written file is
    * left behind. Anything else that exists at `file`, such as a link or `/dev/stdout`, is written
    * to as it is.
    */
  protected final def writeToFileOrStdout(file: Option[String], write: OutputStream => Unit): Unit =
    file.foldFast {
      // `out` is the command's stdout (redirected in tests). Flush but never close it.
      write(outStream)
      outStream.flush()
    } { value =>
      val path = os.Path(value, os.pwd)
      if (os.exists(path, followLinks = false) && !os.isFile(path, followLinks = false))
        writeTo(path, write)
      else {
        val temp = path / os.up / ("." + path.last + ".tmp")
        try {
          writeTo(temp, write)
          os.move(temp, path, replaceExisting = true)
        } catch {
          case NonFatal(e) =>
            try os.remove(temp)
            catch { case NonFatal(removeError) => e.addSuppressed(removeError) }
            throw e
        }
      }
    }

  private def writeTo(path: os.Path, write: OutputStream => Unit): Unit = {
    val stream = os.write.over.outputStream(path)
    try write(stream)
    finally stream.close()
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
  final def runTestCommandWithExitCode(args: List[String]): (String, String, Int) =
    runTestCommandWithExitCode(args, args => App(testMode, outStream, errStream).main(args))

  /** Runs a supplied entry point with this command's captured streams and test exits. */
  final def runTestCommandWithExitCode(
      args: List[String],
      runMain: Array[String] => Unit,
  ): (String, String, Int) = {
    val bufOut = ByteArrayOutputStream()
    val bufErr = ByteArrayOutputStream()
    testMode = true
    outStream = PrintStream(bufOut, true, "UTF-8")
    errStream = PrintStream(bufErr, true, "UTF-8")
    var exitCode = 0
    try runMain(args.toArray)
    catch { case ExitException(code) => exitCode = code }
    finally {
      outStream.flush()
      errStream.flush()
      outStream = System.out
      errStream = System.err
      testMode = false
    }
    (bufOut.toString("UTF-8"), bufErr.toString("UTF-8"), exitCode)
  }
}
