package eu.neverblink.linkml.optiongen

import java.io.{OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import org.scalafmt.interfaces.{Scalafmt, ScalafmtReporter}

object ScalaFormatter {
  def withFormatter[A](config: Path)(f: ((Path, String) => String) => A): A = {
    val reporter = new ScalafmtReporter {
      override def error(file: Path, message: String): Unit =
        throw new IllegalArgumentException(s"$file: $message")

      override def error(file: Path, cause: Throwable): Unit =
        throw new IllegalArgumentException(s"$file: formatting failed", cause)

      override def excluded(file: Path): Unit =
        error(file, "generated target excluded by formatter configuration")

      override def parsedConfig(file: Path, version: String): Unit =
        if version != "3.11.5" then error(file, s"unexpected formatter version $version")

      override def downloadWriter(): PrintWriter = new PrintWriter(System.err)

      override def downloadOutputStreamWriter(): OutputStreamWriter =
        new OutputStreamWriter(System.err, UTF_8)
    }
    val formatter = Scalafmt.create(classOf[Scalafmt].getClassLoader).withReporter(reporter)
    try {
      val session = formatter.createSession(config.toAbsolutePath)
      f { (path, source) =>
        val result = session.formatOrError(path.toAbsolutePath, source)
        if result.exception != null then
          throw new IllegalArgumentException(s"$path: formatting failed", result.exception)
        result.value
      }
    } finally formatter.clear()
  }
}
