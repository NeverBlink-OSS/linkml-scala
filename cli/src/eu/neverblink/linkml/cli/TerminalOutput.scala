package eu.neverblink.linkml.cli

import java.io.{Console, PrintStream}

/** Terminal styling shared by the engine and Ohi, without changing redirected content. */
object TerminalOutput {
  private val csi = "(?:\u001b\\[|\u009b)[0-?]*[ -/]*[@-~]".r

  def withoutAnsi(value: String): String = csi.replaceAllIn(value, "")

  def colored(stream: PrintStream): Boolean =
    ((stream eq System.out) || (stream eq System.err)) && terminalAttached &&
      !sys.env.get("TERM").contains("dumb")

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
