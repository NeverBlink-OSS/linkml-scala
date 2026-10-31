package eu.neverblink.linkml.generator

import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromString, writeToString}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

/** One Python process that runs a command per request. Used in tests that would otherwise start
  * Python for every check, which is very slow.
  *
  * [[script]] reads a JSON array of arguments per line, and answers each with a JSON line holding
  * the command's `exitCode` and `output`.
  */
final class PythonServer(python: os.Path, script: String, scriptArgs: os.Shellable*)
    extends AutoCloseable {
  import PythonServer.{*, given}

  private var process: Option[os.SubProcess] = None

  /** Run the command with [[args]] and return its exit code and output. */
  def call(args: os.Shellable*): Result = synchronized {
    val running = process.getOrElse {
      val started = os.proc(python, "-c", script, scriptArgs).spawn(stderr = os.Inherit)
      process = Some(started)
      started
    }
    running.stdin.writeLine(writeToString(args.flatMap(_.value)))
    running.stdin.flush()
    val reply = running.stdout.readLine()
    if reply == null then throw RuntimeException("the Python process stopped, see its output above")
    readFromString[Result](reply)
  }

  def close(): Unit = synchronized {
    process.foreach(_.destroy())
    process = None
  }
}

object PythonServer {
  final case class Result(exitCode: Int, output: String)

  given JsonValueCodec[Result] = JsonCodecMaker.make
  given JsonValueCodec[Seq[String]] = JsonCodecMaker.make

  val runScript: String =
    """import contextlib, io, json, runpy, sys, traceback
      |
      |script = sys.argv[1]
      |for line in sys.stdin:
      |    output = io.StringIO()
      |    sys.argv = [script] + json.loads(line)
      |    try:
      |        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
      |            runpy.run_path(script, run_name="__main__")
      |        code = 0
      |    except SystemExit as e:
      |        code = e.code if isinstance(e.code, int) else (0 if e.code is None else 1)
      |    except Exception:
      |        output.write(traceback.format_exc())
      |        code = 1
      |    print(json.dumps({"exitCode": code, "output": output.getvalue()}), flush=True)
      |""".stripMargin
}
